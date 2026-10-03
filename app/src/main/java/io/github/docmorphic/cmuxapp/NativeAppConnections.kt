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
    val compatibility = NativeMacCompatibilityRuntime(context.applicationContext, store, applicationActive, teams.state, teams::isCurrent)
    private val savedTailscale = NativeSavedTailscaleRuntime(teams.state, teams::isCurrent, { account.accessToken() },
        admitCompatibility = { team, client, host -> compatibility.gate.admit(team, client, host, locallyAuthorizedTailscale = true) },
        audience = compatibility.audience) { team ->
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
        savedTailscale = savedTailscale,
        admitCompatibility = { team, client, host, tailscale -> compatibility.gate.admit(team, client, host, tailscale) },
        audience = compatibility.audience)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val accountDeletion = NativeAccountDeletionController(scope, store::load, store::update) { login ->
        NativeAccountDeletionClient({ account.deletionCredentials(login) }, { store.taskSession() == login }).delete()
    }
    val presence = NativeMacPresenceRuntime(teams.state, applicationActive, teams::isCurrent, account::accessToken,
        audience = compatibility.audience)
    val ssh = NativeSshRuntime(context.applicationContext, store, scope)
    private val tailscale = TailscaleConnector(context, store, teams) { team, client, host ->
        compatibility.gate.admit(team, client, host, locallyAuthorizedTailscale = true)
    }
    val connector = object : NativeConnector {
        override suspend fun connect(pairing: PairingCode.Tailscale, account: NativeAccount) = tailscale.connect(pairing, account)
        override suspend fun connectIroh(pairing: PairingCode.Iroh, account: NativeAccount) = native.connect(pairing)
        override fun authorizePairing(pairing: PairingCode.Tailscale) = tailscale.authorizePairing(pairing)
        override fun pairingCompatibilityError(pairing: PairingCode): String? =
            (pairing as? PairingCode.Iroh)?.buildTag?.takeUnless(compatibility.audience::allowsTag)
                ?.let { MacBuildNotSupported().message }
        override suspend fun connectSaved(mac: NativeCredentialStore.PairedMac, account: NativeAccount): MobileRpcClient {
            val team = checkNotNull(teams.state.value.scope) { "Refresh your account teams before connecting." }
            check(teams.isCurrent(team) && allowsSaved(mac) && store.visiblePairedMacs().contains(mac)) { "This computer is hidden, changed, or belongs to another account or team." }
            val pairing = PairingCodeParser.parse(mac.code).getOrThrow()
            val client = when (pairing) {
                is PairingCode.Tailscale -> tailscale.connectSaved(pairing, account, team)
                is PairingCode.Iroh -> native.connect(pairing.copy(userId = team.userId, teamId = team.teamId))
            }
            try {
                check(teams.isCurrent(team) && allowsSaved(mac) && store.visiblePairedMacs().contains(mac)) { "This computer is hidden or its account changed. Open Computers to reconnect." }
                return client
            } catch (failure: Throwable) { client.close(); throw failure }
        }
        override fun allowsSaved(mac: NativeCredentialStore.PairedMac): Boolean {
            if (!compatibility.audience.allowsSavedTag(mac.instanceTag)) return false
            val team = teams.state.value.scope ?: return false
            return teams.isCurrent(team) && runCatching {
                NativePairingRecords.usable(mac, team, TailscaleGrantStore(store::load, store::update)) &&
                    PairingCodeParser.parse(mac.code).getOrNull()?.let(::allowsSaved) == true
            }.getOrDefault(false)
        }
        override fun allowsSaved(pairing: PairingCode): Boolean {
            if (pairing is PairingCode.Tailscale) return tailscale.allowsSaved(pairing)
            pairing as PairingCode.Iroh
            if (!compatibility.audience.allowsSavedTag(pairing.buildTag)) return false
            val team = teams.state.value.scope ?: return false
            return teams.isCurrent(team) && (pairing.userId == null || pairing.userId == team.userId) &&
                (pairing.teamId == null || pairing.teamId == team.teamId)
        }
    }

    init {
        scope.launch { store.revisions.collect {
            teams.reconcileLogin()
            accountDeletion.reconcile()
            try { PhoneReplyNotices(context.applicationContext).sync() }
            catch (_: Exception) { currentCoroutineContext().ensureActive() }
        } }
        scope.launch {
            while (isActive) {
                try {
                    val pending = store.load()?.let { NativeNotificationDismissOutbox(it).pending() }.orEmpty()
                    if (pending.isNotEmpty()) flushNativeNotificationDismissals(pending, store.visiblePairedMacs(),
                        permits = { item, mac -> store.taskSession() == item.login &&
                            store.visiblePairedMacs().contains(mac) && connector.allowsSaved(mac) },
                        connect = { connector.connectSaved(it, account) },
                        acknowledge = { sent -> if (sent.isNotEmpty()) store.update { NativeNotificationDismissOutbox(it).acknowledge(sent) } })
                } catch (_: Exception) { currentCoroutineContext().ensureActive() }
                delay(5_000)
            }
        }
        scope.launch { teams.state.collect { tailscale.retireInvalid() } }
        scope.launch {
            var observedLogin: String? = null
            var nextRefresh = 0L
            while (isActive) {
                tailscale.retireInvalid()
                val login = store.taskSession()
                if (login != observedLogin) {
                    teams.reconcileLogin()
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
        compatibility.close(); presence.close(); ssh.close(); scope.cancel(); tailscale.close(); native.close(); teams.close()
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
