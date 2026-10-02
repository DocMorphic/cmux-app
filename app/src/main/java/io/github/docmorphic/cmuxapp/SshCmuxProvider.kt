package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class SshCmuxState(val tree: SshCmuxTree? = null, val loading: Boolean = true,
    val ended: Boolean = false, val error: String? = null)

/** One existing owner on one account-owned SSH route. UI disappearance cannot
 * cancel a submitted mutation; account/transport retirement closes the control.
 * Never retries input or mutations after an uncertain reply. */
internal class SshCmuxProvider private constructor(val control: SshCmuxControl, lifetime: CoroutineScope,
    private val idlePolicy: () -> Long?, private val admitted: () -> Boolean) : AutoCloseable {
    val session = checkNotNull(control.server).session
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(lifetime.coroutineContext + job)
    private val mutable = MutableStateFlow(SshCmuxState())
    val state = mutable.asStateFlow()
    private val operations = Mutex()
    private var refreshJob: Job? = null
    private var refreshAgain = false
    private var subscribeAgain = false
    private var closed = false
    private var resourceScope: Pair<String, String>? = null
    private val browsers = mutableSetOf<SshCmuxBrowserStream>()
    private val terminals = mutableMapOf<String, SshCmuxTerminal>()
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { close() } }
        control.onEvent = { event ->
            when (event.optString("event")) {
                "tree-changed", "title-changed", "surface-exited", "empty" -> refresh()
                "overflow" -> { subscribeAgain = true; refresh() }
                "disconnected", "daemon-shutdown" -> close()
            }
        }
    }
    private fun guard() { check(!closed && job.isActive && admitted() && !control.closed) { "cmux-tui connection ended" } }
    private suspend fun read(): SshCmuxTree {
        guard()
        if (subscribeAgain) { subscribeAgain = false; control.request("subscribe"); guard() }
        val tree = control.listWorkspaces(); guard()
        val old = mutable.value.tree
        check(old?.registry == null || old.registry == tree.registry) { "cmux-tui registry changed" }
        mutable.value = mutable.value.copy(tree = tree, error = null)
        for ((id, terminal) in terminals.toMap()) {
            val row = terminal.selection.resolve(session, tree)?.second
            if (row == null || row.surface != terminal.tab.surface) { terminals.remove(id); terminal.close() }
            else terminal.update(row)
        }
        browsers.toList().forEach { it.validate(tree) }
        browsers.removeAll { it.closed }
        return tree
    }
    fun refresh() {
        if (closed) return
        if (refreshJob?.isActive == true) { refreshAgain = true; return }
        refreshJob = scope.launch {
            mutable.value = mutable.value.copy(loading = true)
            try { do { refreshAgain = false; operations.withLock { read() } } while (refreshAgain && !closed) }
            catch (failure: Exception) { if (failure !is CancellationException) mutable.value = mutable.value.copy(error = failure.message ?: "Could not list cmux-tui workspaces") }
            finally { mutable.value = mutable.value.copy(loading = false) }
        }
    }
    private suspend fun <T> mutate(action: suspend () -> T): T = scope.async {
        operations.withLock {
            guard()
            try { action() }
            finally { if (!closed) refresh() }
        }
    }.await()
    private fun envelope() = JSONObject().put("origin", "cmux-android").put("mutation_id", UUID.randomUUID().toString()).also {
        control.server?.generation?.let { generation -> it.put("expected_generation", generation) }
    }
    private fun current(tree: SshCmuxTree, expected: SshCmuxWorkspace): SshCmuxWorkspace {
        val key = checkNotNull(expected.key) { "A stable workspace key is required" }
        return checkNotNull(tree.workspaces.singleOrNull { it.key == key && (expected.resource == null || it.resource == expected.resource) }) {
            "This workspace ended or was replaced. Refresh before trying again."
        }
    }
    suspend fun createWorkspace(name: String? = null, argv: List<String>? = null): String = mutate {
        read()
        val key = UUID.randomUUID().toString()
        val params = envelope().put("key", key)
        name?.let { params.put("name", it) }
        control.request("create-workspace", params); guard()
        createTerminal(key, argv)
        read(); key
    }
    suspend fun newTerminal(workspace: SshCmuxWorkspace, argv: List<String>? = null): Unit = mutate {
        val current = current(read(), workspace)
        createTerminal(checkNotNull(current.key), argv); read(); Unit
    }
    private suspend fun createTerminal(key: String, argv: List<String>?) {
        val params = envelope().put("key", key).put("cols", 80).put("rows", 24)
            .put("terminal_id", UUID.randomUUID().toString().replace("-", ""))
        argv?.let { require(it.isNotEmpty()); params.put("argv", JSONArray(it)) }
        val created = control.request("create-terminal", params); guard()
        applyIdlePolicy(surface(created))
    }
    private fun surface(result: JSONObject): Int {
        val value = result.get("surface")
        check(value is Int || value is Long) { "Invalid created terminal identity" }
        return (value as Number).toLong().also { check(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
    }
    private suspend fun applyIdlePolicy(surface: Int) {
        try { control.idlePolicy(surface, idlePolicy()) }
        catch (failure: Exception) { currentCoroutineContext().ensureActive(); guard() }
    }
    suspend fun newScreen(workspace: SshCmuxWorkspace): SshCmuxSelection = createSurface(workspace, null, "new-screen")
    suspend fun newTab(workspace: SshCmuxWorkspace, pane: SshCmuxPane): SshCmuxSelection = createSurface(workspace, pane, "new-tab")
    suspend fun split(workspace: SshCmuxWorkspace, pane: SshCmuxPane, right: Boolean): SshCmuxSelection =
        createSurface(workspace, pane, "split", right)

    private suspend fun createSurface(workspace: SshCmuxWorkspace, pane: SshCmuxPane?, command: String,
        right: Boolean = true): SshCmuxSelection = mutate {
        val current = current(read(), workspace)
        val params = JSONObject().put("cols", 80).put("rows", 24)
        if (pane == null) params.put("workspace", current.id)
        else {
            check(workspace.screens.any { screen -> screen.panes.any { it == pane } }) { "Pane belongs to another workspace" }
            val target = checkNotNull(current.screens.flatMap { it.panes }.singleOrNull {
                it.id == pane.id && (pane.resource == null || it.resource == pane.resource)
            }) { "This pane moved or was replaced. Refresh before trying again." }
            check(!target.dead) { "This pane ended" }
            params.put("pane", target.id)
        }
        if (command == "split") params.put("dir", if (right) "right" else "down")
        // These legacy layout commands do not accept the durable workspace
        // mutation envelope. Resolve on the current owner's tree and never
        // replay a creation after a lost response.
        val created = surface(control.request(command, params)); guard()
        applyIdlePolicy(created)
        val tree = read(); val updated = current(tree, workspace)
        val tab = checkNotNull(updated.tabs.singleOrNull { it.surface == created && it.isTerminal }) {
            "The created terminal is no longer listed. Refresh before creating another."
        }
        SshCmuxSelection.capture(session, tree, updated, tab)
    }
    private suspend fun resolveScope(): Pair<String, String> {
        resourceScope?.let { return it }
        fun objects(array: JSONArray) = (0 until array.length()).map(array::getJSONObject)
        val machines = control.requestV2("machine.list", JSONObject()) as? JSONArray ?: error("Invalid cmux-tui machine list")
        require(machines.length() in 1..64)
        val matches = mutableListOf<Pair<String, String>>()
        for (machine in objects(machines)) {
            val machineID = machine.getString("id")
            val sessions = control.requestV2("session.list", JSONObject().put("machine", machineID)) as? JSONArray ?: error("Invalid cmux-tui session list")
            require(sessions.length() <= 1024)
            for (candidate in objects(sessions)) if (candidate.optString("name") == session) matches += machineID to candidate.getString("id")
        }
        guard()
        return matches.singleOrNull()?.also { resourceScope = it } ?: error("Could not resolve the exact cmux-tui session")
    }
    private fun members(workspace: SshCmuxWorkspace) = workspace.tabs.map {
        checkNotNull(if (it.isTerminal) it.terminal else it.content) { "This server did not expose a stable content identity" }
    }.toSet()
    suspend fun endWorkspace(workspace: SshCmuxWorkspace): Unit = mutate {
        val current = current(read(), workspace)
        val confirmed = members(workspace)
        check(members(current) == confirmed) { "Workspace contents changed. Review it before ending it." }
        val (machine, resourceSession) = resolveScope()
        for (terminal in current.tabs.filter { it.isTerminal }.map { checkNotNull(it.terminal) }.distinct()) {
            control.requestV2("terminal.close", JSONObject().put("machine", machine).put("session", resourceSession)
                .put("terminal", terminal), UUID.randomUUID().toString()); guard()
        }
        // Closing views alone leaves terminal processes running. Also refuse
        // to remove a new tab another client added while terminals were ending.
        val latest = read(); val remaining = current(latest, workspace)
        check(members(remaining).all { it in confirmed }) { "New workspace content appeared. Review it before ending it." }
        val params = envelope().put("key", checkNotNull(remaining.key))
        latest.revision?.let { params.put("expected_revision", it) }
        control.request("close-workspace", params); read(); Unit
    }
    suspend fun open(selection: SshCmuxSelection, id: String): SshCmuxTerminal {
      val opening = scope.async { operations.withLock {
        val row = checkNotNull(selection.resolve(session, read())) { "This terminal moved, ended or was replaced" }.second
        // Each view acquisition owns a distinct renderer/attachment. A late
        // release from the prior composition must not close a newer view of
        // the same terminal. Reuse will require explicit view lifetime tokens.
        terminals.remove(id)?.retire()
        check(terminals.size < 16) { "Close a terminal before opening another" }
        applyIdlePolicy(row.surface)
        SshCmuxTerminal.open(id, selection, row, control, scope, ::allowed).also { terminals[id] = it }
      } }
      return try { opening.await() }
      catch (failure: CancellationException) {
          // The provider owns the in-flight attach. If navigation disappeared
          // before it returned, retire that exact result after its fence, never
          // a newer acquisition stored under the same ID.
          scope.launch { runCatching { opening.await() }.getOrNull()?.let(::release) }
          throw failure
      }
    }
    fun browser(selection: SshCmuxBrowserSelection): SshCmuxBrowserStream {
        guard()
        browsers.removeAll { it.closed }
        check(browsers.size < 16) { "Close a browser before opening another" }
        return SshCmuxBrowserStream(selection, control, scope, resolve = {
            operations.withLock {
                checkNotNull(selection.resolve(session, read())) { "This browser moved, ended or was replaced" }.second
            }
        }, admitted = ::allowed).also { browsers.add(it) }
    }
    private fun allowed() = !closed && job.isActive && admitted() && !control.closed
    fun release(terminal: SshCmuxTerminal) {
        terminal.visible(false)
        scope.launch { operations.withLock {
            if (terminals[terminal.id] === terminal) { terminal.retire(); terminals.remove(terminal.id) }
        } }
    }
    override fun close() {
        if (closed) return
        browsers.toList().forEach { it.close() }; browsers.clear()
        closed = true; control.onEvent = null; job.cancel(); control.close()
        terminals.values.toList().forEach { it.close() }; terminals.clear()
        mutable.value = mutable.value.copy(loading = false, ended = true)
    }
    companion object {
        suspend fun open(control: SshCmuxControl, lifetime: CoroutineScope, idlePolicy: () -> Long? = { 86400L },
            admitted: () -> Boolean): SshCmuxProvider {
            val provider = SshCmuxProvider(control, lifetime, idlePolicy, admitted)
            try {
                provider.guard(); control.request("subscribe"); provider.operations.withLock { provider.read() }
                provider.mutable.value = provider.mutable.value.copy(loading = false)
                return provider
            } catch (failure: Exception) { provider.close(); throw failure }
        }
    }
}
