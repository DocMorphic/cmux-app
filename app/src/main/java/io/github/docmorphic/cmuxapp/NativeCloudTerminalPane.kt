package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth

/** Uses the shared workspace picker and remote terminal surface/composer. */
@Composable internal fun NativeCloudTerminalPane(model: NativeCloudViewModel, route: CloudWorkspaceRoute,
    snapshot: CloudWorkspaceSnapshot?) {
    val creation = model.creation.collectAsState().value
    val creationState = creation?.state?.collectAsState()?.value
    val terminal by route.host.selected.collectAsState()
    val row = snapshot?.rows?.singleOrNull { it.key == route.workspaceId }
    val shown = terminal
    val creationError = creationState?.failureFor(route.host.machineId)
    val connectionFailures by model.connectionFailures.collectAsState()
    val connectionError = connectionFailures[route.host.machineId]?.userReason ?: snapshot?.failure?.userReason
    if (row == null || shown == null || row.workspace.terminals.none { it.id == shown.id }) {
        NativeWorkspaceWaitingPane(row?.workspace?.title ?: "Cloud workspace unavailable", onBack = model::leaveWorkspace,
            connected = snapshot?.availability == NativeFeedAvailability.CONNECTED,
            connectionError = creationError ?: connectionError,
            onReconnect = { model.retryConnection(route.host.machineId, route.catalogOwner) })
    } else key(shown) {
        val state by shown.state.collectAsState()
        SshShellScreen(shown, reconnectError = creationError ?: connectionError?.takeUnless { it == state.error },
            onReconnect = { model.retryConnection(route.host.machineId, route.catalogOwner) },
            panePicker = {
                NativePanePicker(shown.title, row.workspace,
                    NativeWorkspacePane(terminal = row.workspace.terminals.singleOrNull { it.id == shown.id }),
                    Modifier.fillMaxWidth(), onTerminal = { model.openWorkspace(row, it.id, route.catalogOwner) },
                    onBrowser = {}, onSurface = {},
                    onNewWorkspace = if (creation?.canCreate(route.host.machineId) == true) ({
                        model.createWorkspace(route.host.machineId, route.catalogOwner)
                    }) else null,
                    onNewTerminal = if (creation?.canCreate(route.host.machineId, row.remoteId) == true) ({
                        model.createWorkspace(route.host.machineId, route.catalogOwner, row.remoteId)
                    }) else null)
            }, onBack = model::leaveWorkspace)
    }
}
