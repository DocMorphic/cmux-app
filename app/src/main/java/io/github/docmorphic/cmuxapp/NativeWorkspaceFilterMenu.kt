package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
internal fun NativeWorkspaceFilterMenu(filter: NativeWorkspaceFilter, machines: List<NativeWorkspaceFilterMachine>,
    open: Boolean, onOpen: (Boolean) -> Unit, onChange: (NativeWorkspaceFilter) -> Unit,
    sortMode: NativeWorkspaceSortMode? = null, onSort: (NativeWorkspaceSortMode) -> Unit = {}, onOrder: (() -> Unit)? = null) {
    Box {
        IconButton(onClick = { onOpen(true) }, modifier = Modifier.semantics {
            contentDescription = "Filter workspaces"
            stateDescription = if (filter.active) "Filter active" else "All workspaces"
        }) {
            Icon(painterResource(if (filter.active) R.drawable.ic_feed_filter_active else R.drawable.ic_feed_filter),
                null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, onDismissRequest = { onOpen(false) }) {
            fun select(value: NativeWorkspaceFilter) { onChange(value); onOpen(false) }
            DropdownMenuItem(text = { Text("All workspaces") }, leadingIcon = { Text(if (!filter.unread) "✓" else " ") },
                modifier = Modifier.semantics { selected = !filter.unread }, onClick = { select(filter.copy(unread = false)) })
            DropdownMenuItem(text = { Text("Unread") }, leadingIcon = { Text(if (filter.unread) "✓" else " ") },
                modifier = Modifier.semantics { selected = filter.unread }, onClick = { select(filter.copy(unread = true)) })
            sortMode?.let { mode ->
                HorizontalDivider()
                NativeWorkspaceSortMode.entries.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.title) }, leadingIcon = { Text(if (mode == choice) "✓" else " ") },
                        modifier = Modifier.testTag("workspace.sort.${choice.raw}").semantics { selected = mode == choice },
                        onClick = { onOpen(false); onSort(choice) })
                }
                if (mode == NativeWorkspaceSortMode.PRIORITY && onOrder != null)
                    DropdownMenuItem(text = { Text("Edit Computer Order") }, onClick = { onOpen(false); onOrder() })
            }
            if (machines.size > 1) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("All Machines") }, leadingIcon = { Text(if (filter.machines.isEmpty()) "✓" else " ") },
                    modifier = Modifier.semantics { selected = filter.machines.isEmpty() },
                    onClick = { select(filter.copy(machines = emptySet())) })
                machines.forEach { machine ->
                    DropdownMenuItem(text = { Column {
                        Text(machine.name)
                        machine.buildLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    } }, leadingIcon = { Text(if (machine.id in filter.machines) "✓" else " ") },
                        modifier = Modifier.testTag("workspace.filter.machine:${machine.id}").semantics { selected = machine.id in filter.machines },
                        onClick = { select(filter.toggle(machine.id)) })
                }
            }
        }
    }
}
