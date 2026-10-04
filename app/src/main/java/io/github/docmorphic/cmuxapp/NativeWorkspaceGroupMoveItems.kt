package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Uses the same anchored popup so the parent hold/drag recognizer keeps its pointer. */
@Composable
internal fun NativeWorkspaceGroupMoveItems(
    menu: NativeWorkspaceGroupMoveMenu, onBack: () -> Unit, onMove: (String?) -> Unit
) {
    DropdownMenuItem(text = { Text("Move to Group") }, onClick = onBack,
        modifier = Modifier.semantics { contentDescription = "Back to workspace actions" },
        leadingIcon = { Icon(painterResource(R.drawable.ic_task_back), null, Modifier.size(20.dp)) })
    HorizontalDivider()
    menu.entries.forEach { entry ->
        DropdownMenuItem(text = { Text(entry.group.name) }, enabled = entry.isEnabled,
            modifier = Modifier.semantics { if (entry.isCurrent) stateDescription = "Current group" },
            leadingIcon = entry.group.iconSymbol?.let { symbol ->
                { Icon(painterResource(nativeWorkspaceGroupIcon(symbol)), null, Modifier.size(20.dp)) }
            },
            trailingIcon = if (entry.isCurrent) ({
                Icon(painterResource(R.drawable.ic_menu_check), null, Modifier.size(20.dp))
            }) else null,
            onClick = { onMove(entry.group.id) })
    }
    if (menu.canRemoveFromGroup) {
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Remove from Group") }, onClick = { onMove(null) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_workspace_folder_minus), null, Modifier.size(20.dp)) })
    }
}
