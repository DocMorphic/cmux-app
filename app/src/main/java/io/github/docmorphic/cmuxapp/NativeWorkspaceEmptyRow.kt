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

internal enum class NativeWorkspaceEmptyGuidance { MAC, SSH_HOST, CLOUD_HOST, HIDDEN_CLOUD, ALL_COMPUTERS, SEARCH, UNREAD, MACHINES, UNREAD_MACHINES }

@Composable
internal fun NativeWorkspaceEmptyRow(guidance: NativeWorkspaceEmptyGuidance,
    recovery: NativeWorkspaceEmptyRecoveryState = NativeWorkspaceEmptyRecoveryState(), onRetry: (() -> Unit)? = null,
    onClearFilter: (() -> Unit)? = null) {
    val uri = LocalUriHandler.current
    var docsError by remember(guidance) { mutableStateOf(false) }
    val lifetime = remember { EmptyWorkspaceLifetime() }
    DisposableEffect(lifetime) { onDispose { lifetime.mounted = false } }
    val admission by rememberUpdatedState(LocalWorkspaceRowAdmission.current)
    val currentGuidance by rememberUpdatedState(guidance)
    val currentRecovery by rememberUpdatedState(recovery)
    val currentRetry by rememberUpdatedState(onRetry)
    val currentClear by rememberUpdatedState(onClearFilter)
    val currentUri by rememberUpdatedState(uri)
    fun allowed() = lifetime.mounted && admission()
    fun retryAllowed() = allowed() && currentGuidance == NativeWorkspaceEmptyGuidance.MAC &&
        !currentRecovery.busy && currentRetry != null
    fun clearAllowed() = allowed() && currentGuidance in setOf(NativeWorkspaceEmptyGuidance.UNREAD,
        NativeWorkspaceEmptyGuidance.MACHINES, NativeWorkspaceEmptyGuidance.UNREAD_MACHINES) && currentClear != null
    WorkspaceMeasuredContent(NativeEmptyVisual(guidance, recovery, docsError, onRetry != null, onClearFilter != null),
        LocalWorkspaceGeometryHeld.current) { shown, measuring ->
        NativeWorkspaceEmptyBody(shown, measuring, retryAllowed(), clearAllowed(),
            allowed() && currentGuidance == NativeWorkspaceEmptyGuidance.MAC,
            retry = { if (retryAllowed()) currentRetry?.invoke() },
            clear = { if (clearAllowed()) currentClear?.invoke() },
            docs = { if (allowed() && currentGuidance == NativeWorkspaceEmptyGuidance.MAC)
                docsError = runCatching { currentUri.openUri(DOCS) }.isFailure })
    }
}
private class EmptyWorkspaceLifetime { var mounted = true }
private const val DOCS = "https://cmux.com/docs/ios#prerequisites"
