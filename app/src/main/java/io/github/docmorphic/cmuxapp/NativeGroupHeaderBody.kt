package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties

private val nativeMuted = Color(0xFF9B9FA8)
internal data class GroupRowVisual(val group: NativeGroup, val expanded: Boolean,
    val unread: NativeWorkspaceUnread, val highlighted: Boolean)

/** Stateless header drawing, measured separately from live menus and confirmation state. */
@Composable
internal fun NativeGroupHeaderBody(model: GroupRowVisual, measuring: Boolean, handlesHold: Boolean,
    canOpen: Boolean, hasMenu: Boolean, blocked: Boolean, moveActions: List<CustomAccessibilityAction>,
    open: () -> Unit, toggle: () -> Unit, showMenu: () -> Boolean) {
    val group = model.group
    val expanded = model.expanded
    val unread = model.unread
    val highlighted = model.highlighted
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp)
        .background(if (highlighted) LocalContentColor.current.copy(alpha = .08f) else Color.Transparent, RoundedCornerShape(6.dp))
        .padding(horizontal = 4.dp, vertical = 2.dp).then(if (measuring) Modifier else Modifier.testTag("group.row:${group.id}")),
        verticalAlignment = Alignment.CenterVertically) {
        NativeUnreadGutter(unread, gap = 3.dp)
        IconButton(enabled = !measuring, onClick = { if (!blocked) toggle() }, modifier = Modifier.size(32.dp).semantics {
            contentDescription = "${if (expanded) "Collapse" else "Expand"} ${group.name}"
        }) { Icon(painterResource(if (expanded) R.drawable.ic_workspace_chevron_down else R.drawable.ic_workspace_chevron_right),
            null, Modifier.size(16.dp), tint = nativeMuted) }
        Row(Modifier.weight(1f).then(if (measuring) Modifier else if (handlesHold) Modifier.combinedClickable(
            onClick = { if (!blocked) open() },
            onLongClick = { showMenu() })
            else if (canOpen) Modifier.clickable { if (!blocked) open() } else Modifier)
            .semantics(mergeDescendants = true) {
                selected = highlighted
                if (!measuring && hasMenu) onLongClick("Show group actions") { showMenu() }
                customActions = if (measuring) emptyList() else moveActions + if (hasMenu) listOf(CustomAccessibilityAction("Show group actions") { showMenu() }) else emptyList()
                if (canOpen) contentDescription = "Open ${group.name}"
                stateDescription = listOfNotNull("Pinned".takeIf { group.isPinned },
                    unread.accessibilityLabel.takeIf { it.isNotEmpty() }).joinToString(", ")
            }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(nativeWorkspaceGroupIcon(group.iconSymbol)), null, Modifier.size(15.dp), tint = nativeMuted)
            Text(group.name, Modifier.weight(1f, fill = false), color = Color.White,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (group.isPinned) Icon(painterResource(R.drawable.ic_workspace_pin_fill), null,
                Modifier.size(12.dp), tint = nativeMuted)
        }
    }
}
