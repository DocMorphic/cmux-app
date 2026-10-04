package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp

private data class WorkspaceCreateMenuRow(val mac: NativeCredentialStore.PairedMac, val name: String,
    val buildLabel: String?, val connection: NativeComputerConnection)
private data class WorkspaceCreateMenuOpening(val owner: NativeComputerMenuOwner, val selection: String?,
    val rows: List<WorkspaceCreateMenuRow>, val create: (NativeCredentialStore.PairedMac) -> Unit,
    val group: (() -> Unit)?)

/** Capture the displayed targets once; never let a later account/filter substitute a target under a tap. */
@Composable
internal fun NativeWorkspaceCreateMenu(macs: List<NativeCredentialStore.PairedMac>,
    appearances: NativeMacAppearances, presence: NativeMacPresenceState,
    connections: Map<NativeMacIdentity, NativeComputerConnection>, open: Boolean, onOpen: (Boolean) -> Unit,
    owner: NativeComputerMenuOwner, selection: String?, busy: Boolean,
    isOwnerCurrent: (NativeComputerMenuOwner) -> Boolean, canCreate: (NativeCredentialStore.PairedMac) -> Boolean,
    onCreate: (NativeCredentialStore.PairedMac) -> Unit, onGroup: (() -> Unit)?) {
    val opening = remember(open) {
        if (!open) null else WorkspaceCreateMenuOpening(owner, selection, macs.map { mac ->
            WorkspaceCreateMenuRow(mac, appearances.name(mac), presence.buildLabel(mac),
                connections[NativeMacIdentity(mac.deviceId, mac.instanceTag)] ?: NativeComputerConnection())
        }, onCreate, onGroup)
    }
    val currentOwnerCheck by rememberUpdatedState(isOwnerCurrent)
    val currentCreateCheck by rememberUpdatedState(canCreate)
    val currentBusy by rememberUpdatedState(busy)
    val currentGroupAvailable by rememberUpdatedState(onGroup != null)
    fun admitted(menu: WorkspaceCreateMenuOpening) = menu.owner == owner && menu.selection == selection &&
        currentOwnerCheck(menu.owner) && !currentBusy
    LaunchedEffect(open, owner, selection) {
        if (opening != null && (opening.owner != owner || opening.selection != selection || !currentOwnerCheck(opening.owner)))
            onOpen(false)
    }
    Box {
        val single = macs.singleOrNull()
        val enabled = !busy && isOwnerCurrent(owner) && macs.any(canCreate)
        Box(Modifier.size(48.dp).clip(CircleShape).semantics { contentDescription = "New Workspace" }
            .combinedClickable(enabled = enabled, role = Role.Button,
                onLongClickLabel = if (single != null) "Workspace creation options" else null,
                onLongClick = if (single != null) ({ onOpen(true) }) else null,
                onClick = {
                    if (!busy && isOwnerCurrent(owner)) {
                        if (single != null) { if (canCreate(single)) onCreate(single) }
                        else onOpen(true)
                    }
                }), contentAlignment = Alignment.Center) {
            Text("+", fontSize = 25.sp,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        }
        DropdownMenu(open && opening?.owner == owner && opening.selection == selection,
            onDismissRequest = { onOpen(false) }) {
            opening?.let { menu ->
                val multiple = menu.rows.size > 1
                if (multiple) DropdownMenuItem(text = { Text("New Workspace") }, enabled = false, onClick = {})
                menu.rows.forEach { row ->
                    DropdownMenuItem(text = { Column {
                        Text(if (multiple) row.name else "New Workspace")
                        row.buildLabel?.takeIf { multiple }?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                        if (row.connection.availability != NativeFeedAvailability.CONNECTED)
                            Text(row.connection.phrase, style = MaterialTheme.typography.labelMedium)
                    } }, enabled = admitted(menu) && currentCreateCheck(row.mac),
                        modifier = Modifier.testTag("workspace.create.target:${row.mac.origin}"),
                        trailingIcon = { NativeComputerStatusDot(row.connection, NativeComputerPresence(), reconnect = false) }, onClick = {
                            onOpen(false)
                            if (admitted(menu) && currentCreateCheck(row.mac)) menu.create(row.mac)
                        })
                }
                if (menu.group != null) HorizontalDivider()
                if (menu.group != null && onGroup != null) DropdownMenuItem(text = { Text("New group") },
                    enabled = admitted(menu), onClick = {
                        onOpen(false)
                        if (admitted(menu) && currentGroupAvailable) menu.group()
                    })
            }
        }
    }
}
