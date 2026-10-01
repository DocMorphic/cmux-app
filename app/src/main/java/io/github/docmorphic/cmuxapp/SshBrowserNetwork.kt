package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Every destination, including loopback, leaves via SSH. There is no direct fallback. */
internal class SshBrowserNetwork(lifetime: CoroutineScope, private val permitted: () -> Boolean,
    private val connection: suspend () -> SshTransport
) : RoutedBrowserNetwork, AutoCloseable {
    override val storageId = UUID.randomUUID().toString().replace("-", "")
    private val retirement = CompletableDeferred<Unit>()
    override val retired: Deferred<Unit> get() = retirement
    private val scope = CoroutineScope(lifetime.coroutineContext + SupervisorJob(lifetime.coroutineContext[Job]))
    private val preparing = Mutex()
    private val lock = Any()
    private var closed = false
    private var proxy: BrowserSocksProxy? = null
    val navigation = LocalBrowserNavigation(scope)

    init { scope.launch {
        try { while (isActive) { guard(); delay(100) } }
        catch (_: Exception) { /* Retirement is exposed through retired, not an uncaught UI exception. */ }
        finally { close() }
    } }
    private fun guard() = synchronized(lock) {
        check(!closed && scope.isActive && permitted()) { "SSH browser account or route changed" }
    }
    private suspend fun <T> owned(action: suspend () -> T): T {
        guard()
        val pending = scope.async { guard(); action() }
        try { return pending.await() } finally { pending.cancel() }
    }
    override suspend fun requiresProxy(): Boolean { guard(); return true }
    override suspend fun prepare(loopbackPort: Int?): Int = owned {
        require(loopbackPort == null || loopbackPort in 1..65535)
        preparing.withLock {
            guard()
            // Verify admission before giving WebView a listener. Later requests borrow
            // the current transport afresh, so a dropped socket can reconnect normally.
            connection(); guard()
            synchronized(lock) {
                guard()
                if (proxy?.isListening != true) {
                    proxy?.close()
                    proxy = BrowserSocksProxy.start(BrowserTunnelBackend { host, port, connected ->
                        owned {
                            val transport = connection(); guard()
                            val lane = transport.openTcp(host, port)
                            try {
                                guard()
                                // Android's asynchronous socket implementation can perform
                                // an immediate read/write on the calling thread.
                                withContext(Dispatchers.IO) { connected(lane) }
                            } finally { lane.close() }
                        }
                    })
                }
                checkNotNull(proxy).port
            }
        }
    }
    override fun close() {
        val previous = synchronized(lock) {
            if (closed) return
            closed = true; retirement.complete(Unit)
            proxy.also { proxy = null }
        }
        previous?.close(); scope.cancel(); navigation.clear()
    }
}

/** Account incarnation + immutable route/trust snapshots fence cached browser storage. */
internal class SshBrowserNetworks(private val hosts: SshHostStore,
    private val connections: SshConnections<SshTransport>, private val lifetime: CoroutineScope,
    private val admitted: () -> Boolean
) : AutoCloseable {
    private data class Entry(val plan: SshDialPlan, val trust: List<SshTrustSnapshot>, val network: SshBrowserNetwork)
    private val networks = mutableMapOf<UUID, Entry>()
    private var closed = false
    @Synchronized fun network(hostId: UUID): SshBrowserNetwork {
        check(!closed && admitted()) { "SSH account ended" }
        val plan = hosts.dialPlan(hostId)
        val trust = plan.hops.map { hosts.trustSnapshot(it.endpoint) }
        val previous = networks[hostId]
        if (previous != null && previous.plan == plan && previous.trust == trust && !previous.network.retired.isCompleted) return previous.network
        previous?.network?.close()
        val network = SshBrowserNetwork(lifetime, {
            admitted() && hosts.isCurrent(plan) && plan.hops.map { hosts.trustSnapshot(it.endpoint) } == trust
        }) {
            checkNotNull(connections.autoConnect(hostId)) { "SSH computer disconnected. Reconnect before browsing." }
        }
        networks[hostId] = Entry(plan, trust, network)
        return network
    }
    @Synchronized override fun close() {
        closed = true; networks.values.forEach { it.network.close() }; networks.clear()
    }
}
