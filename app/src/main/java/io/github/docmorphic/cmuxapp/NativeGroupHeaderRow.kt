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
    onAction: (String, String?) -> Unit,
    handlesHold: Boolean = false, isSelected: Boolean = false
) {
    val admitted by rememberUpdatedState(LocalWorkspaceRowAdmission.current)
    val latestOpen by rememberUpdatedState(onOpen)
    val latestToggle by rememberUpdatedState(onToggle)
    val latestCreate by rememberUpdatedState(onCreate)
    val latestAction by rememberUpdatedState(onAction)
    fun open() { if (admitted()) latestOpen?.invoke() }
    fun toggle() { if (admitted()) latestToggle() }
    val latestEditable by rememberUpdatedState(canEdit)
    val latestCreatable by rememberUpdatedState(canCreate && creationEnabled)
    val latestGroup by rememberUpdatedState(group)
    fun create() { if (admitted() && latestCreatable) latestCreate() }
    fun dispatchAction(verb: String, value: String?) {
        if (admitted() && latestEditable && (verb != "ungroup" || !latestGroup.isPinned)) latestAction(verb, value)
    }

    val highlighted = isSelected && LocalWorkspaceShellChrome.current.split
    val menu = rememberWorkspaceContextMenu(group.id)
    val hasMenu = canEdit || canCreate
    val latestHasMenu by rememberUpdatedState(hasMenu)
    fun showMenu(): Boolean {
        if (!admitted() || !latestHasMenu) return false
        menu.expanded = true
        return true
    }
    val menuExpanded = menu.expanded && hasMenu
    val moveActions = LocalWorkspaceMoveActions.current
    var renaming by remember(menu, group.id) { mutableStateOf(false) }
    var destructive by remember(menu, group.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(menu, menu.expanded, canEdit, canCreate, group.isPinned) {
        if (!hasMenu) menu.expanded = false
        if (!canEdit) renaming = false
        if (!canEdit || (destructive == "ungroup" && group.isPinned)) destructive = null
    }
    val rowPresent = admitted()
    LaunchedEffect(rowPresent) { if (!rowPresent) { menu.expanded = false; renaming = false; destructive = null } }
    var name by remember(menu, group.id) { mutableStateOf(group.name) }
    Box {
    WorkspaceMeasuredContent(GroupRowVisual(group, expanded, unread, highlighted), LocalWorkspaceGeometryHeld.current) { shown, measuring ->
        NativeGroupHeaderBody(shown, measuring, handlesHold, onOpen != null, hasMenu,
            menuExpanded || menu.held, moveActions, ::open, ::toggle, ::showMenu)
    }
    DropdownMenu(menuExpanded, onDismissRequest = { if (!menu.held) menu.expanded = false },
        properties = PopupProperties(focusable = !menu.held)) {
        if (canEdit) {
            DropdownMenuItem(text = { Text(if (group.isPinned) "Unpin Group" else "Pin Group") },
                leadingIcon = { WorkspaceActionIcon(if (group.isPinned) R.drawable.ic_workspace_unpin else R.drawable.ic_workspace_pin) },
                onClick = { menu.expanded = false; dispatchAction(if (group.isPinned) "unpin" else "pin", null) })
            DropdownMenuItem(text = { Text("Rename Group") },
                leadingIcon = { WorkspaceActionIcon(R.drawable.ic_workspace_rename) },
                onClick = { menu.expanded = false; if (admitted() && latestEditable) { name = latestGroup.name; renaming = true } })
        }
        if (canCreate) DropdownMenuItem(text = { Text("New Workspace in Group") },
            enabled = creationEnabled, leadingIcon = { WorkspaceActionIcon(R.drawable.ic_workspace_plus) },
            onClick = { menu.expanded = false; create() })
        if (canEdit) {
            HorizontalDivider()
            if (!group.isPinned) DropdownMenuItem(text = { Text("Ungroup (Keep Workspaces)") },
                colors = MenuDefaults.itemColors(textColor = destructiveTint, leadingIconColor = destructiveTint),
                leadingIcon = { WorkspaceActionIcon(R.drawable.ic_workspace_ungroup) },
                onClick = { menu.expanded = false; if (admitted() && latestEditable && !latestGroup.isPinned) destructive = "ungroup" })
            DropdownMenuItem(text = { Text("Delete Group (Close Workspaces)") },
                colors = MenuDefaults.itemColors(textColor = destructiveTint, leadingIconColor = destructiveTint),
                leadingIcon = { WorkspaceActionIcon(R.drawable.ic_workspace_delete) },
                onClick = { menu.expanded = false; if (admitted() && latestEditable) destructive = "delete" })
        }
    }
    }
    if (renaming && canEdit) AlertDialog(
        onDismissRequest = { renaming = false },
        title = { Text("Rename Group") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            renaming = false; dispatchAction("rename", name)
        }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
    )
    val action = destructive?.takeIf { canEdit && (it != "ungroup" || !group.isPinned) }
    if (action != null) AlertDialog(
        onDismissRequest = { destructive = null },
        title = { Text(if (action == "ungroup") "Ungroup Group?" else "Delete Group?") },
        text = { Text(if (action == "ungroup") "This will dissolve the group on your Mac and keep its workspaces."
            else "This will delete the group and close its workspaces on your Mac.") },
        confirmButton = { TextButton(onClick = { destructive = null; dispatchAction(action, null) }) {
            Text(if (action == "ungroup") "Ungroup" else "Delete Group", color = destructiveTint)
        } },
        dismissButton = { TextButton(onClick = { destructive = null }) { Text("Cancel") } }
    )
}
