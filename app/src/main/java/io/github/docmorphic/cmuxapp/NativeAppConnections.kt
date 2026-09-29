package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Activity recreation and the notification service share one enrolled endpoint per process. */
internal class NativeAppConnections private constructor(context: Context) : AutoCloseable {
    val store = NativeCredentialStore(context)
    val account = NativeAccount(store)
    val teams = NativeAccountTeams(account, store)
    private val activityLock = Any()
    private val activityOwners = mutableSetOf<Any>()
    private val applicationActive = MutableStateFlow(IrxProbeActivity(false))
    private val savedTailscale = NativeSavedTailscaleRuntime(teams.state, teams::isCurrent, { account.accessToken() }) { team ->
        val routes = NativeTailscaleRoutes(context.applicationContext, store, team)
        NativeSavedTailscaleAccount(NativeMacConnectionStore.create(context.applicationContext, team), store.revisions,
            routes::grants, routes::transport, resolve = { pairing ->
                if (pairing.macDeviceId != null && pairing.buildTag != null)
                    NativeComputerTarget(pairing.macDeviceId, pairing.buildTag, "Mac")
                else store.pairedMacs().mapNotNull { row ->
                    val saved = PairingCodeParser.parse(row.code).getOrNull() as? PairingCode.Iroh
                    if (saved?.endpointId == pairing.endpointId) NativeComputerTarget.from(row, team) else null
                }.distinctBy { canonicalMacDeviceId(it.deviceId) to it.buildTag }.singleOrNull()
            })
    }
    val native = NativeIrohRuntime(teams.state, teams::isCurrent, { account.accessToken() },
        { team, current -> NativeIrohBackend.create(context, team, account, current, applicationActive) },
        savedTailscale = savedTailscale)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tailscale = TailscaleConnector(context, store, teams)
    val connector = object : NativeConnector {
        override suspend fun connect(pairing: PairingCode.Tailscale, account: NativeAccount) = tailscale.connect(pairing, account)
        override suspend fun connectIroh(pairing: PairingCode.Iroh, account: NativeAccount) = native.connect(pairing)
        override fun authorizePairing(pairing: PairingCode.Tailscale) = tailscale.authorizePairing(pairing)
        override fun allowsSaved(pairing: PairingCode): Boolean {
            if (pairing is PairingCode.Tailscale) return tailscale.allowsSaved(pairing)
            pairing as PairingCode.Iroh
            val team = teams.state.value.scope ?: return false
            return teams.isCurrent(team) && (pairing.userId == null || pairing.userId == team.userId) &&
                (pairing.teamId == null || pairing.teamId == team.teamId)
        }
    }

    init {
        scope.launch { teams.state.collect { tailscale.retireInvalid() } }
        scope.launch {
            var observedLogin: String? = null
            var nextRefresh = 0L
            while (isActive) {
                tailscale.retireInvalid()
                val login = store.taskSession()
                if (login != observedLogin) {
                    teams.clear()
                    observedLogin = login
                    nextRefresh = 0
                }
                val time = System.nanoTime() / 1_000_000
                if (login != null && time >= nextRefresh) {
                    nextRefresh = time + try { teams.refresh(); 60_000 } catch (failure: Exception) {
                        currentCoroutineContext().ensureActive()
                        15_000
                    }
                }
                delay(2000)
            }
        }
    }

    fun setProbeActive(owner: Any, active: Boolean) = synchronized(activityLock) {
        if (active) activityOwners.add(owner) else activityOwners.remove(owner)
        val enabled = activityOwners.isNotEmpty()
        if (applicationActive.value.active != enabled)
            applicationActive.value = IrxProbeActivity(enabled, applicationActive.value.revision + 1)
    }

    override fun close() {
        synchronized(activityLock) {
            activityOwners.clear()
            applicationActive.value = IrxProbeActivity(false, applicationActive.value.revision + 1)
        }
        scope.cancel(); tailscale.close(); native.close(); teams.close()
    }

    class Handle internal constructor(val connections: NativeAppConnections) : AutoCloseable {
        private var released = false
        override fun close() = synchronized(sharedLock) {
            if (!released) {
                released = true
                references--
                if (references == 0) { shared = null; connections.close() }
            }
        }
    }

    companion object {
        private val sharedLock = Any()
        private var shared: NativeAppConnections? = null
        private var references = 0
        fun acquire(context: Context): Handle = synchronized(sharedLock) {
            val value = shared ?: NativeAppConnections(context.applicationContext).also { shared = it }
            references++
            Handle(value)
        }
    }
}

internal class NativeConnectionsViewModel(context: Context) : ViewModel() {
    private val handle = NativeAppConnections.acquire(context)
    val connections get() = handle.connections
    override fun onCleared() = handle.close()
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == NativeConnectionsViewModel::class.java)
            return NativeConnectionsViewModel(context.applicationContext) as T
        }
    }
}
