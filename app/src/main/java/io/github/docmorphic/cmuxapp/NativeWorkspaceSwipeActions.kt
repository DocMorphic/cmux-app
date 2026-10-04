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
    onRead: () -> Unit, onClose: () -> Unit,
    content: @Composable (dismissIfOpen: () -> Boolean) -> Unit
) {
    val fallback = remember { WorkspaceSwipeCoordinator() }
    val coordinator = LocalWorkspaceSwipeCoordinator.current ?: fallback
    val key = LocalWorkspaceSwipeKey.current ?: workspaceId
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    val actionWidth = with(LocalDensity.current) { 104.dp.toPx() }
    var width by remember { mutableIntStateOf(0) }
    var offset by remember(key) { mutableFloatStateOf(0f) }
    var dragging by remember(key) { mutableStateOf(false) }
    val latestRead by rememberUpdatedState(onRead)
    val latestClose by rememberUpdatedState(onClose)
    fun dismiss(): Boolean {
        val wasOpen = offset != 0f
        offset = 0f; dragging = false
        if (coordinator.activeKey == key) coordinator.activeKey = null
        return wasOpen
    }
    LaunchedEffect(coordinator.activeKey, canRead, canClose) {
        if (coordinator.activeKey != key || (offset > 0 && !canRead) || (offset < 0 && !canClose)) dismiss()
    }
    BackHandler(offset != 0f) { dismiss() }
    val displayed by animateFloatAsState(offset, if (dragging) snap() else tween(160), label = "workspace swipe")
    Box(Modifier.fillMaxWidth().clipToBounds().onSizeChanged { width = it.width }
        .testTag("workspace.swipe:$workspaceId")
        .pointerInput(key, canRead, canClose, direction) {
            if (canRead || canClose) detectHorizontalDragGestures(
                onDragStart = { coordinator.activeKey = key; dragging = true },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    offset = (offset + amount * direction).coerceIn(
                        if (canClose) -width.toFloat() else 0f, if (canRead) width.toFloat() else 0f)
                },
                onDragCancel = { dismiss() },
                onDragEnd = {
                    dragging = false
                    val value = offset
                    when {
                        width > 0 && abs(value) >= maxOf(actionWidth, width * .65f) -> {
                            dismiss()
                            if (value > 0 && canRead) latestRead() else if (value < 0 && canClose) latestClose()
                        }
                        abs(value) >= actionWidth / 2 -> offset = if (value > 0) actionWidth else -actionWidth
                        else -> dismiss()
                    }
                })
        }) {
        if (displayed != 0f) {
            val leading = displayed > 0
            // A button exists only for the exposed side; hidden actions aren't TalkBack targets.
            Box(Modifier.matchParentSize().background(if (leading) Color(0xFF007AFF) else Color(0xFFFF3B30))) {
                Box(Modifier.align(if (leading) Alignment.CenterStart else Alignment.CenterEnd)
                    .width(104.dp).fillMaxHeight()
                    .testTag("workspace.swipe.action:$workspaceId")
                    .clickable(role = Role.Button) {
                        dismiss()
                        if (leading && canRead) latestRead() else if (!leading && canClose) latestClose()
                    }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text(if (leading) if (hasUnread) "Mark as Read" else "Mark as Unread" else "Delete",
                        color = Color.White, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        Box(Modifier.absoluteOffset { IntOffset((displayed * direction).roundToInt(), 0) }
            .background(Color(0xFF0B0C0E))) { content(::dismiss) }
    }
}
