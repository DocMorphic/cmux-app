package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth

/** Uses the shared workspace picker and remote terminal surface/composer. */
@Composable internal fun NativeCloudTerminalPane(model: NativeCloudViewModel, route: CloudWorkspaceRoute,
    snapshot: CloudWorkspaceSnapshot?) {
    val terminal by route.host.selected.collectAsState()
    val row = snapshot?.rows?.singleOrNull { it.key == route.workspaceId }
    val shown = terminal
    if (row == null || shown == null) {
        NativeWorkspaceWaitingPane(row?.workspace?.title ?: "Cloud workspace unavailable", onBack = model::leaveWorkspace,
            connected = snapshot?.availability == NativeFeedAvailability.CONNECTED,
            connectionError = snapshot?.failure?.detail,
            onReconnect = { model.retryConnection(route.host.machineId) })
    } else key(shown) {
        val state by shown.state.collectAsState()
        SshShellScreen(shown, reconnectError = state.error, onReconnect = { model.retryConnection(route.host.machineId) },
            panePicker = {
                NativePanePicker(shown.title, row.workspace,
                    NativeWorkspacePane(terminal = row.workspace.terminals.singleOrNull { it.id == shown.id }),
                    Modifier.fillMaxWidth(), onTerminal = { model.openWorkspace(row, it.id, route.catalogOwner) },
                    onBrowser = {}, onSurface = {})
            }, onBack = model::leaveWorkspace)
    }
}
