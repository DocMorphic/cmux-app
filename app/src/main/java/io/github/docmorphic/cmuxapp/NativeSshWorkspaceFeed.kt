package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import java.util.UUID

internal data class SshFeedRow(
    val host: SshHostRecord, val key: String, val title: String, val kind: SshWorkspaceKind,
    val targets: List<SshWorkspaceTarget>, val cmuxSession: String? = null,
    val cmuxWorkspace: SshCmuxWorkspace? = null, val tmuxWorkspace: SshTmuxWorkspace? = null,
    val generation: String? = null, val registry: String? = null,
) {
    val preview get() = when (kind) {
        SshWorkspaceKind.CMUX_TUI -> "cmux-tui · $cmuxSession"
        SshWorkspaceKind.TMUX -> "tmux"
        SshWorkspaceKind.SHELL -> "Shell"
    }
    val workspace get() = NativeWorkspace(key, title, targets.mapNotNull {
        if (it is SshWorkspaceTarget.Browser) null else NativeTerminal(it.encode(), title)
    }, null, false, null, null, false, targets.filterIsInstance<SshWorkspaceTarget.Browser>().map {
        NativeBrowser(it.encode(), "Browser")
    }, null, preview, null)
    val confirmation get() = when (kind) {
        SshWorkspaceKind.CMUX_TUI -> WorkspaceCloseConfirmation.ssh(PersistentSshWorkspaceKind.CMUX_TUI, title, host.name)
        SshWorkspaceKind.TMUX -> WorkspaceCloseConfirmation.ssh(PersistentSshWorkspaceKind.TMUX, title, host.name)
        SshWorkspaceKind.SHELL -> WorkspaceCloseConfirmation("Close “$title”?", "This ends the shell on ${host.name}.", "Close Shell")
    }
    fun sameOwner(other: SshFeedRow) = key == other.key && host.connectsLike(other.host) &&
        generation == other.generation && registry == other.registry
}

internal fun sshFeedKey(host: UUID, kind: SshWorkspaceKind, vararg parts: Any?): String =
    "ssh-workspace:" + JSONArray(listOf(host.toString(), kind.name) + parts).toString()

internal fun sshCmuxFeedRows(host: SshHostRecord, session: String, tree: SshCmuxTree): List<SshFeedRow> =
    tree.workspaces.map { workspace ->
        val tabs = workspace.tabs.filter { !it.dead && (it.isTerminal || it.isBrowser) }
        val targets = tabs.map { tab -> if (tab.isBrowser)
            SshWorkspaceTarget.Browser(SshCmuxBrowserSelection.capture(session, tree, workspace, tab))
        else SshWorkspaceTarget.Cmux(SshCmuxSelection.capture(session, tree, workspace, tab)) }
        SshFeedRow(host, sshFeedKey(host.id, SshWorkspaceKind.CMUX_TUI, session, workspace.key ?: workspace.resource ?: workspace.id),
            workspace.name.ifBlank { "Workspace" }, SshWorkspaceKind.CMUX_TUI, targets,
            session, workspace, generation = tree.generation, registry = tree.registry)
    }

internal fun sshTmuxFeedRows(host: SshHostRecord, workspaces: List<SshTmuxWorkspace>) = workspaces.map { workspace ->
    SshFeedRow(host, sshFeedKey(host.id, SshWorkspaceKind.TMUX, workspace.id), workspace.name, SshWorkspaceKind.TMUX,
        workspace.panes.map { SshWorkspaceTarget.Tmux(workspace.id, it.window, it.id) }, tmuxWorkspace = workspace)
}

internal data class SshFeedSnapshot(val host: SshHostRecord, val rows: List<SshFeedRow> = emptyList(),
    val loading: Boolean = false, val error: String? = null)

internal fun mergeSshFeedSnapshot(previous: SshFeedSnapshot?, incoming: SshFeedSnapshot, actionError: String?): SshFeedSnapshot =
    incoming.copy(rows = if (incoming.loading && previous?.host?.connectsLike(incoming.host) == true)
        previous.rows.map { it.copy(host = incoming.host) } else incoming.rows,
        error = actionError ?: incoming.error)

/** Inventory belongs to the login. A hidden list never cancels a handshake or remote mutation. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class NativeSshWorkspaceFeed(private val session: NativeSshSession, lifetime: CoroutineScope) : AutoCloseable {
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(lifetime.coroutineContext + job + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow<Map<UUID, SshFeedSnapshot>>(emptyMap())
    val state = mutable.asStateFlow()
    private data class Binding(val host: SshHostRecord, val tmux: SshTmuxHost, val cmux: SshCmuxHost, val observer: Job)
    private val bindings = mutableMapOf<UUID, Binding>()
    private val opening = mutableMapOf<UUID, Job>()
    private val closing = mutableSetOf<String>()
    private val actionErrors = mutableMapOf<UUID, String>()
    private fun current(host: SshHostRecord) = job.isActive && session.isOpen &&
        session.hosts.state.value.host(host.id)?.connectsLike(host) == true
    private fun update(host: SshHostRecord, change: (SshFeedSnapshot) -> SshFeedSnapshot) {
        if (current(host)) mutable.value = mutable.value + (host.id to change(mutable.value[host.id] ?: SshFeedSnapshot(host)))
    }
    init {
        scope.launch {
            session.hosts.state.collect { saved ->
                bindings.values.toList().filter { saved.host(it.host.id)?.connectsLike(it.host) != true }.forEach {
                    bindings.remove(it.host.id)?.observer?.cancel(); opening.remove(it.host.id)?.cancel(); actionErrors.remove(it.host.id)
                }
                mutable.value = mutable.value.mapNotNull { (id, snapshot) ->
                    saved.host(id)?.takeIf { it.connectsLike(snapshot.host) }?.let { host ->
                        id to snapshot.copy(host = host, rows = snapshot.rows.map { it.copy(host = host) })
                    }
                }.toMap()
            }
        }
        scope.launch {
            session.connections.statuses.collect { statuses ->
                statuses.filterValues { it.phase != SshConnectionPhase.CONNECTED }.keys.forEach { id ->
                    mutable.value[id]?.let { snapshot -> update(snapshot.host) { it.copy(loading = false) } }
                }
                statuses.filterValues { it.phase == SshConnectionPhase.CONNECTED }.keys.forEach { id ->
                    session.hosts.state.value.host(id)?.let { open(it, explicit = false, refresh = false) }
                }
            }
        }
    }
    fun open(host: SshHostRecord, explicit: Boolean, refresh: Boolean = true) {
        if (!current(host) || opening[host.id]?.isActive == true) return
        if (explicit || refresh) { actionErrors.remove(host.id); update(host) { it.copy(error = null) } }
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val connection = if (explicit) session.connections.open(host.id) else session.connections.autoConnect(host.id)
                if (!current(host) || connection == null) return@launch
                val tmux = session.tmux.adopt(host.id, connection)
                val cmux = session.cmux.adopt(host.id, connection)
                if (bindings[host.id]?.tmux !== tmux || bindings[host.id]?.cmux !== cmux) bind(host, tmux, cmux)
                if (refresh) { tmux.refresh(); cmux.refresh() }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                // A declined trust question stays quiet; the connection manager retains its pause.
                if (session.connections.statuses.value[host.id]?.phase == SshConnectionPhase.FAILED)
                    update(host) { it.copy(loading = false, error = failure.message ?: "Could not list SSH workspaces") }
            } finally {
                if (opening[host.id] === currentCoroutineContext()[Job]) opening.remove(host.id)
            }
        }
        opening[host.id] = task; task.start()
    }
    fun refreshConnected() {
        session.hosts.state.value.hosts.filter { session.connections.statuses.value[it.id]?.phase == SshConnectionPhase.CONNECTED }
            .forEach { open(it, explicit = false) }
    }
    private fun bind(host: SshHostRecord, tmux: SshTmuxHost, cmux: SshCmuxHost) {
        bindings.remove(host.id)?.observer?.cancel()
        val observer = scope.launch(start = CoroutineStart.LAZY) {
            val cmuxRows = cmux.state.flatMapLatest { state ->
                if (state.providers.isEmpty()) flowOf(emptyList())
                else combine(state.providers.map { provider -> provider.state.map { p ->
                    p.tree?.let { sshCmuxFeedRows(host, provider.session, it) }.orEmpty()
                } }) { it.toList().flatten() }
            }
            val shellRows = session.shells.state.map { it.filter { shell -> shell.hostId == host.id } }.flatMapLatest { shells ->
                if (shells.isEmpty()) flowOf(emptyList()) else combine(shells.map { shell ->
                    shell.state.map { it.phase }.distinctUntilChanged().map { phase ->
                        if (phase == SshShellPhase.ENDED) null else SshFeedRow(host,
                            sshFeedKey(host.id, SshWorkspaceKind.SHELL, shell.id), shell.title, SshWorkspaceKind.SHELL,
                            listOf(SshWorkspaceTarget.Shell(shell.id)))
                    }
                }) { it.filterNotNull() }
            }
            combine(tmux.state, cmux.state, cmuxRows, shellRows) { t, c, cmuxList, shells ->
                SshFeedSnapshot(host, cmuxList + sshTmuxFeedRows(host, t.workspaces) + shells,
                    t.loading || c.loading, (c.errors + listOfNotNull(t.error)).joinToString("\n").ifBlank { null })
            }.collect { snapshot ->
                if (current(host) && tmux.connection.isConnected) {
                    val liveHost = checkNotNull(session.hosts.state.value.host(host.id))
                    mutable.value = mutable.value + (host.id to mergeSshFeedSnapshot(mutable.value[host.id],
                        snapshot.copy(host = liveHost, rows = snapshot.rows.map { it.copy(host = liveHost) }), actionErrors[host.id]))
                }
            }
        }
        bindings[host.id] = Binding(host, tmux, cmux, observer); observer.start()
    }
    fun isCurrent(row: SshFeedRow) = current(row.host) && state.value[row.host.id]?.rows?.any { it.sameOwner(row) } == true
    fun canClose(row: SshFeedRow) = isCurrent(row) && row.key !in closing && bindings[row.host.id]?.tmux?.connection?.isConnected == true
    fun closeWorkspace(row: SshFeedRow) {
        if (!canClose(row) || !closing.add(row.key)) return
        val binding = checkNotNull(bindings[row.host.id])
        actionErrors.remove(row.host.id); update(row.host) { it.copy(error = null) }
        scope.launch {
            try {
                check(isCurrent(row) && bindings[row.host.id] === binding) { "SSH workspace changed" }
                when (row.kind) {
                    SshWorkspaceKind.CMUX_TUI -> {
                        val provider = binding.cmux.state.value.providers.single { it.session == row.cmuxSession }
                        val tree = checkNotNull(provider.state.value.tree)
                        check(tree.generation == row.generation && tree.registry == row.registry) { "SSH workspace changed" }
                        provider.endWorkspace(checkNotNull(row.cmuxWorkspace))
                    }
                    SshWorkspaceKind.TMUX -> binding.tmux.endWorkspace(checkNotNull(row.tmuxWorkspace))
                    SshWorkspaceKind.SHELL -> session.shells.remove((row.targets.single() as SshWorkspaceTarget.Shell).id)
                }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (current(row.host)) {
                    val message = failure.message ?: "Close was not confirmed. Refresh before trying again."
                    actionErrors[row.host.id] = message
                    update(row.host) { it.copy(error = message) }
                }
            } finally { closing.remove(row.key) }
        }
    }
    override fun close() { job.cancel(); bindings.clear(); opening.clear(); closing.clear(); actionErrors.clear(); mutable.value = emptyMap() }
}
