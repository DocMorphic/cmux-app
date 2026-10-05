package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * Override LazyColumn's first-key tracking before a structural remeasure. Otherwise a
 * notification moving that key to the top takes the whole viewport with it. A SideEffect
 * reads the last committed layout after successful composition, never an abandoned one.
 * Keys must include every prefix row and be in precisely the LazyColumn's order.
 */
@Composable
internal fun WorkspaceViewportAnchorEffect(list: LazyListState, keys: List<String>, gestureActive: Boolean) {
    val committed = remember(list) { WorkspaceViewportKeys() }
    SideEffect {
        val previous = committed.keys
        committed.keys = keys
        if (previous == null || previous == keys || gestureActive || list.isScrollInProgress) return@SideEffect
        val layout = list.layoutInfo
        if (layout.visibleItemsInfo.isEmpty()) return@SideEffect
        val position = workspaceViewportPosition(previous, keys,
            layout.visibleItemsInfo.mapNotNull { item -> (item.key as? String)?.let {
                WorkspaceViewportItem(it, item.offset, item.size)
            } }, layout.viewportStartOffset, layout.viewportEndOffset, atTop = !list.canScrollBackward)
        position?.let { list.requestScrollToItem(it.index, it.scrollOffset) }
    }
}

private class WorkspaceViewportKeys { var keys: List<String>? = null }
