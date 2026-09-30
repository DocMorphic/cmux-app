package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class MacBrowserAvailability {
    AVAILABLE, NOT_CONNECTED, NEEDS_MAC_UPDATE, ROUTE_WITHOUT_LANES;
    val bindsBrowserToMac get() = this == AVAILABLE || this == NOT_CONNECTED
}

internal interface MacBrowserAccess : BrowserTunnelBackend {
    suspend fun availability(): MacBrowserAvailability
    suspend fun listeningPorts(): BrowserTunnelProtocol.ListeningPorts
}

/** One computer's network survives reconnects. Retirement cancels both Mac and direct traffic. */
internal class NativeMacBrowserNetwork(ownerScope: CoroutineScope, private val access: MacBrowserAccess,
    private val permits: () -> Boolean,
    direct: BrowserTunnelBackend = NioBrowserSocket.direct,
    private val now: () -> Long = System::nanoTime
) : AutoCloseable {
    private val scope = CoroutineScope(ownerScope.coroutineContext + SupervisorJob(ownerScope.coroutineContext[Job]))
    private val preparing = Mutex()
    private val router = MacBrowserRouter(MacBrowserLaneBackend(access), direct)
    private var proxy: BrowserSocksProxy? = null
    private var preferredPort = 0
    private var listedAt: Long? = null
    private var closed = false
    var listing: BrowserTunnelProtocol.ListeningPorts? = null
        private set

    private fun checkOwner() { check(!closed && permits()) { "Browser account or computer changed" } }
    private suspend fun <T> owned(action: suspend () -> T): T {
        val pending = scope.async { checkOwner(); action() }
        try { return pending.await() } finally { pending.cancel() }
    }
    suspend fun availability(): MacBrowserAvailability = owned { access.availability() }

    /** Call before navigation; a failed refresh retains the last confirmed Mac policy. */
    suspend fun prepare(loopbackPort: Int? = null): Int = owned {
        require(loopbackPort == null || loopbackPort in 1..65535)
        preparing.withLock {
            checkOwner()
            if (proxy?.isListening != true) {
                proxy?.stop()
                val backend = BrowserTunnelBackend { host, port, connected ->
                    owned { withContext(Dispatchers.IO) { router.use(host, port, connected) } }
                }
                proxy = if (preferredPort != 0) runCatching { BrowserSocksProxy.start(backend, port = preferredPort) }.getOrNull() else null
                if (proxy == null) proxy = BrowserSocksProxy.start(backend)
                preferredPort = checkNotNull(proxy).port
            }
            if (loopbackPort != null || listedAt?.let { now() - it > 10_000_000_000L } != false) {
                try {
                    val fresh = access.listeningPorts()
                    currentCoroutineContext().ensureActive(); checkOwner()
                    listing = fresh; listedAt = now()
                    router.allowsNonLoopbackHosts = fresh.allowsNonLoopbackHosts
                } catch (failure: Exception) {
                    // A connection lease may be retired without retiring the bound network.
                    currentCoroutineContext().ensureActive(); checkOwner()
                }
            }
            checkOwner()
            checkNotNull(proxy).port
        }
    }

    /** Owner-dispatcher confined, like the session registry. Closes listeners immediately. */
    override fun close() {
        if (closed) return
        closed = true
        proxy?.close(); proxy = null
        scope.cancel(); listing = null; listedAt = null
    }
}

/** Account/build identity, rather than whichever computer happens to be selected in the UI. */
internal class NativeBrowserNetworks(private val scope: CoroutineScope,
    private val access: (NativeCredentialStore.PairedMac, () -> Boolean) -> MacBrowserAccess,
    private val allows: (NativeCredentialStore.PairedMac, String) -> Boolean
) {
    private data class Owner(val login: String, val user: String?, val team: String?, val generation: Long?,
        val origin: String, val device: String, val build: String?)
    private var authorized = emptyMap<Owner, NativeCredentialStore.PairedMac>()
    private val networks = mutableMapOf<Owner, NativeMacBrowserNetwork>()

    fun retain(login: String?, team: NativeTeamScope?, macs: List<NativeCredentialStore.PairedMac>) {
        authorized = if (login == null || (team != null && team.login != login)) emptyMap() else macs
            .filter { allows(it, login) && (team == null ||
                ((it.accountUserId == null || it.accountUserId == team.userId) &&
                    (it.accountTeamId == null || it.accountTeamId == team.teamId))) }
            .associateBy { Owner(login, team?.userId ?: it.accountUserId, team?.teamId ?: it.accountTeamId,
                team?.generation, it.origin, canonicalMacDeviceId(it.deviceId), it.instanceTag?.trim()?.takeIf(String::isNotEmpty)) }
        networks.keys.toList().filter { it !in authorized }.forEach { networks.remove(it)?.close() }
    }

    fun network(mac: NativeCredentialStore.PairedMac): NativeMacBrowserNetwork? {
        val owner = authorized.entries.singleOrNull { it.value == mac }?.key ?: return null
        fun permitted() = authorized[owner]?.let { allows(it, owner.login) } == true
        if (!permitted()) return null
        return networks.getOrPut(owner) {
            // Re-resolve a replacement route for this identity on every operation.
            fun current(): MacBrowserAccess {
                check(permitted()) { "Browser account or computer changed" }
                return access(checkNotNull(authorized[owner]), ::permitted)
            }
            val context = scope.coroutineContext.minusKey(Job)
            val bound = object : MacBrowserAccess {
                override suspend fun availability() = withContext(context) { current().availability() }
                override suspend fun listeningPorts() = withContext(context) { current().listeningPorts() }
                override suspend fun use(host: String, port: Int, connected: suspend (BrowserTunnelLane) -> Unit) =
                    withContext(context) { current().use(host, port, connected) }
            }
            NativeMacBrowserNetwork(scope, bound, ::permitted)
        }
    }
    fun clear() { authorized = emptyMap(); networks.values.forEach { it.close() }; networks.clear() }
}
