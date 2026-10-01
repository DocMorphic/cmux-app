package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

@Composable
internal fun SshTmuxRoute(session: NativeSshSession, hostId: UUID, onBack: () -> Unit) {
    var host by remember(session, hostId) { mutableStateOf<SshTmuxHost?>(null) }
    var failure by remember(session, hostId) { mutableStateOf<String?>(null) }
    var connecting by remember(session, hostId) { mutableStateOf(false) }
    var recovery by remember(session, hostId) { mutableIntStateOf(0) }
    val mutex = remember(session, hostId) { Mutex() }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    suspend fun connect(explicit: Boolean) = mutex.withLock {
        connecting = true; failure = null
        try {
            val next = if (explicit) session.tmux.open(hostId) else session.tmux.autoOpen(hostId)
            if (next != null) {
                next.refresh()
                host = next; recovery++
            }
        } catch (error: Exception) {
            // A declined/retired shared dial can cancel its waiter without
            // cancelling this visible route. Actual view cancellation escapes.
            currentCoroutineContext().ensureActive()
            if (session.connections.statuses.value[hostId]?.phase == SshConnectionPhase.FAILED)
                failure = session.connections.statuses.value[hostId]?.error ?: error.message ?: "Could not connect"
            else if (session.connections.statuses.value[hostId]?.phase != SshConnectionPhase.IDLE && error !is CancellationException)
                failure = error.message ?: "Could not connect"
        } finally { connecting = false }
    }
    LaunchedEffect(session, hostId, lifecycle) {
        connect(true) // The user explicitly opened this computer.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            session.connections.statuses.collect { statuses ->
                if (host?.connection?.isConnected != true && statuses[hostId]?.phase != SshConnectionPhase.FAILED) connect(false)
            }
        }
    }
    val reconnect = { scope.launch { connect(true) }; Unit }
    val current = host
    if (current != null) SshTmuxScreen(current, connecting, failure, recovery, reconnect, onBack)
    else Column(Modifier.padding(20.dp)) {
        BackHandler(onBack = onBack)
        TextButton(onClick = onBack) { Text("Back") }
        if (connecting) CircularProgressIndicator()
        else { Text(failure ?: "SSH computer disconnected"); TextButton(onClick = reconnect) { Text("Try again") } }
    }
}

@Composable
internal fun SshTmuxScreen(host: SshTmuxHost, reconnecting: Boolean = false, reconnectError: String? = null,
    recovery: Int = 0, onReconnect: (() -> Unit)? = null, onBack: () -> Unit) {
    val state by host.state.collectAsState()
    val disconnected by host.connection.disconnected.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember(host.hostId) { mutableStateOf<SshTmuxTerminal?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var ending by remember(host) { mutableStateOf<SshTmuxWorkspace?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(host, recovery) {
        val old = selected ?: return@LaunchedEffect
        if (old.state.value.phase != SshShellPhase.ENDED) return@LaunchedEffect
        restoring = true; failure = null
        try {
            val inventory = host.state.first { !it.loading }
            check(inventory.error == null) { inventory.error.orEmpty() }
            val workspace = inventory.workspaces.firstOrNull { it.id == old.workspace.id }
                ?: error("This tmux workspace ended or was replaced. Go back to choose a workspace.")
            val pane = workspace.panes.firstOrNull { it.id == old.pane.id && it.window == old.pane.window }
                ?: error("This tmux pane moved or ended. Go back to choose a pane.")
            val replacement = host.open(workspace, pane)
            if (selected === old) selected = replacement
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (selected === old) failure = error.message ?: "Could not reopen tmux pane"
        } finally { restoring = false }
    }
    fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true; failure = null
        scope.launch {
            try { action() }
            catch (error: Exception) { if (error is CancellationException) throw error; failure = error.message ?: "tmux operation failed" }
            finally { busy = false }
        }
    }
    selected?.let { terminal ->
        SshShellScreen(terminal, reconnecting || restoring, reconnectError ?: failure, onReconnect) { selected = null; failure = null }
        return
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().testTag("ssh.tmux")) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("tmux Workspaces", Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = host::refresh, enabled = !disconnected && !state.loading && !busy) { Text("Refresh") }
        }
        if (state.loading || busy || reconnecting) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (disconnected) {
            Text("SSH connection ended.", Modifier.padding(16.dp))
            onReconnect?.let { TextButton(onClick = it, enabled = !reconnecting) { Text("Reconnect") } }
        }
        val enabled = !disconnected && !state.loading && !busy
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (reconnectError ?: failure ?: state.error)?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (!state.loading && !state.available && state.error == null) item { Text("tmux was not found on this computer. Install it there, then refresh to use persistent workspaces.") }
            if (state.available) {
                item { TextButton(onClick = { act { host.createWorkspace() } }, enabled = enabled, modifier = Modifier.testTag("ssh.tmux.create")) { Text("New tmux Workspace") } }
                if (state.workspaces.isEmpty()) item { Text("No tmux sessions yet. Workspaces you create here stay running when you leave.") }
            }
            items(state.workspaces, key = { it.id }) { workspace ->
                Card(Modifier.fillMaxWidth().testTag("ssh.tmux.workspace.${workspace.id}")) {
                    Column(Modifier.padding(14.dp)) {
                        Text(workspace.name, style = MaterialTheme.typography.titleMedium)
                        Row {
                            TextButton(onClick = { act { host.createWindow(workspace) } }, enabled = enabled) { Text("New Terminal") }
                            TextButton(onClick = { ending = workspace }, enabled = enabled, modifier = Modifier.testTag("ssh.tmux.end.${workspace.id}")) { Text("End Workspace") }
                        }
                        for ((_, panes) in workspace.panes.groupBy { it.window }) {
                            val first = panes.first()
                            Text("${first.windowIndex}: ${first.windowName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            for (pane in panes) {
                                TextButton(onClick = { act { selected = host.open(workspace, pane) } }, enabled = enabled,
                                    modifier = Modifier.testTag("ssh.tmux.pane.${workspace.id}.${pane.id}")) { Text(if (pane.count > 1) "Pane ${pane.index + 1}" else pane.windowName) }
                                Row {
                                    TextButton(onClick = { act { host.split(workspace, pane, true) } }, enabled = enabled) { Text("Split Right") }
                                    TextButton(onClick = { act { host.split(workspace, pane, false) } }, enabled = enabled) { Text("Split Down") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    ending?.let { workspace ->
        AlertDialog(onDismissRequest = { ending = null }, title = { Text("End ${workspace.name}?") },
            text = { Text("This ends the tmux session and its programs, including work opened from another device.") },
            confirmButton = { TextButton(onClick = { ending = null; act { host.endWorkspace(workspace) } }, enabled = !busy && !disconnected) { Text("End Workspace") } },
            dismissButton = { TextButton(onClick = { ending = null }) { Text("Cancel") } })
    }
}
