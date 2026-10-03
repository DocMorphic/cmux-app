package io.github.docmorphic.cmuxapp

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

internal class RoutedBrowserHostLease(private val release: () -> Unit, private val probe: (Boolean) -> Unit) : AutoCloseable {
    private var closed = false
    fun foreground(active: Boolean) { if (!closed) probe(active) }
    override fun close() { if (!closed) { closed = true; probe(false); release() } }
}

@Composable
internal fun RoutedLocalBrowserWorkspaceView(destination: LocalBrowserDestination, navigation: LocalBrowserNavigation,
    workspace: NativeWorkspace, network: () -> RoutedBrowserNetwork?, retainHost: () -> RoutedBrowserHostLease,
    onClose: () -> Unit, onRoute: (NativeWorkspaceRoute) -> Unit, browserModes: Boolean = false,
    onNewWorkspace: (() -> Unit)? = null, onNewTerminal: (() -> Unit)? = null, onNewBrowser: (() -> Unit)? = null,
    sshPicker: SshPickerPresentation? = null, onSshCommand: ((SshPickerCommand) -> Unit)? = null) {
    val creationEnabled = onNewWorkspace != null && onNewTerminal != null && onNewBrowser != null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var requestId by rememberSaveable(destination.surface.id) { mutableStateOf<String?>(null) }
    var routed by remember(destination.surface.id) { mutableStateOf<Boolean?>(null) }
    var failure by remember(destination.surface.id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val currentWorkspace by rememberUpdatedState(workspace)
    val currentSshPicker by rememberUpdatedState(sshPicker)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = result.data?.getStringExtra(RoutedBrowserProtocol.EXTRA) ?: requestId
        if (id != null && id == requestId) {
            val entry = RoutedBrowserSessions.find(id)
            val action = result.data?.getStringExtra("action")
            requestId = null
            val returnScope = routedBrowserReturnScope(destination, navigation.state.value.local, entry?.destination,
                entry?.network?.retired?.isCompleted == false, currentWorkspace.id)
            if (returnScope == RoutedBrowserReturnScope.LEAVE) navigation.leave(close = false)
            else if (returnScope == RoutedBrowserReturnScope.APPLY && action == "restart") attempt++
            else if (returnScope == RoutedBrowserReturnScope.APPLY) {
                val kind = result.data?.getStringExtra("kind")
                val paneId = result.data?.getStringExtra("pane")
                val panel = RoutedBrowserProtocol.panes(currentWorkspace).singleOrNull { it.kind == kind && it.id == paneId }
                val creation = when (action) {
                    "new_workspace" -> onNewWorkspace
                    "new_terminal" -> onNewTerminal
                    "new_browser" -> onNewBrowser
                    else -> null
                }
                val sshCommand = SshPickerCommand.decode(result.data?.getStringExtra("ssh_command"))
                when {
                    action == "ssh_command" && sshCommand != null && onSshCommand != null && currentSshPicker?.permits(sshCommand) == true -> onSshCommand(sshCommand)
                    creation != null && creationEnabled -> creation()
                    action == "stream" && browserModes && panel?.kind == "browser" &&
                        navigation.switchToStream(destination.key, currentWorkspace, panel.id) -> {
                        onRoute(NativeWorkspaceRoute(destination.key.computerId, workspace.id, browserId = panel.id))
                    }
                    action == "pane" && panel != null -> {
                        navigation.leave(close = true)
                        onRoute(NativeWorkspaceRoute(destination.key.computerId, workspace.id,
                            terminalId = panel.id.takeIf { panel.kind == "terminal" }, browserId = panel.id.takeIf { panel.kind == "browser" },
                            surfaceId = panel.id.takeIf { panel.kind == "surface" }))
                    }
                    action == "close" -> {
                        navigation.leave(close = true); onClose()
                        val terminal = destination.terminalId?.takeIf { id -> currentWorkspace.terminals.any { it.id == id } }
                            ?: currentWorkspace.terminals.firstOrNull()?.id
                        if (currentWorkspace.hasPanes) onRoute(NativeWorkspaceRoute(destination.key.computerId, workspace.id, terminalId = terminal))
                    }
                    else -> navigation.leave(close = false)
                }
            }
            scope.launch { RoutedBrowserSessions.abandon(context, id); RoutedBrowserSessions.consume(id) }
        }
    }
    SideEffect { RoutedBrowserSessions.refresh(requestId, workspace, creationEnabled, sshPicker) }
    LaunchedEffect(destination.surface.id, attempt) {
        if (requestId != null) { routed = true; return@LaunchedEffect }
        var lease: RoutedBrowserHostLease? = null
        var registered: String? = null
        try {
            val owner = checkNotNull(network()) { "This computer is no longer available" }
            if (!owner.requiresProxy()) { routed = false; return@LaunchedEffect }
            routed = true; failure = null
            lease = retainHost()
            val held = lease
            val entry = RoutedBrowserSessions.register(context, owner, destination, workspace, held::close, held::foreground, browserModes, creationEnabled, sshPicker)
            registered = entry.id; requestId = entry.id
            launcher.launch(Intent(context, RoutedBrowserActivity::class.java).putExtra(RoutedBrowserProtocol.EXTRA, entry.id))
        } catch (error: Exception) {
            withContext(NonCancellable) {
                if (registered != null) RoutedBrowserSessions.abandon(context, registered) else lease?.close()
            }
            currentCoroutineContext().ensureActive()
            requestId = null; failure = error.message ?: "Could not open the browser"
        }
    }
    if (routed == false) LocalBrowserWorkspaceView(destination, navigation, workspace, onClose, onRoute, onNewWorkspace, onNewTerminal, onNewBrowser, sshPicker, onSshCommand)
    else {
        fun back() {
            val id = requestId
            scope.launch {
                RoutedBrowserSessions.abandon(context, id)
                if (ownsBrowserDestination(destination, navigation.state.value.local)) navigation.leave(close = false)
            }
        }
        BackHandler { back() }
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (failure == null) CircularProgressIndicator()
            Text(failure ?: "Opening browser…", Modifier.padding(16.dp))
            if (failure != null) TextButton(onClick = { attempt++ }) { Text("Retry") }
            TextButton(onClick = { back() }) { Text("Back to workspaces") }
        }
    }
}
