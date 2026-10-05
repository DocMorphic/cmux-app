package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

@Composable
internal fun <T> rememberWorkspacePresentationRows(target: List<T>, holdOrder: Boolean,
    key: (T) -> String): List<T> {
    val committed = remember { WorkspacePresentationHolder(target) }
    val presentation = workspacePresentationRows(committed.rows, target, holdOrder, key)
    // Commit only successful compositions. A release consumes the latest target, not a queued intermediate poll.
    SideEffect { committed.rows = presentation }
    return presentation
}

private class WorkspacePresentationHolder<T>(var rows: List<T>)

internal val LocalWorkspaceRowAdmission = compositionLocalOf<() -> Boolean> { { true } }

/** Removed rows retain their geometry until gesture release, but cease to be actionable immediately. */
@Composable
internal fun WorkspacePresentationRow(present: Boolean, admission: () -> Boolean, content: @Composable () -> Unit) {
    val lifetime = remember { WorkspaceRowLifetime() }
    DisposableEffect(lifetime) { onDispose { lifetime.mounted = false } }
    val outer = LocalWorkspaceRowAdmission.current
    val latestAdmission by rememberUpdatedState(admission)
    val latestOuter by rememberUpdatedState(outer)
    val allowed = remember { { lifetime.mounted && latestOuter() && latestAdmission() } }
    CompositionLocalProvider(LocalWorkspaceRowAdmission provides allowed) {
        Box(if (present) Modifier else Modifier.graphicsLayer { alpha = .45f }.clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }) { content() }
    }
}

private class WorkspaceRowLifetime { var mounted = true }
