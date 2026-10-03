package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID

internal data class SshTmuxPaneRow(val id: Int, val window: Int, val windowIndex: Int, val windowName: String,
    val index: Int, val columns: Int, val rows: Int, val count: Int) {
    val title get() = "$windowIndex:$windowName" + if (count > 1) " · Pane ${index + 1}" else ""
}
internal data class SshTmuxWorkspace(val server: Int, val session: Int, val created: Long, val name: String, val panes: List<SshTmuxPaneRow>) {
    val id get() = "$server:$session:$created"
    val target get() = "$$session"
}
internal object SshTmuxInventory {
    const val FORMAT = "#{pid}:#{session_id}:#{session_created}:#{window_id}:#{window_index}:#{pane_id}:#{pane_index}:#{pane_width}:#{pane_height}:#{window_panes}:#{session_name}:#{window_name}"
    fun parse(output: String): List<SshTmuxWorkspace> {
        val rows = linkedMapOf<String, SshTmuxWorkspace>()
        for (line in output.lineSequence().filter { it.isNotEmpty() }) {
            val f = line.split(':', limit = 12); require(f.size == 12) { "Invalid tmux inventory" }
            fun n(i: Int) = f[i].toInt().also { require(it >= 0) }
            val server = n(0); val session = requireNotNull(SshTmuxParser.id(f[1], '$')); val created = f[2].toLong().also { require(it >= 0) }
            val window = requireNotNull(SshTmuxParser.id(f[3], '@')); val pane = requireNotNull(SshTmuxParser.id(f[5], '%'))
            val columns = n(7); val height = n(8); require(columns in 1..1000 && height in 1..1000)
            val item = SshTmuxWorkspace(server, session, created, f[10], emptyList())
            if (item.name.contains(SshTmuxEncoding.GROUP_MARKER) || item.name.contains("-cmux-ios-")) continue
            val old = rows[item.id] ?: item
            require(old.name == item.name && old.panes.none { it.id == pane })
            rows[item.id] = old.copy(panes = old.panes + SshTmuxPaneRow(pane, window, n(4), f[11], n(6), columns, height, n(9)))
        }
        return rows.values.map { it.copy(panes = it.panes.sortedWith(compareBy({ p -> p.windowIndex }, { p -> p.index }))) }
    }
    fun command(path: String, vararg args: String) = (listOf(path) + args).joinToString(" ", transform = SshTmuxEncoding::shellQuote)
    fun supportsShellEnvironment(version: String): Boolean {
        val parts = Regex("(\\d+)\\.(\\d+)").find(version)?.groupValues ?: return false
        val major = parts[1].toIntOrNull() ?: return false
        val minor = parts[2].toIntOrNull() ?: return false
        return major > 3 || major == 3 && minor >= 2
    }
    /** tmux evaluates this guard in its own command queue, before mutation.
     * A replacement server/session cannot satisfy an old confirmation token. */
    fun paneTarget(workspace: SshTmuxWorkspace, pane: SshTmuxPaneRow) = "${workspace.target}:@${pane.window}.%${pane.id}"
    fun guardedArguments(workspace: SshTmuxWorkspace, action: String, target: String = workspace.target): List<String> {
        val condition = "#{&&:#{==:#{pid},${workspace.server}},#{==:#{session_created},${workspace.created}}}"
        return listOf("if-shell", "-F", "-t", target, condition, action, "display-message -p CMUX_STALE_TARGET")
    }
    fun guarded(path: String, workspace: SshTmuxWorkspace, action: String, target: String = workspace.target) =
        command(path, *guardedArguments(workspace, action, target).toTypedArray())
    private val groupName = Regex("cmux-[0-9a-f]{8}-cmux-android-[0-9a-f]{32}")
    fun staleGroups(output: String) = output.lineSequence().filter { it.startsWith("0:") }
        .map { it.removePrefix("0:") }.filter { groupName.matches(it) }.distinct().toList()
    fun collectGroupArguments(name: String): List<String> {
        require(groupName.matches(name))
        val target = "=$name"
        // if-shell resolves a target-pane; the trailing colon selects the
        // named session's current window instead of treating it as a pane.
        return listOf("if-shell", "-F", "-t", "$target:", "#{==:#{session_attached},0}",
            "kill-session -t ${SshTmuxEncoding.quote(target)}", "display-message -p CMUX_GROUP_IN_USE")
    }
}

internal data class SshTmuxHostState(val loading: Boolean = true, val available: Boolean = false,
    val workspaces: List<SshTmuxWorkspace> = emptyList(), val error: String? = null)

/** Main-owned provider for one live SSH transport. It never auto-installs tmux,
 * and view disposal does not cancel shared discovery/control clients. */
internal class SshTmuxHost(val hostId: UUID, val connection: SshTransport, lifetime: CoroutineScope,
    private val admitted: () -> Boolean,
) : AutoCloseable {
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(lifetime.coroutineContext + job + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(SshTmuxHostState())
    val state = mutable.asStateFlow()
    private val operations = Mutex()
    private var tmux: String? = null
    private var environment = emptyList<String>()
    private var collected = false
    private val controls = mutableMapOf<String, SshTmuxControl>()
    private val opening = mutableMapOf<String, Deferred<SshTmuxControl>>()
    private val terminals = mutableMapOf<String, SshTmuxTerminal>()
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
            mutable.value = mutable.value.copy(loading = true, error = null)
            try {
                do { refreshAgain = false; operations.withLock { discover() } } while (refreshAgain && !closed)
            }
            catch (failure: Exception) { if (failure !is CancellationException) mutable.value = mutable.value.copy(error = failure.message ?: "Could not list tmux sessions") }
            finally { mutable.value = mutable.value.copy(loading = false) }
        }
    }
    private suspend fun discover() {
        guard()
        if (tmux == null) {
            val script = "for p in \"\$(command -v tmux 2>/dev/null)\" /opt/homebrew/bin/tmux /usr/local/bin/tmux /usr/bin/tmux; do [ -n \"\$p\" ] && [ -x \"\$p\" ] && { echo \"\$p\"; exit 0; }; done; exit 1"
            val result = connection.exec("sh -c " + SshTmuxEncoding.shellQuote(script))
            guard()
            if (result.exitStatus != 0) { mutable.value = mutable.value.copy(available = false, workspaces = emptyList()); return }
            tmux = result.stdout.toString(Charsets.UTF_8).trim().also { require(it.startsWith('/') && it.none { c -> c == '\n' || c == '\r' || c == '\u0000' }) { "Invalid tmux executable path" } }
            val version = connection.exec(SshTmuxInventory.command(checkNotNull(tmux), "-V"))
            guard()
            environment = if (version.exitStatus == 0 && SshTmuxInventory.supportsShellEnvironment(version.stdout.toString(Charsets.UTF_8))) listOf("-e", "COLORTERM=truecolor") else emptyList()
        }
        val path = checkNotNull(tmux)
        if (!collected) {
            val result = connection.exec(SshTmuxInventory.command(path, "list-sessions", "-F", "#{session_attached}:#{session_name}"))
            guard()
            if (result.exitStatus == 0) {
                for (name in SshTmuxInventory.staleGroups(result.stdout.toString(Charsets.UTF_8))) {
                    connection.exec(SshTmuxInventory.command(path, *SshTmuxInventory.collectGroupArguments(name).toTypedArray())); guard()
                }
            }
            collected = true
        }
        val result = connection.exec(SshTmuxInventory.command(path, "list-panes", "-a", "-F", SshTmuxInventory.FORMAT))
        guard()
        val workspaces = if (result.exitStatus == 0) SshTmuxInventory.parse(result.stdout.toString(Charsets.UTF_8)) else {
            val error = result.stderr.toString(Charsets.UTF_8)
            check(error.contains("no server running") || error.contains("No such file or directory")) { "tmux session listing failed" }
            emptyList()
        }
        mutable.value = mutable.value.copy(available = true, workspaces = workspaces, error = null)
        val live = workspaces.map { it.id }.toSet()
        for (id in controls.keys.toList().filter { it !in live }) controls.remove(id)?.close()
        for ((id, terminal) in terminals.toMap()) {
            val row = workspaces.firstOrNull { it.id == terminal.workspace.id }?.panes?.firstOrNull { it.id == terminal.pane.id }
            if (row == null || row.window != terminal.pane.window) { terminals.remove(id); terminal.close() } else terminal.updatePane(row)
        }
    }
    private fun current(workspace: SshTmuxWorkspace) {
        guard(); check(mutable.value.workspaces.any { it.id == workspace.id }) { "This tmux session changed. Refresh before trying again." }
    }
    private suspend fun run(command: String): String {
        guard(); val result = connection.exec(command); guard()
        check(result.exitStatus == 0) { "tmux operation failed. Refresh to check its outcome before retrying." }
        val text = result.stdout.toString(Charsets.UTF_8).trim()
        check(text != "CMUX_STALE_TARGET") { "This tmux session was replaced. Refresh before trying again." }
        return text
    }
    /** Mutations run under the provider owner even when their screen disappears. */
    private suspend fun <T> mutate(action: suspend () -> T): T = scope.async {
        operations.withLock {
            try { action() } finally { if (!closed) refresh() }
        }
    }.await()
    suspend fun createWorkspace(): SshWorkspaceTarget.Tmux = mutate {
        discover(); val path = checkNotNull(tmux) { "tmux is not installed on this computer" }
        val names = state.value.workspaces.map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { "cmux-$it" }.first { it !in names }
        val created = run(SshTmuxInventory.command(path, *(listOf("new-session", "-d", "-P", "-F", "#{pane_id}") + environment + listOf("-s", name)).toTypedArray()))
        createdPane(created)
    }
    suspend fun createWindow(workspace: SshTmuxWorkspace): SshWorkspaceTarget.Tmux = mutate {
        current(workspace)
        val action = "new-window -d -P -F '#{pane_id}' ${environment.joinToString(" ")} -t ${SshTmuxEncoding.quote(workspace.target + ":")}"
        createdPane(run(SshTmuxInventory.guarded(checkNotNull(tmux), workspace, action)), workspace.id)
    }
    /** Section actions split that window's active pane without moving a desktop client's selection. */
    suspend fun splitWindow(workspace: SshTmuxWorkspace, window: Int, right: Boolean): SshWorkspaceTarget.Tmux = mutate {
        current(workspace)
        check(state.value.workspaces.first { it.id == workspace.id }.panes.any { it.window == window }) { "This tmux window ended" }
        val target = "${workspace.target}:@$window"
        val action = "split-window -d -P -F '#{pane_id}' ${environment.joinToString(" ")} ${if (right) "-h" else "-v"} -t ${SshTmuxEncoding.quote(target)}"
        createdPane(run(SshTmuxInventory.guarded(checkNotNull(tmux), workspace, action, target)), workspace.id)
    }
    private suspend fun createdPane(output: String, workspaceId: String? = null): SshWorkspaceTarget.Tmux {
        val pane = checkNotNull(SshTmuxParser.id(output, '%')) { "Could not confirm the created tmux pane. Refresh before creating another." }
        discover()
        val matches = state.value.workspaces.filter { workspaceId == null || it.id == workspaceId }.flatMap { workspace ->
            workspace.panes.filter { it.id == pane }.map { SshWorkspaceTarget.Tmux(workspace.id, it.window, it.id) }
        }
        return checkNotNull(matches.singleOrNull()) { "The created tmux pane is no longer listed. Refresh before creating another." }
    }
    suspend fun split(workspace: SshTmuxWorkspace, pane: SshTmuxPaneRow, right: Boolean): Unit = mutate {
        current(workspace); check(state.value.workspaces.first { it.id == workspace.id }.panes.any { it.id == pane.id })
        val target = SshTmuxInventory.paneTarget(workspace, pane)
        val action = "split-window -d ${environment.joinToString(" ")} ${if (right) "-h" else "-v"} -t ${SshTmuxEncoding.quote(target)}"
        run(SshTmuxInventory.guarded(checkNotNull(tmux), workspace, action, target)); discover()
    }
    suspend fun endWorkspace(workspace: SshTmuxWorkspace): Unit = mutate {
        current(workspace)
        // A linked phone session keeps the shared windows alive. Remove it
        // before ending the original, or its programs would survive the action.
        controls.remove(workspace.id)?.let { client ->
            collected = false
            client.detachSession()
        }
        run(SshTmuxInventory.guarded(checkNotNull(tmux), workspace, "kill-session -t ${SshTmuxEncoding.quote(workspace.target)}"))
        discover()
    }
    private suspend fun control(workspace: SshTmuxWorkspace): SshTmuxControl {
        current(workspace)
        controls[workspace.id]?.takeUnless { it.isClosed }?.let { return it }
        opening[workspace.id]?.let { return it.await() }
        val task = scope.async(start = CoroutineStart.LAZY) {
            val grouped = "cmux-${hostId.toString().take(8)}${SshTmuxEncoding.GROUP_MARKER}${UUID.randomUUID().toString().replace("-", "")}"
            val command = SshTmuxInventory.command(checkNotNull(tmux), "-C", "new-session", "-t", workspace.target, "-s", grouped) +
                " \\; set-option -t ${SshTmuxEncoding.shellQuote("=$grouped:")} destroy-unattached off"
            val client = SshTmuxControl(grouped, connection.openTmux(command), scope)
            try {
                client.initialize(); current(workspace)
                val server = client.command("display-message -p '#{pid}'").singleOrNull()?.toString(Charsets.UTF_8)?.toIntOrNull()
                check(server == workspace.server) { "tmux server restarted during attachment" }
                client.onTopologyChange = ::refresh
                client.onClose = { if (controls[workspace.id] === client) { controls.remove(workspace.id); collected = false; refresh() } }
                controls[workspace.id] = client; client
            } catch (failure: Exception) { collected = false; client.close(); throw failure }
            finally { opening.remove(workspace.id) }
        }
        opening[workspace.id] = task; task.start(); return task.await()
    }
    suspend fun open(workspace: SshTmuxWorkspace, pane: SshTmuxPaneRow): SshTmuxTerminal = scope.async {
      operations.withLock {
        current(workspace)
        check(state.value.workspaces.first { it.id == workspace.id }.panes.any { it.id == pane.id && it.window == pane.window }) {
            "This tmux pane moved or ended. Refresh before trying again."
        }
        val id = "cmux-ssh-$hostId:tmux:${workspace.id}/%${pane.id}"
        terminals[id]?.takeUnless { it.state.value.phase == SshShellPhase.ENDED || it.pane.window != pane.window }?.let { return@withLock it }
        val client = control(workspace); current(workspace)
        // Another view may have finished the shared control open first.
        terminals[id]?.takeUnless { it.state.value.phase == SshShellPhase.ENDED || it.pane.window != pane.window }?.let { return@withLock it }
        terminals.remove(id)?.close()
        SshTmuxTerminal(id, workspace, pane, client, scope, { !closed && job.isActive && admitted() && connection.isConnected }).also { terminals[id] = it }
      }
    }.await()
    override fun close() {
        if (closed) return
        closed = true; job.cancel(); controls.values.toList().forEach { it.close() }; controls.clear()
        terminals.values.toList().forEach { it.close() }; terminals.clear(); opening.clear()
    }
}
