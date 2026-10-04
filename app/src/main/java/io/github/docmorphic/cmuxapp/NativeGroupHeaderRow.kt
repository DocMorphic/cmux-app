package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.clickable
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
private val destructiveTint = Color(0xFFFF9999)

@Composable
internal fun NativeGroupHeaderRow(
    group: NativeGroup,
    expanded: Boolean,
    unread: NativeWorkspaceUnread,
    onOpen: (() -> Unit)?,
    canEdit: Boolean,
    canCreate: Boolean = false,
    creationEnabled: Boolean = true,
    onCreate: () -> Unit = {},
    onToggle: () -> Unit,
    onAction: (String, String?) -> Unit
) {
    val menu = rememberWorkspaceContextMenu(group.id)
    val hasMenu = canEdit || canCreate
    val menuExpanded = menu.expanded && hasMenu
    val moveActions = LocalWorkspaceMoveActions.current
    var renaming by remember(menu, group.id) { mutableStateOf(false) }
    var destructive by remember(menu, group.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(menu, menu.expanded, canEdit, canCreate, group.isPinned) {
        if (!hasMenu) menu.expanded = false
        if (!canEdit) renaming = false
        if (!canEdit || (destructive == "ungroup" && group.isPinned)) destructive = null
    }
    var name by remember(menu, group.id) { mutableStateOf(group.name) }
    Box {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NativeUnreadGutter(unread, gap = 3.dp)
        IconButton(onClick = { if (!menuExpanded && !menu.held) onToggle() }, modifier = Modifier.size(32.dp).semantics {
            contentDescription = "${if (expanded) "Collapse" else "Expand"} ${group.name}"
        }) { Icon(painterResource(if (expanded) R.drawable.ic_workspace_chevron_down else R.drawable.ic_workspace_chevron_right),
            null, Modifier.size(16.dp), tint = nativeMuted) }
        Row(Modifier.weight(1f).then(if (onOpen != null) Modifier.clickable { if (!menuExpanded && !menu.held) onOpen() } else Modifier)
            .semantics(mergeDescendants = true) {
                if (hasMenu) onLongClick("Show group actions") { menu.expanded = true; true }
                customActions = moveActions + if (hasMenu) listOf(CustomAccessibilityAction("Show group actions") { menu.expanded = true; true }) else emptyList()
                if (onOpen != null) contentDescription = "Open ${group.name}"
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
    DropdownMenu(menuExpanded, onDismissRequest = { if (!menu.held) menu.expanded = false },
        properties = PopupProperties(focusable = !menu.held)) {
        if (canEdit) {
            DropdownMenuItem(text = { Text(if (group.isPinned) "Unpin Group" else "Pin Group") },
                leadingIcon = { WorkspaceActionIcon(if (group.isPinned) R.drawable.ic_workspace_unpin else R.drawable.ic_workspace_pin) },
                onClick = { menu.expanded = false; onAction(if (group.isPinned) "unpin" else "pin", null) })
            DropdownMenuItem(text = { Text("Rename Group") },
                leadingIcon = { WorkspaceActionIcon(R.drawable.ic_workspace_rename) },
                onClick = { menu.expanded = false; name = group.name; renaming = true })
        }
        if (canCreate) DropdownMenuItem(text = { Text("New Workspace in Group") },
            enabled = creationEnabled, leadingIcon = { WorkspaceActionIcon(R.drawable.ic_workspace_plus) },
            onClick = { menu.expanded = false; onCreate() })
        if (canEdit) {
            HorizontalDivider()
            if (!group.isPinned) DropdownMenuItem(text = { Text("Ungroup (Keep Workspaces)") },
                colors = MenuDefaults.itemColors(textColor = destructiveTint, leadingIconColor = destructiveTint),
                leadingIcon = { WorkspaceActionIcon(R.drawable.ic_workspace_ungroup) },
                onClick = { menu.expanded = false; destructive = "ungroup" })
            DropdownMenuItem(text = { Text("Delete Group (Close Workspaces)") },
                colors = MenuDefaults.itemColors(textColor = destructiveTint, leadingIconColor = destructiveTint),
                leadingIcon = { WorkspaceActionIcon(R.drawable.ic_workspace_delete) },
                onClick = { menu.expanded = false; destructive = "delete" })
        }
    }
    }
    if (renaming && canEdit) AlertDialog(
        onDismissRequest = { renaming = false },
        title = { Text("Rename Group") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            renaming = false; onAction("rename", name)
        }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
    )
    val action = destructive?.takeIf { canEdit && (it != "ungroup" || !group.isPinned) }
    if (action != null) AlertDialog(
        onDismissRequest = { destructive = null },
        title = { Text(if (action == "ungroup") "Ungroup Group?" else "Delete Group?") },
        text = { Text(if (action == "ungroup") "This will dissolve the group on your Mac and keep its workspaces."
            else "This will delete the group and close its workspaces on your Mac.") },
        confirmButton = { TextButton(onClick = { destructive = null; onAction(action, null) }) {
            Text(if (action == "ungroup") "Ungroup" else "Delete Group", color = destructiveTint)
        } },
        dismissButton = { TextButton(onClick = { destructive = null }) { Text("Cancel") } }
    )
}
