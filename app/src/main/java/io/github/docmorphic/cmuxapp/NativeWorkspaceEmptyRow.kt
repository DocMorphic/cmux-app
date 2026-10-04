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

internal enum class NativeWorkspaceEmptyGuidance { MAC, SSH_HOST, ALL_COMPUTERS, SEARCH, UNREAD }

@Composable
internal fun NativeWorkspaceEmptyRow(guidance: NativeWorkspaceEmptyGuidance,
    recovery: NativeWorkspaceEmptyRecoveryState = NativeWorkspaceEmptyRecoveryState(), onRetry: (() -> Unit)? = null) {
    val uri = LocalUriHandler.current
    var docsError by remember { mutableStateOf(false) }
    val filtered = guidance == NativeWorkspaceEmptyGuidance.SEARCH || guidance == NativeWorkspaceEmptyGuidance.UNREAD
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp).testTag("workspaces.empty"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!filtered) Icon(painterResource(R.drawable.ic_computer_desktop), null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when (guidance) {
            NativeWorkspaceEmptyGuidance.SEARCH -> "No workspaces match your search"
            NativeWorkspaceEmptyGuidance.UNREAD -> "No unread workspaces"
            NativeWorkspaceEmptyGuidance.ALL_COMPUTERS -> "No workspaces yet"
            else -> "No workspaces yet"
        }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() })
        Text(when (guidance) {
            NativeWorkspaceEmptyGuidance.MAC -> "Open cmux on your Mac and enable iOS pairing in Settings > Mobile. Use the same cmux account and team on both devices."
            NativeWorkspaceEmptyGuidance.SSH_HOST -> "Create a workspace with New cmux Workspace, or open a New Shell below. Your SSH connection does not require Mac pairing."
            NativeWorkspaceEmptyGuidance.ALL_COMPUTERS -> "Use + to create a workspace on a computer."
            NativeWorkspaceEmptyGuidance.SEARCH -> "Try another search or clear the search field."
            NativeWorkspaceEmptyGuidance.UNREAD -> "Choose All workspaces from the filter to see your other workspaces."
        }, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (guidance == NativeWorkspaceEmptyGuidance.MAC) {
            recovery.message?.let { Text(it, textAlign = TextAlign.Center, modifier = Modifier.testTag("workspaces.empty.message")) }
            onRetry?.let { retry -> Button(onClick = retry, enabled = !recovery.busy, modifier = Modifier.testTag("workspaces.empty.retry")) {
                if (recovery.busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                Text(if (recovery.busy) "Retrying…" else "Retry")
            } }
            OutlinedButton(onClick = { docsError = runCatching { uri.openUri(DOCS) }.isFailure }, modifier = Modifier.testTag("workspaces.empty.docs")) { Text("See Docs") }
            if (docsError) Text("Could not open the setup guide. Try opening cmux.com/docs/ios in your browser.", color = MaterialTheme.colorScheme.error)
        }
    }
    }
}
private const val DOCS = "https://cmux.com/docs/ios#prerequisites"
