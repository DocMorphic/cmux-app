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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun SshTmuxRoute(session: NativeSshSession, hostId: UUID, onBack: () -> Unit) {
    var host by remember(session, hostId) { mutableStateOf<SshTmuxHost?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(session, hostId, attempt) {
        failure = null
        try { host = session.tmux.open(hostId) }
        catch (error: Exception) { if (error is CancellationException) throw error; failure = error.message ?: "Could not connect" }
    }
    val current = host
    if (current != null) SshTmuxScreen(current, onBack)
    else Column(Modifier.padding(20.dp)) {
        BackHandler(onBack = onBack)
        TextButton(onClick = onBack) { Text("Back") }
        failure?.let { Text(it); TextButton(onClick = { attempt++ }) { Text("Try again") } } ?: CircularProgressIndicator()
    }
}

@Composable
internal fun SshTmuxScreen(host: SshTmuxHost, onBack: () -> Unit) {
    val state by host.state.collectAsState()
    val disconnected by host.connection.disconnected.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember(host) { mutableStateOf<SshTmuxTerminal?>(null) }
    var ending by remember(host) { mutableStateOf<SshTmuxWorkspace?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true; failure = null
        scope.launch {
            try { action() }
            catch (error: Exception) { if (error is CancellationException) throw error; failure = error.message ?: "tmux operation failed" }
            finally { busy = false }
        }
    }
    selected?.let { terminal -> SshShellScreen(terminal) { selected = null }; return }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().testTag("ssh.tmux")) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("tmux Workspaces", Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = host::refresh, enabled = !disconnected && !state.loading && !busy) { Text("Refresh") }
        }
        if (state.loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (disconnected) Text("SSH connection ended. Go back to reconnect.", Modifier.padding(16.dp))
        val enabled = !disconnected && !state.loading && !busy
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (failure ?: state.error)?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
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
