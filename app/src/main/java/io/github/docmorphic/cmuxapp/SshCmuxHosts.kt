package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class SshCmuxHostState(val loading: Boolean = true, val available: Boolean = false,
    val providers: List<SshCmuxProvider> = emptyList(), val errors: List<String> = emptyList(),
    val platform: SshCmuxPlatform? = null, val operation: String? = null)

/** Existing cmux-tui owners on one SSH connection. Failed/stale sockets are
 * reported independently; they cannot hide reachable owners on the same host. */
internal class SshCmuxHost(val hostId: UUID, val connection: SshTransport, lifetime: CoroutineScope,
    private val admitted: () -> Boolean, private val installer: SshCmuxInstaller? = null) : AutoCloseable {
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(lifetime.coroutineContext + job + Dispatchers.Main.immediate)
    private val remote = SshCmuxRemote(connection, scope)
    private val mutable = MutableStateFlow(SshCmuxHostState())
    val state = mutable.asStateFlow()
    private val providers = linkedMapOf<String, SshCmuxProvider>()
    private val operations = Mutex()
    private var refreshJob: Job? = null
    private var refreshAgain = false
    private var closed = false
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { close() } }
        scope.launch { connection.disconnected.first { it }; close() }
        refresh()
    }
    private fun guard() { check(!closed && job.isActive && admitted() && connection.isConnected) { "SSH connection ended" } }
    fun refresh() {
        if (closed) return
        if (refreshJob?.isActive == true) { refreshAgain = true; return }
        refreshJob = scope.launch {
            mutable.value = mutable.value.copy(loading = true, errors = emptyList())
            try { do { refreshAgain = false; operations.withLock { discover() } } while (refreshAgain && !closed) }
            catch (failure: Exception) { if (failure !is CancellationException) mutable.value = mutable.value.copy(errors = listOf(failure.message ?: "Could not discover cmux-tui")) }
            finally { mutable.value = mutable.value.copy(loading = false) }
        }
    }
    private suspend fun discover() {
        guard()
        if (mutable.value.platform == null) {
            try { mutable.value = mutable.value.copy(platform = remote.probePlatform()) }
            catch (failure: Exception) { if (failure is CancellationException) throw failure }
        }
        val binary = remote.locateBinary(); guard()
        if (binary == null) {
            providers.values.toList().forEach { it.close() }; providers.clear()
            mutable.value = mutable.value.copy(available = false, providers = emptyList()); return
        }
        val sockets = remote.listSockets(); guard()
        val live = sockets.map { it.digest }.toSet()
        for (id in providers.keys.toList().filter { it !in live }) providers.remove(id)?.close()
        val errors = mutableListOf<String>()
        for (socket in sockets) {
            guard()
            val existing = providers[socket.digest]
            if (existing != null && !existing.state.value.ended) { existing.refresh(); continue }
            providers.remove(socket.digest)?.close()
            var control: SshCmuxControl? = null
            try {
                control = remote.connect(binary, socket); guard()
                val provider = SshCmuxProvider.open(control, scope) { !closed && job.isActive && admitted() && connection.isConnected }
                guard(); providers[socket.digest] = provider
            } catch (failure: Exception) {
                control?.close()
                if (failure is CancellationException) { currentCoroutineContext().ensureActive(); guard() }
                errors += "${socket.name ?: "Session ${socket.digest.take(8)}"}: ${failure.message ?: "Could not connect"}"
            }
        }
        guard(); mutable.value = mutable.value.copy(available = true, providers = providers.values.toList(), errors = errors)
    }
    /** Parent-owned after explicit user creation, so navigating away does not
     * cancel a submitted install/creation or cause an automatic replay. */
    suspend fun createWorkspace(): String = scope.async {
        operations.withLock {
            guard(); mutable.value = mutable.value.copy(operation = "Preparing workspace…")
            try {
                var binary = remote.locateBinary(); guard()
                if (binary == null) {
                    val platform = remote.probePlatform(); guard()
                    mutable.value = mutable.value.copy(platform = platform)
                    binary = checkNotNull(installer) { "cmux-tui installation is unavailable" }
                        .install(platform, connection) { mutable.value = mutable.value.copy(operation = it) }
                    guard()
                }
                val id = SshCmuxDiscovery.digest("cmux-android")
                var provider = providers[id]?.takeUnless { it.state.value.ended }
                if (provider == null) {
                    val control = remote.connectOwned(binary); guard()
                    try {
                        provider = SshCmuxProvider.open(control, scope) { !closed && job.isActive && admitted() && connection.isConnected }
                        guard(); providers.remove(id)?.close(); providers[id] = provider
                    } catch (failure: Exception) { control.close(); throw failure }
                }
                mutable.value = mutable.value.copy(available = true, providers = providers.values.toList(), operation = "Creating workspace…")
                provider.createWorkspace()
            } finally { mutable.value = mutable.value.copy(operation = null); if (!closed) refresh() }
        }
    }.await()
    override fun close() {
        if (closed) return
        closed = true; job.cancel(); providers.values.toList().forEach { it.close() }; providers.clear()
        mutable.value = mutable.value.copy(loading = false)
    }
}

internal class SshCmuxHosts(private val connections: SshConnections<SshTransport>, lifetime: CoroutineScope,
    private val admitted: () -> Boolean, private val installer: SshCmuxInstaller? = null) : AutoCloseable {
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(lifetime.coroutineContext + job + Dispatchers.Main.immediate)
    private val hosts = mutableMapOf<UUID, SshCmuxHost>()
    init { scope.launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { closeAll() } } }
    suspend fun open(id: UUID): SshCmuxHost = withContext(Dispatchers.Main.immediate) {
        check(job.isActive && admitted()) { "Sign in to open workspaces" }; adopt(id, connections.open(id))
    }
    suspend fun autoOpen(id: UUID): SshCmuxHost? = withContext(Dispatchers.Main.immediate) {
        check(job.isActive && admitted()) { "Sign in to open workspaces" }; connections.autoConnect(id)?.let { adopt(id, it) }
    }
    internal fun adopt(id: UUID, connection: SshTransport): SshCmuxHost {
        check(job.isActive && admitted() && connection.isConnected)
        hosts[id]?.takeIf { it.connection === connection }?.let { return it }
        hosts.remove(id)?.close()
        return SshCmuxHost(id, connection, scope, { job.isActive && admitted() }, installer).also { hosts[id] = it }
    }
    private fun closeAll() { hosts.values.toList().forEach { it.close() }; hosts.clear() }
    override fun close() { job.cancel(); scope.launch(NonCancellable) { closeAll() } }
}
