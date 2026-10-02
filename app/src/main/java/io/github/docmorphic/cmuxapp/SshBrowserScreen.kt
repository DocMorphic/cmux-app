package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject

internal fun sshBrowserWorkspace(target: SshWorkspaceTarget, title: String): NativeWorkspace {
    val id = when (target) {
        is SshWorkspaceTarget.Shell -> JSONArray(listOf("shell", target.id))
        is SshWorkspaceTarget.Tmux -> JSONArray(listOf("tmux", target.workspace))
        is SshWorkspaceTarget.Browser -> target.selection.let { ref -> JSONArray(listOf("cmux", ref.session, ref.registry,
            ref.workspaceKey ?: ref.workspaceResource ?: "${ref.generation}:${ref.workspace}")) }
        is SshWorkspaceTarget.Cmux -> target.selection.let { ref -> JSONArray(listOf("cmux", ref.session, ref.registry,
            ref.workspaceKey ?: ref.workspaceResource ?: "${ref.generation}:${ref.workspace}")) }
    }.toString()
    return parseWorkspaces(JSONObject().put("workspaces", JSONArray().put(JSONObject()
        .put("id", id).put("title", title).put("terminals", JSONArray().put(JSONObject()
            .put("id", target.encode()).put("title", title)))))).single()
}

internal data class SshBrowserPresentation(val network: SshBrowserNetwork, val workspace: NativeWorkspace)

/** The dialog retains the terminal composition and its unsent draft behind the browser Activity. */
@Composable
internal fun SshBrowserSheet(presentation: SshBrowserPresentation, onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            SshBrowserScreen(presentation.network, presentation.workspace, onDone)
        }
    }
}

@Composable
internal fun SshBrowserScreen(network: SshBrowserNetwork, workspace: NativeWorkspace, onDone: () -> Unit) {
    val navigation = network.navigation
    val state by navigation.state.collectAsState()
    var started by remember(network, workspace.id) { mutableStateOf(false) }
    LaunchedEffect(network, workspace.id) {
        val key = LocalBrowserKey(network.storageId, null, "ssh:${network.storageId}", workspace.id)
        navigation.restoreRemembered(key, workspace)
        started = true
    }
    val destination = state.local
    LaunchedEffect(started, destination) { if (started && destination == null) onDone() }
    if (destination != null) RoutedLocalBrowserWorkspaceView(destination, navigation, workspace, { network },
        { RoutedBrowserHostLease({}, {}) }, {}, { onDone() })
}
