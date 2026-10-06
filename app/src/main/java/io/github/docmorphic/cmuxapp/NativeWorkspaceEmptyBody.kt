package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal data class NativeEmptyVisual(val guidance: NativeWorkspaceEmptyGuidance,
    val recovery: NativeWorkspaceEmptyRecoveryState, val docsError: Boolean, val canRetry: Boolean, val canClear: Boolean)
private fun Modifier.emptyVisualTag(measuring: Boolean, tag: String) = if (measuring) this else testTag(tag)

@Composable
internal fun NativeWorkspaceEmptyBody(model: NativeEmptyVisual, measuring: Boolean,
    retryEnabled: Boolean, clearEnabled: Boolean, docsEnabled: Boolean,
    retry: () -> Unit, clear: () -> Unit, docs: () -> Unit) {
    val guidance = model.guidance
    val recovery = model.recovery
    val docsError = model.docsError
    val filtered = guidance in setOf(NativeWorkspaceEmptyGuidance.SEARCH, NativeWorkspaceEmptyGuidance.UNREAD,
        NativeWorkspaceEmptyGuidance.MACHINES, NativeWorkspaceEmptyGuidance.UNREAD_MACHINES)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp).emptyVisualTag(measuring, "workspaces.empty"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!filtered) Icon(painterResource(R.drawable.ic_computer_desktop), null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when (guidance) {
            NativeWorkspaceEmptyGuidance.SEARCH -> "No workspaces match your search"
            NativeWorkspaceEmptyGuidance.UNREAD -> "No unread workspaces"
            NativeWorkspaceEmptyGuidance.MACHINES -> "No workspaces on the selected machines"
            NativeWorkspaceEmptyGuidance.UNREAD_MACHINES -> "No unread workspaces on the selected machines"
            NativeWorkspaceEmptyGuidance.ALL_COMPUTERS -> "No workspaces yet"
            else -> "No workspaces yet"
        }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() })
        Text(when (guidance) {
            NativeWorkspaceEmptyGuidance.MAC -> "Open cmux on your Mac and enable iOS pairing in Settings > Mobile. Use the same cmux account and team on both devices."
            NativeWorkspaceEmptyGuidance.CLOUD_HOST -> "No workspaces are available on this Cloud computer. Check its status in Cloud, or choose another computer."
            NativeWorkspaceEmptyGuidance.SSH_HOST -> "Create a workspace with New cmux Workspace, or open a New Shell below. Your SSH connection does not require Mac pairing."
            NativeWorkspaceEmptyGuidance.ALL_COMPUTERS -> "Use + to create a workspace on a computer."
            NativeWorkspaceEmptyGuidance.SEARCH -> "Try another search or clear the search field."
            NativeWorkspaceEmptyGuidance.UNREAD -> "Choose All workspaces from the filter to see your other workspaces."
            NativeWorkspaceEmptyGuidance.MACHINES, NativeWorkspaceEmptyGuidance.UNREAD_MACHINES -> "Change the workspace filter to see your other workspaces."
        }, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (filtered && guidance != NativeWorkspaceEmptyGuidance.SEARCH) if (model.canClear) {
            TextButton(onClick = { if (!measuring) clear() }, enabled = !measuring && clearEnabled, modifier = Modifier.emptyVisualTag(measuring, "workspace.filter.showAll")) { Text("Show All") }
        }
        if (guidance == NativeWorkspaceEmptyGuidance.MAC) {
            recovery.message?.let { Text(it, textAlign = TextAlign.Center, modifier = Modifier.emptyVisualTag(measuring, "workspaces.empty.message")) }
            if (model.canRetry) { Button(onClick = { if (!measuring) retry() }, enabled = !measuring && retryEnabled, modifier = Modifier.emptyVisualTag(measuring, "workspaces.empty.retry")) {
                if (recovery.busy) { if (measuring) Spacer(Modifier.padding(end = 8.dp).size(16.dp))
                    else CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp) }
                Text(if (recovery.busy) "Retrying…" else "Retry")
            } }
            OutlinedButton(onClick = { if (!measuring) docs() }, enabled = !measuring && docsEnabled, modifier = Modifier.emptyVisualTag(measuring, "workspaces.empty.docs")) { Text("See Docs") }
            if (docsError) Text("Could not open the setup guide. Try opening cmux.com/docs/ios in your browser.", color = MaterialTheme.colorScheme.error)
        }
    }
    }
}