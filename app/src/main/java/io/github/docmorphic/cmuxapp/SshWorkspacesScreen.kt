package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

@Composable
internal fun SshWorkspacesRoute(session: NativeSshSession, hostId: UUID, onBack: () -> Unit) {
    var tmux by remember(session, hostId) { mutableStateOf<SshTmuxHost?>(null) }
    var cmux by remember(session, hostId) { mutableStateOf<SshCmuxHost?>(null) }
    var connecting by remember(session, hostId) { mutableStateOf(false) }
    var failure by remember(session, hostId) { mutableStateOf<String?>(null) }
    var recovery by remember(session, hostId) { mutableIntStateOf(0) }
    var entered by rememberSaveable(hostId.toString()) { mutableStateOf(false) }
    val mutex = remember(session, hostId) { Mutex() }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    suspend fun connect(explicit: Boolean) = mutex.withLock {
        connecting = true; failure = null
        try {
            val connection = if (explicit) session.connections.open(hostId) else session.connections.autoConnect(hostId)
            if (connection != null) {
                // One dial/admission decision for all providers. Adopting an
                // automatic connection must not silently clear Disconnect.
                tmux = session.tmux.adopt(hostId, connection).also { it.refresh() }
                cmux = session.cmux.adopt(hostId, connection).also { it.refresh() }
                recovery++
            }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val status = session.connections.statuses.value[hostId]
            if (status?.phase == SshConnectionPhase.FAILED) failure = status.error ?: error.message
            else if (status?.phase != SshConnectionPhase.IDLE && error !is CancellationException) failure = error.message
        } finally { connecting = false }
    }
    LaunchedEffect(session, hostId, lifecycle) {
        val explicit = !entered; entered = true
        connect(explicit)
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            session.connections.statuses.collect { statuses ->
                if (tmux?.connection?.isConnected != true && statuses[hostId]?.phase != SshConnectionPhase.FAILED) connect(false)
            }
        }
    }
    val reconnect = { scope.launch { connect(true) }; Unit }
    val currentTmux = tmux; val currentCmux = cmux
    if (currentTmux != null && currentCmux != null)
        SshWorkspacesScreen(session, hostId, currentTmux, currentCmux, recovery, connecting, failure, reconnect, onBack)
    else Column(Modifier.padding(20.dp)) {
        BackHandler(onBack = onBack)
        TextButton(onClick = onBack) { Text("Back") }
        if (connecting) CircularProgressIndicator()
        else { Text(failure ?: "SSH computer disconnected"); TextButton(onClick = reconnect) { Text("Reconnect") } }
    }
}

private data class SshWorkspaceView(val reference: String, val terminal: SshTerminal, val owner: SshCmuxProvider? = null)
private data class SshWorkspaceEnd(val name: String, val action: suspend () -> Unit)

@Composable
internal fun SshWorkspacesScreen(session: NativeSshSession, hostId: UUID, tmux: SshTmuxHost, cmux: SshCmuxHost,
    recovery: Int, reconnecting: Boolean, reconnectError: String?, onReconnect: () -> Unit, onBack: () -> Unit) {
    val tmuxState by tmux.state.collectAsState()
    val cmuxState by cmux.state.collectAsState()
    val hosts by session.hosts.state.collectAsState()
    val shells by session.shells.state.collectAsState()
    val disconnected by tmux.connection.disconnected.collectAsState()
    val providers = cmuxState.providers.map { provider -> key(provider) { provider to provider.state.collectAsState().value } }
    val scope = rememberCoroutineScope()
    var selection by rememberSaveable(hostId.toString()) { mutableStateOf<String?>(null) }
    var opened by remember(session, hostId) { mutableStateOf<SshWorkspaceView?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var files by remember(session, hostId) { mutableStateOf(false) }
    var ending by remember(session, hostId) { mutableStateOf<SshWorkspaceEnd?>(null) }
    fun select(target: SshWorkspaceTarget) { selection = "$hostId\n${target.encode()}"; failure = null }
    fun leave() { selection = null; opened = null; failure = null }
    val view = opened
    DisposableEffect(view) { onDispose { (view?.terminal as? SshCmuxTerminal)?.let { view.owner?.release(it) } } }
    fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true; failure = null
        scope.launch {
            try { action() }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failure = if (error is CancellationException) "Connection changed. Check the workspace before retrying." else error.message ?: "Workspace operation failed"
            }
            finally { busy = false }
        }
    }
    LaunchedEffect(tmux, cmux, recovery, selection, retry, cmuxState.providers) {
        val expected = selection ?: return@LaunchedEffect
        val current = opened
        if (current?.reference == expected && current.terminal.state.value.phase != SshShellPhase.ENDED &&
            (current.owner == null || current.owner in cmuxState.providers)) return@LaunchedEffect
        restoring = true; failure = null
        try {
            check(expected.startsWith("$hostId\n")) { "This saved terminal belongs to another computer" }
            val target = checkNotNull(SshWorkspaceTarget.decode(expected.substringAfter('\n'))) { "Could not restore this saved terminal" }
            val next = when (target) {
                is SshWorkspaceTarget.Shell -> {
                    val shell = checkNotNull(shells.firstOrNull { it.hostId == hostId && it.id == target.id }) {
                        "This local shell ended. Go back to open a new shell."
                    }
                    SshWorkspaceView(expected, shell)
                }
                is SshWorkspaceTarget.Tmux -> {
                    val inventory = tmux.state.first { !it.loading }
                    check(inventory.error == null) { inventory.error.orEmpty() }
                    val workspace = checkNotNull(inventory.workspaces.singleOrNull { it.id == target.workspace }) { "This tmux workspace ended or was replaced" }
                    val pane = checkNotNull(workspace.panes.singleOrNull { it.id == target.pane && it.window == target.window }) { "This tmux pane moved or ended" }
                    SshWorkspaceView(expected, tmux.open(workspace, pane))
                }
                is SshWorkspaceTarget.Cmux -> {
                    cmux.state.first { !it.loading }
                    val provider = cmux.forSelection(target.selection)
                    val state = provider.state.first { !it.loading }
                    check(!state.ended && state.error == null) { state.error ?: "cmux-tui session disconnected" }
                    SshWorkspaceView(expected, provider.open(target.selection, "cmux-ssh-$expected"), provider)
                }
            }
            if (selection == expected) opened = next
            else (next.terminal as? SshCmuxTerminal)?.let { next.owner?.release(it) }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (selection == expected) failure = error.message ?: "Could not open terminal"
        } finally { restoring = false }
    }
    LaunchedEffect(tmux, cmux, recovery) { ending = null }
    if (selection != null) {
        val terminal = opened?.takeIf { it.reference == selection }?.terminal
        val reconnect: () -> Unit = {
            if (terminal is SshShell) act {
                val replacement = session.shells.reconnect(terminal.id)
                select(SshWorkspaceTarget.Shell(replacement.id))
            } else { onReconnect(); retry++ }
        }
        if (files && terminal != null) SshFilesSheet(session, hostId, terminal) { files = false }
        if (terminal != null) SshShellScreen(terminal, reconnecting || restoring || busy, reconnectError ?: failure, reconnect, onFiles = { files = true }, onBack = ::leave)
        else Column(Modifier.fillMaxSize().padding(16.dp)) {
            BackHandler(onBack = ::leave)
            TextButton(onClick = ::leave) { Text("Back") }
            if (restoring || reconnecting) CircularProgressIndicator()
            (reconnectError ?: failure)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!restoring && !reconnecting) TextButton(onClick = reconnect) { Text("Try again") }
        }
        return
    }
    BackHandler(onBack = onBack)
    val available = !disconnected && !reconnecting && !busy && cmuxState.operation == null
    Column(Modifier.fillMaxSize().testTag("ssh.workspaces")) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(hosts.host(hostId)?.name ?: "Workspaces", Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { tmux.refresh(); cmux.refresh() }, enabled = available) { Text("Refresh") }
        }
        if (tmuxState.loading || cmuxState.loading || busy || reconnecting || cmuxState.operation != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (disconnected) TextButton(onClick = onReconnect, enabled = !reconnecting) { Text("Reconnect") }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (reconnectError ?: failure)?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item(key = "cmux-create") {
                val supported = cmuxState.available || cmuxState.platform?.packageName != null || cmuxState.platform == null
                TextButton(onClick = { act { cmux.createWorkspace() } }, enabled = available && supported && !cmuxState.loading,
                    modifier = Modifier.testTag("ssh.cmux.create-owned")) { Text("New cmux Workspace") }
                cmuxState.operation?.let { Text(it) }
                if (!cmuxState.available && !cmuxState.loading) Text(
                    if (supported) "Installs cmux-tui on this computer when needed."
                    else "cmux-tui is unavailable for ${cmuxState.platform?.os} / ${cmuxState.platform?.arch}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for ((provider, state) in providers) {
                item(key = "cmux-header:${provider.session}") {
                    Text("cmux-tui · ${provider.session}", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { act { provider.createWorkspace() } }, enabled = available && !state.ended && !state.loading,
                        modifier = Modifier.testTag("ssh.cmux.create.${provider.session}")) { Text("New Workspace") }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                val tree = state.tree
                items(tree?.workspaces.orEmpty(), key = { "cmux:${provider.session}:${it.key ?: it.resource ?: it.id}" }) { workspace ->
                    Card(Modifier.fillMaxWidth().testTag("ssh.cmux.workspace.${workspace.key}")) {
                        Column(Modifier.padding(14.dp)) {
                            Text(workspace.name.ifBlank { "Workspace" }, style = MaterialTheme.typography.titleMedium)
                            Row {
                                TextButton(onClick = { act { select(SshWorkspaceTarget.Cmux(provider.newScreen(workspace))) } }, enabled = available && !state.ended && !state.loading,
                                    modifier = Modifier.testTag("ssh.cmux.new-terminal.${workspace.key}")) { Text("New Screen") }
                                TextButton(onClick = { ending = SshWorkspaceEnd(workspace.name) { provider.endWorkspace(workspace) } },
                                    enabled = available && !state.ended, modifier = Modifier.testTag("ssh.cmux.end.${workspace.key}")) { Text("End Workspace") }
                            }
                            if (workspace.tabs.isEmpty()) Text("No terminals in this workspace.")
                            for ((screenIndex, screen) in workspace.screens.withIndex()) {
                                Text(screen.name ?: "Screen ${screenIndex + 1}")
                                for ((paneIndex, pane) in screen.panes.withIndex()) {
                                    if (screen.panes.size > 1) Text(pane.name ?: "Pane ${paneIndex + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row {
                                        val editable = available && !state.ended && !state.loading && !pane.dead
                                        TextButton(onClick = { act { select(SshWorkspaceTarget.Cmux(provider.newTab(workspace, pane))) } }, enabled = editable,
                                            modifier = Modifier.testTag("ssh.cmux.new-tab.${workspace.key}.${pane.id}")) { Text("New Tab") }
                                        TextButton(onClick = { act { select(SshWorkspaceTarget.Cmux(provider.split(workspace, pane, true))) } }, enabled = editable,
                                            modifier = Modifier.testTag("ssh.cmux.split-right.${workspace.key}.${pane.id}")) { Text("Split Right") }
                                        TextButton(onClick = { act { select(SshWorkspaceTarget.Cmux(provider.split(workspace, pane, false))) } }, enabled = editable,
                                            modifier = Modifier.testTag("ssh.cmux.split-down.${workspace.key}.${pane.id}")) { Text("Split Down") }
                                    }
                                    for (tab in pane.tabs) {
                                        val title = tab.name?.takeIf { it.isNotBlank() } ?: tab.title.ifBlank { if (tab.isBrowser) "Browser" else "Terminal" }
                                        TextButton(onClick = { select(SshWorkspaceTarget.Cmux(SshCmuxSelection.capture(provider.session, checkNotNull(tree), workspace, tab))) },
                                            enabled = available && !state.ended && tab.isTerminal && !tab.dead,
                                            modifier = Modifier.testTag("ssh.cmux.terminal.${workspace.key}.${tab.surface}")) {
                                            Text(title + when { tab.dead -> " · Ended"; !tab.isTerminal -> " · Unavailable"; else -> "" })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            for ((index, error) in cmuxState.errors.withIndex()) item(key = "cmux-error:$index") { Text(error, color = MaterialTheme.colorScheme.error) }
            if (cmuxState.available && cmuxState.providers.isEmpty() && !cmuxState.loading) item { Text("No running cmux-tui sessions found.") }
            if (tmuxState.available) {
                item {
                    Text("tmux", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { act { tmux.createWorkspace() } }, enabled = available && !tmuxState.loading,
                        modifier = Modifier.testTag("ssh.tmux.create")) { Text("New tmux Workspace") }
                }
                items(tmuxState.workspaces, key = { "tmux:${it.id}" }) { workspace ->
                    Card(Modifier.fillMaxWidth().testTag("ssh.tmux.workspace.${workspace.id}")) {
                        Column(Modifier.padding(14.dp)) {
                            Text(workspace.name, style = MaterialTheme.typography.titleMedium)
                            Row {
                                TextButton(onClick = { act { tmux.createWindow(workspace) } }, enabled = available) { Text("New Terminal") }
                                TextButton(onClick = { ending = SshWorkspaceEnd(workspace.name) { tmux.endWorkspace(workspace) } }, enabled = available) { Text("End Workspace") }
                            }
                            for (pane in workspace.panes) {
                                TextButton(onClick = { select(SshWorkspaceTarget.Tmux(workspace.id, pane.window, pane.id)) }, enabled = available,
                                    modifier = Modifier.testTag("ssh.tmux.pane.${workspace.id}.${pane.id}")) { Text(pane.title) }
                                Row {
                                    TextButton(onClick = { act { tmux.split(workspace, pane, true) } }, enabled = available) { Text("Split Right") }
                                    TextButton(onClick = { act { tmux.split(workspace, pane, false) } }, enabled = available) { Text("Split Down") }
                                }
                            }
                        }
                    }
                }
            }
            tmuxState.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Text("Shells", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { act { select(SshWorkspaceTarget.Shell(session.shells.create(hostId).id)) } }, enabled = !busy,
                    modifier = Modifier.testTag("ssh.workspaces.new-shell")) { Text("New Shell") }
            }
            items(shells.filter { it.hostId == hostId }, key = { "shell:${it.id}" }) { shell ->
                val state by shell.state.collectAsState()
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { select(SshWorkspaceTarget.Shell(shell.id)) }, modifier = Modifier.weight(1f)) {
                        Text(shell.title + if (state.phase == SshShellPhase.ENDED) " · Ended" else "")
                    }
                    TextButton(onClick = { session.shells.remove(shell.id) }) { Text("Close") }
                }
            }
        }
    }
    ending?.let { confirmation ->
        AlertDialog(onDismissRequest = { ending = null }, title = { Text("End ${confirmation.name}?") },
            text = { Text("This ends the workspace and its programs, including work opened from another device.") },
            confirmButton = { TextButton(onClick = { ending = null; act(confirmation.action) }, enabled = available) { Text("End Workspace") } },
            dismissButton = { TextButton(onClick = { ending = null }) { Text("Cancel") } })
    }
}
