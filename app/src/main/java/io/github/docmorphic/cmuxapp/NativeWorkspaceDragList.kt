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
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** One stable gesture surface lets a held row cross lazy-list composition and auto-scroll boundaries. */
@Composable
internal fun NativeWorkspaceDragList(
    entries: List<WorkspaceListEntry>, reorderEnabled: Boolean, modifier: Modifier = Modifier,
    onMove: (NativeFeedSource, String, NativeWorkspaceMove) -> Boolean,
    rowHandlesAccessibility: Boolean = true,
    before: LazyListScope.() -> Unit = {}, after: LazyListScope.() -> Unit = {},
    hasOtherRows: Boolean = false,
    displayRows: List<NativeWorkspaceDisplayRow>? = null,
    sshRow: @Composable (SshFeedRow) -> Unit = {}, empty: @Composable () -> Unit,
    row: @Composable (WorkspaceListEntry) -> Unit
) {
    val list = rememberLazyListState()
    val latestEntries by rememberUpdatedState(entries)
    val latestMove by rememberUpdatedState(onMove)
    val latestEnabled by rememberUpdatedState(reorderEnabled)
    val scope = rememberCoroutineScope()
    var autoscroll by remember { mutableStateOf<Job?>(null) }
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val contextMenus = remember { WorkspaceContextMenuCoordinator() }
    var held by remember { mutableStateOf<WorkspaceListEntry?>(null) }
    var holdY by remember { mutableFloatStateOf(0f) }
    var dragged by remember { mutableStateOf<WorkspaceListEntry?>(null) }
    var snapshot by remember { mutableStateOf<List<WorkspaceListEntry>>(emptyList()) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var fingerOffset by remember { mutableFloatStateOf(0f) }
    var destination by remember { mutableIntStateOf(-1) }
    fun cancel() { autoscroll?.cancel(); autoscroll = null; held = null; contextMenus.heldKey = null; dragged = null; snapshot = emptyList(); destination = -1 }
    fun updateDestination() {
        val visible = list.layoutInfo.visibleItemsInfo.filter { item -> snapshot.any { it.key == item.key } }
        val next = visible.firstOrNull { pointerY < it.offset + it.size / 2f }
        destination = if (next != null) snapshot.indexOfFirst { it.key == next.key }
            else visible.lastOrNull()?.let { item -> snapshot.indexOfFirst { it.key == item.key } + 1 } ?: -1
    }
    LaunchedEffect(reorderEnabled) { if (!reorderEnabled) cancel() }
    fun startAutoscroll() {
        autoscroll?.cancel()
        autoscroll = scope.launch(start = CoroutineStart.UNDISPATCHED) {
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
    }
    val swipeCoordinator = remember { WorkspaceSwipeCoordinator() }
    CompositionLocalProvider(LocalWorkspaceSwipeCoordinator provides swipeCoordinator, LocalWorkspaceContextMenus provides contextMenus) {
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().pointerInput(reorderEnabled) {
            detectDragGesturesAfterLongPress(
                onDragStart = { point ->
                    val item = list.layoutInfo.visibleItemsInfo.firstOrNull { point.y >= it.offset && point.y < it.offset + it.size }
                    val entry = latestEntries.firstOrNull { it.key == item?.key }
                    if (entry != null && entry !is WorkspaceListEntry.Footer) {
                        swipeCoordinator.activeKey = null
                        val contextKey = WorkspaceContextMenuKey(entry.key, entry.source.mac)
                        contextMenus.activeKey = contextKey.takeIf { entry !is WorkspaceListEntry.Header ||
                            entry.source.canEditGroups() || entry.source.canCreateInGroup() }
                        contextMenus.heldKey = contextKey
                        snapshot = latestEntries; held = entry; pointerY = point.y; holdY = point.y
                        fingerOffset = point.y - (item?.offset ?: 0)
                    }
                },
                onDrag = { change, amount ->
                    val entry = held
                    if (entry != null) {
                        change.consume(); pointerY += amount.y
                        if (dragged == null && latestEnabled && abs(pointerY - holdY) > viewConfiguration.touchSlop &&
                            (entry !is WorkspaceListEntry.Header || entry.group.liveAnchorWorkspaceId != null)) {
                            contextMenus.activeKey = null
                            dragged = entry
                            startAutoscroll()
                        }
                        if (dragged != null) updateDestination()
                    }
                },
                onDragCancel = ::cancel,
                onDragEnd = {
                    val entry = dragged
                    if (entry != null && latestEnabled && destination >= 0) {
                        workspaceDropIntent(entry.source, snapshot, snapshot.indexOfFirst { it.key == entry.key }, destination)
                            ?.let { (id, intent) -> latestMove(entry.source, id, intent) }
                    }
                    cancel()
                }
            )
        }, state = list, userScrollEnabled = held == null, contentPadding = PaddingValues(bottom = 84.dp)) {
            before()
            itemsIndexed(displayRows ?: entries.map(NativeWorkspaceDisplayRow::Mac), key = { _, item -> item.key }) { _, item ->
                if (item is NativeWorkspaceDisplayRow.Ssh) {
                    Column(Modifier.animateItem()) { sshRow(item.row) }
                    return@itemsIndexed
                }
                val entry = (item as NativeWorkspaceDisplayRow.Mac).entry
                val index = entries.indexOfFirst { it.key == entry.key }
                val actions = remember(entries, reorderEnabled, index) { if (reorderEnabled) listOf("Move up" to false, "Move down" to true)
                    .mapNotNull { (label, down) ->
                        workspaceStepIntent(entry.source, entries, index, down)?.let { (id, intent) ->
                            CustomAccessibilityAction(label) {
                                val currentIndex = latestEntries.indexOfFirst { it.key == entry.key && it.source.mac == entry.source.mac }
                                if (!latestEnabled || currentIndex < 0) false else {
                                    val current = latestEntries[currentIndex]
                                    workspaceStepIntent(current.source, latestEntries, currentIndex, down)
                                        ?.let { (freshId, freshIntent) -> latestMove(current.source, freshId, freshIntent) } ?: false
                                }
                            }
                        }
                    } else emptyList() }
                Column(Modifier.animateItem().graphicsLayer { alpha = if (dragged?.key == entry.key) 0.25f else 1f }
                    .then(if (rowHandlesAccessibility) Modifier else Modifier.semantics { customActions = actions })) {
                    CompositionLocalProvider(LocalWorkspaceSwipeKey provides entry.key, LocalWorkspaceMoveActions provides actions,
                        LocalWorkspaceContextKey provides WorkspaceContextMenuKey(entry.key, entry.source.mac)) { row(entry) }
                }
            }
            after()
            if ((displayRows?.isEmpty() ?: entries.isEmpty()) && !hasOtherRows) item { empty() }
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
}
