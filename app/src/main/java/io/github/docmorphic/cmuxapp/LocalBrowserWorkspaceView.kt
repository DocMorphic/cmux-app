package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun LocalBrowserCreationProgress(creating: Boolean, onCancel: () -> Unit) {
    if (creating) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("Opening browser…", Modifier.weight(1f).padding(horizontal = 12.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
internal fun LocalBrowserWorkspaceView(destination: LocalBrowserDestination, navigation: LocalBrowserNavigation,
    workspace: NativeWorkspace = destination.workspace, onClose: () -> Unit = {}, onRoute: (NativeWorkspaceRoute) -> Unit,
    onNewWorkspace: (() -> Unit)? = null, onNewTerminal: (() -> Unit)? = null, onNewBrowser: (() -> Unit)? = null,
    sshPicker: SshPickerPresentation? = null, onSshCommand: ((SshPickerCommand) -> Unit)? = null,
    browserState: NativeBrowserPickerState = NativeBrowserPickerState(), customizeWorkspace: RoutedWorkspaceCustomizationSave? = null) {
    val page by destination.surface.state.collectAsState()
    var customize by androidx.compose.runtime.saveable.rememberSaveable(destination.surface.id) { mutableStateOf(false) }
    if (customize && customizeWorkspace != null) NativeWorkspaceCustomizationSheet(workspace, { customize = false }, customizeWorkspace)
    fun open(terminal: String? = null, browser: String? = null, surface: String? = null) {
        navigation.leave(close = true)
        onRoute(NativeWorkspaceRoute(destination.key.computerId, workspace.id, terminalId = terminal,
            browserId = browser, surfaceId = surface))
    }
    BackHandler { navigation.leave(close = false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            NativeWorkspaceBackControl { TextButton(onClick = { navigation.leave(close = false) },
                modifier = Modifier.semantics { contentDescription = "Back to workspaces" }) { Text("‹  Workspaces") } }
            val rows = nativePanePickerRows(workspace, browserState)
            val selected = rows.singleOrNull { it.kind == "browser" && it.id == destination.surface.linkedStreamPanelId }
            if (sshPicker != null) Box(Modifier.weight(1f)) {
                SshBrowserPanePicker(page.title ?: "Browser", sshPicker, destination.surface.linkedStreamPanelId,
                    onSelect = { row -> open(terminal = row.id.takeIf { row.kind == "terminal" }, browser = row.id.takeIf { row.kind == "browser" }) },
                    onCommand = { if (sshPicker.permits(it)) onSshCommand?.invoke(it) })
            } else NativePanePicker(page.title ?: "Browser", rows, selected, Modifier.weight(1f), onSelect = { row ->
                open(terminal = row.id.takeIf { row.kind == "terminal" }, browser = row.id.takeIf { row.kind == "browser" },
                    surface = row.id.takeIf { row.kind == "surface" })
            }, onNewWorkspace, onNewTerminal, if (selected == null) ({}) else onNewBrowser, checksNewBrowser = selected == null, browserState = browserState, utilities = { close ->
                if (customizeWorkspace != null) DropdownMenuItem(text = { Text("Customize Workspace") }, onClick = { close(); customize = true })
            })
        }
        LocalBrowserPane(destination.surface) {
            onClose()
            if (!workspace.hasPanes) navigation.leave(close = true)
            else open(terminal = destination.terminalId?.takeIf { id -> workspace.terminals.any { it.id == id } }
                ?: workspace.terminals.firstOrNull()?.id)
        }
    }
}
