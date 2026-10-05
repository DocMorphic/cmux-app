package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Stable gesture ownership across lazy composition; the main process validates each opaque drop. */
@Composable
internal fun RoutedSidebarDragList(rows: List<RoutedSidebarRow>, revision: String?, more: Boolean,
    onMore: () -> Unit, onDrop: (RoutedSidebarDrop) -> Unit,
    before: LazyListScope.() -> Unit = {}, after: LazyListScope.() -> Unit = {},
    prefixKeys: List<String> = emptyList(),
    row: @Composable (RoutedSidebarRow) -> Unit) {
    val list = rememberLazyListState()
    val latestRows by rememberUpdatedState(rows)
    val latestRevision by rememberUpdatedState(revision)
    val latestDrop by rememberUpdatedState(onDrop)
    val latestMore by rememberUpdatedState(onMore)
    val hasMore by rememberUpdatedState(more)
    val menus = remember { WorkspaceContextMenuCoordinator() }
    val swipes = remember { WorkspaceSwipeCoordinator() }
    val scope = rememberCoroutineScope()
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    val inset = with(LocalDensity.current) { 8.dp.toPx() }
    var held by remember { mutableStateOf<RoutedSidebarRow?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var startY by remember { mutableFloatStateOf(0f) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var fingerOffset by remember { mutableFloatStateOf(0f) }
    var heldRevision by remember { mutableStateOf<String?>(null) }
    var proposal by remember { mutableStateOf<RoutedSidebarDrop?>(null) }
    var autoscroll by remember { mutableStateOf<Job?>(null) }
    fun cancel() { autoscroll?.cancel(); autoscroll = null; menus.heldKey = null; held = null; dragging = false; proposal = null }
    fun update() {
        val moving = held ?: return
        val version = heldRevision ?: return
        if (version != latestRevision) { cancel(); return }
        val visible = list.layoutInfo.visibleItemsInfo.filter { item -> latestRows.any { it.key == item.key } }
        val hit = visible.firstOrNull { pointerY >= it.offset && pointerY < it.offset + it.size }
        val target = latestRows.firstOrNull { it.key == hit?.key }
        val into = moving.kind == "workspace" && target?.kind == "group" && hit != null &&
            hit.size > inset * 2 && pointerY >= hit.offset + inset && pointerY <= hit.offset + hit.size - inset
        val next = visible.firstOrNull { pointerY < it.offset + it.size / 2f }
        proposal = if (into) RoutedSidebarDrop(version, moving.key, RoutedSidebarDropPlacement.INTO, target!!.key)
            else (next ?: visible.lastOrNull())?.let {
                RoutedSidebarDrop(version, moving.key, if (next == null) RoutedSidebarDropPlacement.AFTER else RoutedSidebarDropPlacement.BEFORE, it.key as String)
            }?.takeUnless { it.target == moving.key }
    }
    LaunchedEffect(revision) { if (held != null && heldRevision != revision) cancel() }
    DisposableEffect(Unit) { onDispose { autoscroll?.cancel() } }
    WorkspaceViewportAnchorEffect(list, prefixKeys + rows.map { it.key },
        gestureActive = held != null || swipes.activeKey != null)
    CompositionLocalProvider(LocalWorkspaceContextMenus provides menus, LocalWorkspaceSwipeCoordinator provides swipes) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGesturesAfterLongPress(onDragStart = { point ->
                val item = list.layoutInfo.visibleItemsInfo.firstOrNull { point.y >= it.offset && point.y < it.offset + it.size }
                latestRows.firstOrNull { it.key == item?.key && it.kind in setOf("workspace", "group") }?.let {
                    swipes.activeKey = null; held = it; heldRevision = latestRevision
                    startY = point.y; pointerY = point.y; fingerOffset = point.y - (item?.offset ?: 0)
                    menus.activeKey = WorkspaceContextMenuKey(it.key); menus.heldKey = menus.activeKey
                }
            }, onDrag = { change, amount ->
                val moving = held
                if (moving != null) {
                    change.consume(); pointerY += amount.y
                    if (!dragging && moving.drag != null && heldRevision != null && abs(pointerY - startY) > viewConfiguration.touchSlop) {
                        dragging = true; menus.activeKey = null
                        autoscroll = scope.launch {
                            var previous = withFrameNanos { it }; var requestedCount = -1
                            while (dragging) {
                                val now = withFrameNanos { it }; val dt = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f); previous = now
                                val info = list.layoutInfo
                                val speed = when {
                                    pointerY < info.viewportStartOffset + edge -> -((info.viewportStartOffset + edge - pointerY) / edge).coerceIn(0f, 1f)
                                    pointerY > info.viewportEndOffset - edge -> ((pointerY - info.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
                                    else -> 0f
                                }
                                if (speed != 0f) { list.scrollBy(speed * edge * 8 * dt); update() }
                                if (speed > 0 && hasMore && !list.canScrollForward && requestedCount != latestRows.size) {
                                    requestedCount = latestRows.size; latestMore()
                                }
                            }
                        }
                    }
                    if (dragging) update()
                }
            }, onDragCancel = ::cancel, onDragEnd = {
                if (dragging && heldRevision == latestRevision) proposal?.let(latestDrop)
                cancel()
            })
        }, state = list, userScrollEnabled = held == null) {
            before()
            items(rows, key = { it.key }, contentType = { it.kind }) { item ->
                val actions = listOf("Move up" to RoutedSidebarDropPlacement.UP, "Move down" to RoutedSidebarDropPlacement.DOWN)
                    .filter { (_, direction) -> if (direction == RoutedSidebarDropPlacement.UP) item.drag?.up == true else item.drag?.down == true }
                    .map { (label, direction) -> CustomAccessibilityAction(label) {
                        val version = latestRevision
                        val current = latestRows.firstOrNull { it.key == item.key }
                        val allowed = if (direction == RoutedSidebarDropPlacement.UP) current?.drag?.up == true else current?.drag?.down == true
                        if (version == null || !allowed) false else { latestDrop(RoutedSidebarDrop(version, item.key, direction)); true }
                    } }
                Column(Modifier.graphicsLayer { alpha = if (dragging && held?.key == item.key) .25f else 1f }) {
                    CompositionLocalProvider(LocalWorkspaceContextKey provides WorkspaceContextMenuKey(item.key),
                        LocalWorkspaceSwipeKey provides item.key, LocalWorkspaceMoveActions provides actions) { row(item) }
                }
            }
            after()
        }
        if (dragging) {
            proposal?.let { drop -> list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == drop.target }?.let { target ->
                val into = drop.placement == RoutedSidebarDropPlacement.INTO
                val y = target.offset + if (drop.placement == RoutedSidebarDropPlacement.AFTER) target.size else 0
                Box(Modifier.offset { IntOffset(0, y) }.fillMaxWidth()
                    .height(if (into) with(LocalDensity.current) { target.size.toDp() } else 2.dp)
                    .background(Color(0xFF76B9FF).copy(alpha = if (into) .18f else 1f)))
            } }
            held?.let { moving -> Column(Modifier.offset { IntOffset(0, (pointerY - fingerOffset).roundToInt()) }.fillMaxWidth()
                .graphicsLayer { shadowElevation = 16f; alpha = .96f }.background(Color(0xFF24272D)).clearAndSetSemantics { }) { row(moving) } }
        }
    }
    }
}
