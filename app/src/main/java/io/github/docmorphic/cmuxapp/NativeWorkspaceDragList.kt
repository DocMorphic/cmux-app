package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** One stable gesture surface lets a held row cross lazy-list composition and auto-scroll boundaries. */
@Composable
internal fun NativeWorkspaceDragList(
    entries: List<WorkspaceListEntry>, reorderEnabled: Boolean, modifier: Modifier = Modifier,
    onMove: (NativeFeedSource, String, NativeWorkspaceMove) -> Boolean,
    before: LazyListScope.() -> Unit = {}, empty: @Composable () -> Unit,
    row: @Composable (WorkspaceListEntry) -> Unit
) {
    val list = rememberLazyListState()
    val latestEntries by rememberUpdatedState(entries)
    val latestMove by rememberUpdatedState(onMove)
    val latestEnabled by rememberUpdatedState(reorderEnabled)
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    var dragged by remember { mutableStateOf<WorkspaceListEntry?>(null) }
    var snapshot by remember { mutableStateOf<List<WorkspaceListEntry>>(emptyList()) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var fingerOffset by remember { mutableFloatStateOf(0f) }
    var destination by remember { mutableIntStateOf(-1) }
    fun cancel() { dragged = null; snapshot = emptyList(); destination = -1 }
    fun updateDestination() {
        val visible = list.layoutInfo.visibleItemsInfo.filter { item -> snapshot.any { it.key == item.key } }
        val next = visible.firstOrNull { pointerY < it.offset + it.size / 2f }
        destination = if (next != null) snapshot.indexOfFirst { it.key == next.key }
            else visible.lastOrNull()?.let { item -> snapshot.indexOfFirst { it.key == item.key } + 1 } ?: -1
    }
    LaunchedEffect(reorderEnabled) { if (!reorderEnabled) cancel() }
    LaunchedEffect(dragged?.key) {
        if (dragged == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (dragged != null) {
            val now = withFrameNanos { it }
            val dt = ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f); previous = now
            val info = list.layoutInfo
            val speed = when {
                pointerY < info.viewportStartOffset + edge -> -((info.viewportStartOffset + edge - pointerY) / edge).coerceIn(0f, 1f)
                pointerY > info.viewportEndOffset - edge -> ((pointerY - info.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f) { list.scrollBy(speed * edge * 8 * dt); updateDestination() }
        }
    }
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().pointerInput(reorderEnabled) {
            if (reorderEnabled) detectDragGesturesAfterLongPress(
                onDragStart = { point ->
                    val item = list.layoutInfo.visibleItemsInfo.firstOrNull { point.y >= it.offset && point.y < it.offset + it.size }
                    val entry = latestEntries.firstOrNull { it.key == item?.key }
                    if (entry != null && entry !is WorkspaceListEntry.Footer &&
                        (entry !is WorkspaceListEntry.Header || entry.group.liveAnchorWorkspaceId != null)) {
                        snapshot = latestEntries; dragged = entry; pointerY = point.y
                        fingerOffset = point.y - (item?.offset ?: 0)
                        updateDestination()
                    }
                },
                onDrag = { change, amount -> if (dragged != null) {
                    change.consume(); pointerY += amount.y; updateDestination()
                } },
                onDragCancel = ::cancel,
                onDragEnd = {
                    val entry = dragged
                    if (entry != null && destination >= 0) {
                        workspaceDropIntent(entry.source, snapshot, snapshot.indexOfFirst { it.key == entry.key }, destination)
                            ?.let { (id, intent) -> latestMove(entry.source, id, intent) }
                    }
                    cancel()
                }
            )
        }, state = list, userScrollEnabled = dragged == null, contentPadding = PaddingValues(bottom = 84.dp)) {
            before()
            itemsIndexed(entries, key = { _, item -> item.key }) { index, entry ->
                val actions = remember(entries, reorderEnabled, index) { if (reorderEnabled) listOf("Move up" to false, "Move down" to true)
                    .mapNotNull { (label, down) ->
                        workspaceStepIntent(entry.source, entries, index, down)?.let { (id, intent) ->
                            CustomAccessibilityAction(label) { latestEnabled && latestMove(entry.source, id, intent) }
                        }
                    } else emptyList() }
                Column(Modifier.animateItem().graphicsLayer { alpha = if (dragged?.key == entry.key) 0.25f else 1f }
                    .semantics { customActions = actions }) { row(entry) }
            }
            if (entries.isEmpty()) item { empty() }
        }
        val moving = dragged
        if (moving != null) {
            val visible = list.layoutInfo.visibleItemsInfo
            val nextKey = snapshot.getOrNull(destination)?.key
            val lineY = visible.firstOrNull { it.key == nextKey }?.offset
                ?: if (destination == snapshot.size) visible.lastOrNull()?.let { it.offset + it.size } else null
            if (lineY != null) Box(Modifier.offset { IntOffset(0, lineY) }.fillMaxWidth().height(2.dp).background(Color(0xFF76B9FF)))
            Column(Modifier.offset { IntOffset(0, (pointerY - fingerOffset).roundToInt()) }.fillMaxWidth()
                .graphicsLayer { shadowElevation = 16f; alpha = 0.96f }.background(Color(0xFF24272D))
                .clearAndSetSemantics { }) { row(moving) }
        }
    }
}
