package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Decrypt once per account revision, not once per workspace row/recomposition. */
@Composable
internal fun rememberWorkspaceTabSnapshot(store: NativeCredentialStore, login: String?): NativeWorkspaceLastTabs {
    val revision by store.revisions.collectAsState()
    return remember(store, login, revision) {
        val state = store.load()?.takeIf { login != null && it.optString("task_session") == login && it.optString("refresh_token").isNotBlank() }
        NativeWorkspaceLastTabs(state?.optJSONObject(NativeWorkspaceLastTabs.STORAGE_KEY))
    }
}

/** Only a pending restore polls. Captured ownership and its ticket fence every result. */
@Composable
internal fun NativeWorkspaceTabRecovery(navigation: NativeWorkspaceTabNavigation,
    pending: NativeWorkspacePendingTab?, client: MobileRpcClient?, ready: Boolean,
    capabilities: Set<String>, isCurrent: () -> Boolean,
    onChoice: (JSONObject, NativeWorkspace, NativeWorkspaceTabChoice) -> Unit, onMissing: () -> Unit) {
    val current by rememberUpdatedState(isCurrent)
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
                val listing = active.workspaces()
                if (!valid()) return@repeatOnLifecycle
                val workspace = parseAuthoritativeWorkspaces(listing).singleOrNull { it.id == ticket.key.workspaceId }
                if (workspace == null) { navigation.cancel(); missing(); return@repeatOnLifecycle }
                val browsers = if (ticket.tab.kind == NativeWorkspaceTabKind.BROWSER_STREAM && "browser.stream.v1" in capabilities) {
                    try { withTimeoutOrNull(15_000) { parseWorkspaceBrowserPanels(active.browserPanels(workspace.id), workspace.id) } }
                    catch (failure: Exception) { if (failure is CancellationException) throw failure; null }
                } else if ("browser.stream.v1" !in capabilities) emptyList() else null
                if (!valid()) return@repeatOnLifecycle
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
internal fun NativeWorkspaceWaitingPane(title: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("‹  Workspaces") }
        Text(title, style = MaterialTheme.typography.titleMedium)
        CircularProgressIndicator(Modifier.size(24.dp))
        Text("Waiting for workspace panes…")
    }
}
