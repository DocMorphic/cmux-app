/* Interaction behavior follows cmux WorkspaceListTableCoordinator at
 * 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

internal class WorkspaceSwipeCoordinator { var activeKey by mutableStateOf<String?>(null) }
internal val LocalWorkspaceSwipeCoordinator = compositionLocalOf<WorkspaceSwipeCoordinator?> { null }
internal val LocalWorkspaceSwipeKey = compositionLocalOf<String?> { null }

/** Positive logical displacement reveals the leading read action, including in RTL.
 * Horizontal slop recognition leaves vertical scrolling and held reordering with the list.
 * Full swipes invoke an action without removing the authoritative workspace row.
 */
@Composable
internal fun NativeWorkspaceSwipeActions(
    workspaceId: String, hasUnread: Boolean, canRead: Boolean, canClose: Boolean,
    onRead: (unread: Boolean) -> Unit, onClose: () -> Unit,
    readLabel: String = "Mark as Read", unreadLabel: String = "Mark as Unread", leadingColor: Color = Color(0xFF007AFF),
    content: @Composable (dismissIfOpen: () -> Boolean, actions: WorkspaceSwipeActions) -> Unit
) {
    val fallback = remember { WorkspaceSwipeCoordinator() }
    val coordinator = LocalWorkspaceSwipeCoordinator.current ?: fallback
    val key = LocalWorkspaceSwipeKey.current ?: workspaceId
    DisposableEffect(coordinator, key) {
        onDispose { if (coordinator.activeKey == key) coordinator.activeKey = null }
    }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    val actionWidth = with(LocalDensity.current) { 104.dp.toPx() }
    var width by remember { mutableIntStateOf(0) }
    var offset by remember(key) { mutableFloatStateOf(0f) }
    var dragging by remember(key) { mutableStateOf(false) }
    val currentActions = WorkspaceSwipeActions(hasUnread, canRead, canClose)
    val committed = remember(key) { WorkspaceSwipeActionHolder(currentActions) }
    val geometryHeld = LocalWorkspaceGeometryHeld.current || coordinator.activeKey == key || offset != 0f
    val shownActions = if (geometryHeld) committed.actions else currentActions
    SideEffect { committed.actions = shownActions }
    val latestCanRead by rememberUpdatedState(canRead)
    val latestCanClose by rememberUpdatedState(canClose)
    val latestAdmission by rememberUpdatedState(LocalWorkspaceRowAdmission.current)
    val latestRead by rememberUpdatedState(onRead)
    val latestClose by rememberUpdatedState(onClose)
    fun dismiss(): Boolean {
        val wasOpen = offset != 0f
        offset = 0f; dragging = false
        return wasOpen
    }
    LaunchedEffect(coordinator.activeKey, canRead, canClose) {
        if (coordinator.activeKey != key || (offset > 0 && !canRead) || (offset < 0 && !canClose)) dismiss()
    }
    BackHandler(offset != 0f) { dismiss() }
    val displayed by animateFloatAsState(offset, if (dragging) snap() else tween(160), label = "workspace swipe")
    // Also handles a cancelled gesture that never drew a displaced frame (no animation callback).
    LaunchedEffect(offset, dragging, coordinator.activeKey) {
        if (offset == 0f && !dragging && coordinator.activeKey == key) {
            snapshotFlow { displayed }.first { it == 0f }
            if (offset == 0f && !dragging && coordinator.activeKey == key) coordinator.activeKey = null
        }
    }
    Box(Modifier.fillMaxWidth().clipToBounds().onSizeChanged { width = it.width }
        .testTag("workspace.swipe:$workspaceId")
        .pointerInput(key, shownActions, direction) {
            if (shownActions.canRead || shownActions.canClose) detectHorizontalDragGestures(
                onDragStart = { coordinator.activeKey = key; dragging = true },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    offset = (offset + amount * direction).coerceIn(
                        if (shownActions.canClose) -width.toFloat() else 0f, if (shownActions.canRead) width.toFloat() else 0f)
                },
                onDragCancel = { dismiss() },
                onDragEnd = {
                    dragging = false
                    val value = offset
                    when {
                        width > 0 && abs(value) >= maxOf(actionWidth, width * .65f) -> {
                            dismiss()
                            if (latestAdmission()) {
                                if (value > 0 && shownActions.canRead && latestCanRead) latestRead(!shownActions.hasUnread)
                                else if (value < 0 && shownActions.canClose && latestCanClose) latestClose()
                            }
                        }
                        abs(value) >= actionWidth / 2 -> offset = if (value > 0) actionWidth else -actionWidth
                        else -> dismiss()
                    }
                })
        }) {
        if (displayed != 0f) {
            val leading = displayed > 0
            // A button exists only for the exposed side; hidden actions aren't TalkBack targets.
            Box(Modifier.matchParentSize().background(if (leading) leadingColor else Color(0xFFFF3B30))) {
                Box(Modifier.align(if (leading) Alignment.CenterStart else Alignment.CenterEnd)
                    .width(104.dp).fillMaxHeight()
                    .testTag("workspace.swipe.action:$workspaceId")
                    .clickable(role = Role.Button) {
                        dismiss()
                        if (latestAdmission()) {
                            if (leading && shownActions.canRead && latestCanRead) latestRead(!shownActions.hasUnread)
                            else if (!leading && shownActions.canClose && latestCanClose) latestClose()
                        }
                    }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text(if (leading) if (shownActions.hasUnread) readLabel else unreadLabel else "Delete",
                        color = Color.White, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        Box(Modifier.absoluteOffset { IntOffset((displayed * direction).roundToInt(), 0) }
            .background(Color(0xFF0B0C0E))) {
            CompositionLocalProvider(LocalWorkspaceGeometryHeld provides geometryHeld) { content(::dismiss, shownActions) }
        }
    }
}

internal data class WorkspaceSwipeActions(val hasUnread: Boolean, val canRead: Boolean, val canClose: Boolean)
private class WorkspaceSwipeActionHolder(var actions: WorkspaceSwipeActions)
