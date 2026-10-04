package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Snapshot the displayed choices. A refresh may revoke a choice, never substitute a new target. */
@Composable
internal fun RoutedSidebarCreateMenu(values: List<RoutedSidebarCreateComputer>, selection: String?,
    busy: Boolean, onCreate: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val opening = remember(open) { values.takeIf { open }.orEmpty() }
    val openedSelection = remember(open) { selection }
    var selectedSsh by remember(open) { mutableStateOf<String?>(null) }
    val currentValues by rememberUpdatedState(values)
    val currentBusy by rememberUpdatedState(busy)
    val currentSelection by rememberUpdatedState(selection)
    fun available(key: String) = !currentBusy && openedSelection == currentSelection &&
        currentValues.flatMap { it.options }.any { it.key == key && it.unavailableReason == null }
    fun create(key: String) { if (available(key)) { open = false; onCreate(key) } }
    LaunchedEffect(selection, busy, values) {
        if (openedSelection != selection || busy || values.none { it.enabled }) open = false
    }
    val single = values.singleOrNull()?.takeIf { it.options.size == 1 && it.options.single().kind == null }
    Box {
        Box(Modifier.size(48.dp).clip(CircleShape).semantics { contentDescription = "New Workspace" }
            .combinedClickable(enabled = !busy && values.any { it.enabled }, role = Role.Button,
                onLongClickLabel = if (single != null) "Workspace creation options" else null,
                onLongClick = if (single != null) ({ open = true }) else null,
                onClick = {
                    if (!busy) {
                        if (single != null) single.options.single().takeIf { it.unavailableReason == null }?.let { onCreate(it.key) }
                        else open = true
                    }
                }), contentAlignment = Alignment.Center) {
            Text("+", fontSize = 25.sp, color = if (!busy && values.any { it.enabled }) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        }
        DropdownMenu(open && openedSelection == selection, onDismissRequest = { open = false }) {
            val multiple = opening.size > 1
            val ssh = (if (multiple) opening.singleOrNull { it.key == selectedSsh } else opening.singleOrNull())
                ?.takeIf { it.options.all { option -> option.kind != null } }
            if (ssh != null) {
                if (multiple) DropdownMenuItem(text = { Text("‹  ${ssh.name}") }, onClick = { selectedSsh = null })
                SshWorkspaceKindMenuItems(ssh.options.map { SshWorkspaceKindOption(checkNotNull(it.kind), it.unavailableReason) },
                    !busy, canCreate = { kind -> ssh.options.singleOrNull { it.kind == kind }?.let { available(it.key) } == true },
                    onCreate = { kind -> ssh.options.singleOrNull { it.kind == kind }?.let { create(it.key) } })
            } else {
                if (multiple) DropdownMenuItem(text = { Text("New Workspace") }, enabled = false, onClick = {})
                opening.forEach { target ->
                    val availability = currentValues.singleOrNull { it.key == target.key }?.availability ?: NativeFeedAvailability.OFFLINE
                    DropdownMenuItem(text = { Column {
                        Text(if (multiple) target.name else "New Workspace")
                        target.build?.takeIf { multiple }?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                        if (availability != NativeFeedAvailability.CONNECTED)
                            Text(NativeComputerConnection(availability).phrase, style = MaterialTheme.typography.labelMedium)
                    } }, enabled = target.options.any { available(it.key) },
                        trailingIcon = { NativeComputerStatusDot(NativeComputerConnection(availability), NativeComputerPresence(), reconnect = false) },
                        onClick = {
                            if (target.options.size == 1 && target.options.single().kind == null) target.options.singleOrNull()?.let { create(it.key) }
                            else selectedSsh = target.key
                        })
                }
            }
        }
    }
}
