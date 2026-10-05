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
        DropdownMenu(open, onDismissRequest = { onOpen(false) }, modifier = Modifier.width(320.dp)) {
            // The iOS view-options card updates the list live and stays open.
            fun select(value: NativeWorkspaceFilter) { onChange(value) }
            sortMode?.let { mode ->
                NativeWorkspaceSortTiles(mode, onSort)
                if (mode == NativeWorkspaceSortMode.PRIORITY && onOrder != null) {
                    HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                    DropdownMenuItem(text = { Text("Edit Computer Order") },
                        onClick = { onOpen(false); onOrder() })
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 6.dp)
            }
            DropdownMenuItem(text = { Text("All workspaces") }, leadingIcon = { Text(if (!filter.unread) "✓" else " ") },
                modifier = Modifier.semantics { selected = !filter.unread }, onClick = { select(filter.copy(unread = false)) })
            DropdownMenuItem(text = { Text("Unread") }, leadingIcon = { Text(if (filter.unread) "✓" else " ") },
                modifier = Modifier.semantics { selected = filter.unread }, onClick = { select(filter.copy(unread = true)) })
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
