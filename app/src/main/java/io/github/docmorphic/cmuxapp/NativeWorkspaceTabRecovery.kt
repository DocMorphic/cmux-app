package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Only a pending restore polls. Captured ownership and its ticket fence every result. */
@Composable
internal fun NativeWorkspaceTabRecovery(navigation: NativeWorkspaceTabNavigation,
    pending: NativeWorkspacePendingTab?, client: MobileRpcClient?, ready: Boolean,
    capabilities: Set<String>, readListing: suspend (MobileRpcClient) -> NativeWorkspaceSnapshot, isCurrent: () -> Boolean,
    onSnapshot: (NativeWorkspaceSnapshot) -> NativeWorkspaceTab?,
    onChoice: (NativeWorkspaceSnapshot, NativeWorkspace, NativeWorkspaceTabChoice) -> Unit, onMissing: () -> Unit) {
    val read by rememberUpdatedState(readListing)
    val current by rememberUpdatedState(isCurrent)
    val snapshot by rememberUpdatedState(onSnapshot)
    val choose by rememberUpdatedState(onChoice)
    val missing by rememberUpdatedState(onMissing)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(pending?.id, client, ready, capabilities, lifecycle) {
        val ticket = pending ?: return@LaunchedEffect
        val active = client ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        fun valid() = navigation.pending.value == ticket && current()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (valid()) {
            try {
                val listing = read(active)
                if (!valid()) return@repeatOnLifecycle
                val workspace = listing.workspaces.singleOrNull { it.id == ticket.key.workspaceId }
                if (!listing.accept()) throw NativeWorkspaceSnapshotSuperseded()
                if (workspace == null) { navigation.cancel(); snapshot(listing); missing(); return@repeatOnLifecycle }
                // Publish known panes before awaiting a separate discovery RPC.
                // A remembered tab keeps its intent while this interim pane changes.
                navigation.refreshInterim(ticket, snapshot(listing))
                if (!valid()) return@repeatOnLifecycle
                val browsers = if ((ticket.tab == null || ticket.tab.kind == NativeWorkspaceTabKind.BROWSER_STREAM) && "browser.stream.v1" in capabilities) {
                    try { withTimeoutOrNull(15_000) { parseWorkspaceBrowserPanels(active.browserPanels(workspace.id), workspace.id) } }
                    catch (failure: Exception) { if (failure is CancellationException) throw failure; null }
                } else if ("browser.stream.v1" !in capabilities) emptyList() else null
                if (!valid()) return@repeatOnLifecycle
                if (!listing.accept()) throw NativeWorkspaceSnapshotSuperseded()
                navigation.resolve(ticket, workspace, browsers)?.let { choice -> choose(listing, workspace, choice); return@repeatOnLifecycle }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // A failed read cannot retire the remembered tab. Reconnect can supply a new client.
            }
            delay(2_000)
        }
        }
    }
}

@Composable
internal fun NativeWorkspaceWaitingPane(title: String, onBack: () -> Unit,
    onNewTerminal: (() -> Unit)? = null, onNewBrowser: (() -> Unit)? = null,
    connected: Boolean = true, connectionError: String? = null, onReconnect: (() -> Unit)? = null,
    reconnectingLabel: String = "Reconnecting to your Mac…") {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(18.dp).testTag("WorkspaceWaiting"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        NativeWorkspaceBackControl { TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back to workspaces" }) { Text("‹  Workspaces") } }
        Text(title, style = MaterialTheme.typography.titleMedium)
        CircularProgressIndicator(Modifier.size(24.dp))
        Text(if (connected) "Waiting for workspace panes…" else reconnectingLabel)
        connectionError?.let { Text(it) }
        if (!connected && onReconnect != null) TextButton(onClick = onReconnect) { Text("Reconnect") }
        if (onNewTerminal != null || onNewBrowser != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { onNewTerminal?.invoke() }, enabled = onNewTerminal != null) { Text("New terminal") }
            TextButton(onClick = { onNewBrowser?.invoke() }, enabled = onNewBrowser != null) { Text("New browser") }
        }
    }
}
