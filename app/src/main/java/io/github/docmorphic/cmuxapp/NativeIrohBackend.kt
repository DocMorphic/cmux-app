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
    suspend fun revokeComputer(target: NativeComputerTarget) { error("Computer removal is unavailable") }
    suspend fun refreshNetworking() { refresh() }
    fun endpointStatus(): IrxEndpointStatus? = null
    fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport
    fun transport(mac: IrohV2Computer, permits: () -> Boolean, intent: NativeMacDialIntent): MobileRpcTransport {
        check(intent.method == NativeMacConnectionMethod.IROH) { "Direct connections are unavailable" }
        return transport(mac, permits)
    }
    val connectionSettings: NativeMacConnectionStore? get() = null
    suspend fun awaitClosed() { }
    val privatePaths: NativePrivatePathStore? get() = null
}

/** Binds the enrolled key only when a computer is opened; discovery alone needs no QUIC endpoint. */
internal class NativeIrohBackend(
    private val key: IrohInstallationKey,
    private val control: IrohV2ControlSession,
    private val current: () -> Boolean,
    private val applicationActive: StateFlow<IrxProbeActivity>,
    override val privatePaths: NativePrivatePathStore,
    override val connectionSettings: NativeMacConnectionStore
) : IrohAccountBackend {
    private val lock = Any()
    private val endpointMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var endpoint: IrxEndpointRuntime? = null
    private var directEndpoint: IrxEndpointRuntime? = null
    private var closingEndpoints: List<IrxEndpointRuntime> = emptyList()
    private var closed = false
    override val state get() = control.state
    override suspend fun refresh() { control.refreshDirectory() }
    override suspend fun revokeComputer(target: NativeComputerTarget) { requireCurrent(); control.revokeComputer(target); requireCurrent() }
    override suspend fun refreshNetworking() {
        requireCurrent()
        control.refreshDirectory()
        requireCurrent()
        control.refreshRelays()
        requireCurrent()
    }
    override fun endpointStatus(): IrxEndpointStatus? = synchronized(lock) {
        requireCurrent()
        (endpoint ?: directEndpoint)?.status()
    }

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
        transport(mac, permits, connectionSettings.state.value.intent(mac))

    override fun transport(mac: IrohV2Computer, permits: () -> Boolean, intent: NativeMacDialIntent): MobileRpcTransport {
        check(intent.dialable) { "Direct mode needs an enabled address. Open Computer Details to add one." }
        val directOnly = intent.method == NativeMacConnectionMethod.DIRECT
        val allowed = { current() && !synchronized(lock) { closed } && permits() &&
            runCatching { connectionSettings.state.value.intent(mac) == intent }.getOrDefault(false) }
        return IrxMobileRpcTransport(establish = {
            check(allowed()) { "Computer connection settings changed" }
            val live = endpointMutex.withLock {
                requireCurrent()
                check(allowed()) { "Computer connection settings changed" }
                synchronized(lock) { if (directOnly) directEndpoint else endpoint } ?: run {
                    val bound = IrxEndpointRuntime.bind(key,
                        if (directOnly) emptyList() else state.value.credentials(),
                        pathMode = if (directOnly) IrxEndpointPathMode.DIRECT_ONLY else IrxEndpointPathMode.AUTOMATIC)
                    try {
                        currentCoroutineContext().ensureActive()
                        requireCurrent()
                        check(allowed()) { "Computer connection settings changed" }
                        synchronized(lock) {
                            check(!closed)
                            if (directOnly) directEndpoint = bound else endpoint = bound
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
            val relay = if (directOnly) null else (mac.relayUrls.firstOrNull() ?: state.value.directoryRelays.firstOrNull()
                ?: error("This Mac has no relay address yet"))
            val direct = if (directOnly) intent.addresses else privatePaths.addresses(mac)
            check(allowed()) { "Computer connection settings changed" }
            live.dial(mac.endpointId, relay, allowed, direct)
        }, permits = allowed, applicationActive = applicationActive)
    }

    private fun requireCurrent() {
        if (!current() || synchronized(lock) { closed }) throw CancellationException("Account session changed")
    }

    override fun close() {
        val old = synchronized(lock) {
            if (closed) return
            closed = true
            listOfNotNull(endpoint, directEndpoint).also { closingEndpoints = it; endpoint = null; directEndpoint = null }
        }
        control.close()
        scope.cancel()
        old.forEach { it.close() }
        key.close()
    }

    override suspend fun awaitClosed() {
        // Also wait for a bind that was in flight when close invalidated its owner.
        endpointMutex.withLock { }
        synchronized(lock) { closingEndpoints }.forEach { it.awaitClosed() }
    }

    private fun IrohV2ControlState.credentials() = relays.map { IrxRelayCredential(it.url, it.token, it.expiresAt) }

    companion object {
        fun create(context: Context, team: NativeTeamScope, account: NativeAccount,
                   current: () -> Boolean, applicationActive: StateFlow<IrxProbeActivity>): NativeIrohBackend {
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
                return NativeIrohBackend(key, control, current, applicationActive,
                    NativePrivatePathStore.create(application, key.identity()), NativeMacConnectionStore.create(application, team))
            } catch (failure: Throwable) { key.close(); throw failure }
        }
    }
}
