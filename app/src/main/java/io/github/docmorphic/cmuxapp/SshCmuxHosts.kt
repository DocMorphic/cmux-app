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
    private val admitted: () -> Boolean, private val installer: SshCmuxInstaller? = null,
    private val drafts: SshComposerPool = SshComposerPool(),
    private val idlePolicy: () -> Long? = { 86400L }) : AutoCloseable {
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
        val prefix = "cmux-ssh-$hostId\n"
        drafts.discardWhere { id ->
            val target = id.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.let(SshWorkspaceTarget::decode) as? SshWorkspaceTarget.Cmux
            target != null && SshCmuxDiscovery.digest(target.selection.session) !in live
        }
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
                val provider = SshCmuxProvider.open(control, scope, idlePolicy, drafts, "cmux-ssh-$hostId\n",
                    { bytes, format -> guard(); SshFiles { connection }.uploadImage(bytes, format).also { guard() } }, connection.draftRoute) { !closed && job.isActive && admitted() && connection.isConnected }
                guard(); providers[socket.digest] = provider
            } catch (failure: Exception) {
                control?.close()
                if (failure is CancellationException) { currentCoroutineContext().ensureActive(); guard() }
                errors += "${socket.name ?: "Session ${socket.digest.take(8)}"}: ${failure.message ?: "Could not connect"}"
            }
        }
        guard(); mutable.value = mutable.value.copy(available = true, providers = providers.values.toList(), errors = errors)
    }
    /** Opening a saved terminal can restart our own owner, never a desktop
     * owner's session. It neither installs software nor creates new terminals. */
    suspend fun forSelection(selection: SshCmuxSelection): SshCmuxProvider = forSession(selection.session)
    suspend fun forSelection(selection: SshCmuxBrowserSelection): SshCmuxProvider = forSession(selection.session)
    private suspend fun forSession(session: String): SshCmuxProvider = withContext(Dispatchers.Main.immediate) {
        operations.withLock {
            guard(); require(SshCmuxDiscovery.validSession(session)) { "Invalid cmux-tui session" }
            val id = SshCmuxDiscovery.digest(session)
            providers[id]?.takeUnless { it.state.value.ended } ?: run {
                val binary = checkNotNull(remote.locateBinary()) { "cmux-tui is no longer installed on this computer" }
                guard(); acquire(binary, session)
            }
        }
    }
    // Called with operations held, so refresh and simultaneous view openings
    // cannot publish two controls for one owner.
    private suspend fun acquire(binary: String, session: String): SshCmuxProvider {
        guard()
        val id = SshCmuxDiscovery.digest(session)
        providers[id]?.takeUnless { it.state.value.ended }?.let { return it }
        val control = if (session == "cmux-android") remote.connectOwned(binary) else {
            val socket = checkNotNull(remote.listSockets().singleOrNull { it.serves(session) }) {
                "This desktop cmux-tui session is not running. Start it on the computer, then reconnect."
            }
            guard(); remote.connect(binary, socket)
        }
        try {
            guard()
            val provider = SshCmuxProvider.open(control, scope, idlePolicy, drafts, "cmux-ssh-$hostId\n",
                    { bytes, format -> guard(); SshFiles { connection }.uploadImage(bytes, format).also { guard() } }, connection.draftRoute) { !closed && job.isActive && admitted() && connection.isConnected }
            guard(); providers.remove(id)?.close(); providers[id] = provider
            mutable.value = mutable.value.copy(available = true, providers = providers.values.toList())
            return provider
        } catch (failure: Exception) { control.close(); throw failure }
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
                val provider = acquire(binary, "cmux-android")
                mutable.value = mutable.value.copy(operation = "Creating workspace…")
                provider.createWorkspace()
            } finally { mutable.value = mutable.value.copy(operation = null); if (!closed) refresh() }
        }
    }.await()
    suspend fun createWorkspaceTarget(): SshWorkspaceTarget.Cmux {
        val key = createWorkspace()
        val provider = forSession("cmux-android")
        val tree = checkNotNull(provider.state.value.tree) { "Created workspace inventory unavailable. Refresh before creating another." }
        val workspace = checkNotNull(tree.workspaces.singleOrNull { it.key == key }) { "Created workspace is no longer listed. Refresh before creating another." }
        val tab = checkNotNull(workspace.tabs.firstOrNull { it.isTerminal && !it.dead }) { "Created workspace has no ready terminal. Refresh before creating another." }
        return SshWorkspaceTarget.Cmux(SshCmuxSelection.capture(provider.session, tree, workspace, tab))
    }
    override fun close() {
        if (closed) return
        closed = true; job.cancel(); providers.values.toList().forEach { it.close() }; providers.clear()
        mutable.value = mutable.value.copy(loading = false)
    }
}

internal class SshCmuxHosts(private val connections: SshConnections<SshTransport>, lifetime: CoroutineScope,
    private val admitted: () -> Boolean, private val installer: SshCmuxInstaller? = null,
    private val drafts: SshComposerPool = SshComposerPool(),
    private val idlePolicy: (UUID) -> Long? = { 86400L }) : AutoCloseable {
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
    internal fun peek(id: UUID): SshCmuxHost? = hosts[id]?.takeIf { it.connection.isConnected }
    internal fun adopt(id: UUID, connection: SshTransport): SshCmuxHost {
        check(job.isActive && admitted() && connection.isConnected)
        hosts[id]?.takeIf { it.connection === connection }?.let { return it }
        hosts.remove(id)?.close()
        return SshCmuxHost(id, connection, scope, { job.isActive && admitted() }, installer, drafts, { idlePolicy(id) }).also { hosts[id] = it }
    }
    private fun closeAll() { hosts.values.toList().forEach { it.close() }; hosts.clear() }
    override fun close() { job.cancel(); scope.launch(NonCancellable) { closeAll() } }
}
