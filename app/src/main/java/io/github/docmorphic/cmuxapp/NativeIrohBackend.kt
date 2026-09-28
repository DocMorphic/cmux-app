package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Build
import io.github.docmorphic.cmuxapp.iroh.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface IrohAccountBackend : AutoCloseable {
    val state: StateFlow<IrohV2ControlState>
    suspend fun start()
    suspend fun refresh()
    fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport
    suspend fun awaitClosed() { }
}

/** Binds the enrolled key only when a computer is opened; discovery alone needs no QUIC endpoint. */
internal class NativeIrohBackend private constructor(
    private val key: IrohInstallationKey,
    private val control: IrohV2ControlSession,
    private val current: () -> Boolean
) : IrohAccountBackend {
    private val lock = Any()
    private val endpointMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var endpoint: IrxEndpointRuntime? = null
    private var closingEndpoint: IrxEndpointRuntime? = null
    private var closed = false
    override val state get() = control.state
    override suspend fun refresh() { control.refreshDirectory() }

    override suspend fun start() {
        control.connect()
        scope.launch {
            try {
                state.collect { snapshot ->
                    endpointMutex.withLock {
                        synchronized(lock) { endpoint }?.updateCredentials(snapshot.credentials())
                    }
                }
            } catch (failure: Exception) {
                if (failure !is CancellationException) close()
            }
        }
    }

    override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport =
        IrxMobileRpcTransport(establish = {
            val live = endpointMutex.withLock {
                requireCurrent()
                synchronized(lock) { endpoint } ?: run {
                    val bound = IrxEndpointRuntime.bind(key, state.value.credentials())
                    try {
                        currentCoroutineContext().ensureActive()
                        requireCurrent()
                        synchronized(lock) {
                            check(!closed)
                            endpoint = bound
                        }
                        bound
                    } catch (failure: Throwable) {
                        bound.close()
                        withContext(NonCancellable) { bound.awaitClosed() }
                        throw failure
                    }
                }
            }
            requireCurrent()
            // The directory provides the host's route; our relay credentials authenticate our endpoint.
            val relay = mac.relayUrls.firstOrNull() ?: state.value.directoryRelays.firstOrNull()
                ?: error("This Mac has no relay address yet")
            live.dial(mac.endpointId, relay, permits)
        }, permits = { current() && !synchronized(lock) { closed } && permits() })

    private fun requireCurrent() {
        if (!current() || synchronized(lock) { closed }) throw CancellationException("Account session changed")
    }

    override fun close() {
        val old = synchronized(lock) {
            if (closed) return
            closed = true
            endpoint.also { closingEndpoint = it; endpoint = null }
        }
        control.close()
        scope.cancel()
        old?.close()
        key.close()
    }

    override suspend fun awaitClosed() {
        // Also wait for a bind that was in flight when close invalidated its owner.
        endpointMutex.withLock { }
        synchronized(lock) { closingEndpoint }?.awaitClosed()
    }

    private fun IrohV2ControlState.credentials() = relays.map { IrxRelayCredential(it.url, it.token, it.expiresAt) }

    companion object {
        fun create(context: Context, team: NativeTeamScope, account: NativeAccount,
                   current: () -> Boolean): NativeIrohBackend {
            val application = context.applicationContext
            val key = IrohInstallationStore(application).loadOrCreate(IrohAccountScope(
                "production", NativeAccount.PROJECT_ID, team.teamId, team.userId,
                application.packageName, if (application.packageName.endsWith(".debug")) "debug" else "default"))
            try {
                @Suppress("DEPRECATION")
                val version = application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "0.2.0"
                val descriptor = IrohV2AndroidDevice.descriptor(key.identity(), key.endpointId,
                    version, Build.MODEL.take(118).ifBlank { "Android" }, IrohMobileWireProfile.IOS_COMPATIBILITY)
                val control = IrohV2ControlSession(IrohV2SignedRequests(descriptor, key::sign),
                    { force -> account.accessToken(force) ?: error("Sign in to cmux") }, current)
                return NativeIrohBackend(key, control, current)
            } catch (failure: Throwable) { key.close(); throw failure }
        }
    }
}
