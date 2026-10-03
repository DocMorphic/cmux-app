package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

internal data class NativeComputerMenuOwner(val login: String?, val team: NativeTeamScope?)

internal object NativeComputerMenuPairing {
    /** A name/appearance update is harmless; a replaced route or ambiguous pairing is not. */
    fun isCurrent(mac: NativeCredentialStore.PairedMac, saved: List<NativeCredentialStore.PairedMac>): Boolean =
        saved.singleOrNull { it.origin == mac.origin }?.let {
            it.code == mac.code && it.deviceId == mac.deviceId && it.instanceTag == mac.instanceTag &&
                it.accountUserId == mac.accountUserId && it.accountTeamId == mac.accountTeamId
        } == true
}

private data class ComputerMenuRow(val mac: NativeCredentialStore.PairedMac, val name: String,
    val selected: Boolean, val buildLabel: String?, val connection: NativeComputerConnection)
private data class ComputerMenuOpening(val owner: NativeComputerMenuOwner, val allSelected: Boolean,
    val rows: List<ComputerMenuRow>, val select: (NativeCredentialStore.PairedMac?) -> Unit, val pair: (() -> Unit)?)

@Composable
internal fun NativeComputerSelector(macs: List<NativeCredentialStore.PairedMac>, selected: NativeCredentialStore.PairedMac?,
    appearances: NativeMacAppearances, colorIndices: Map<NativeMacIdentity, Int>,
    connections: Map<NativeMacIdentity, NativeComputerConnection>, open: Boolean, onOpen: (Boolean) -> Unit,
    onSelect: (NativeCredentialStore.PairedMac?) -> Unit, pending: NativeCredentialStore.PairedMac?, onPair: (() -> Unit)?,
    owner: NativeComputerMenuOwner, isOwnerCurrent: (NativeComputerMenuOwner) -> Boolean,
    canSelect: (NativeCredentialStore.PairedMac) -> Boolean, presence: NativeMacPresenceState = NativeMacPresenceState()) {
    // Match iOS's deferred menu: capture presentation and callbacks once per opening.
    // Toolbar state remains live. Current authority is checked separately at every tap.
    val opening = remember(open) {
        if (!open) null else ComputerMenuOpening(owner, selected == null && pending == null,
            macs.map { mac -> ComputerMenuRow(mac, appearances.name(mac), (pending ?: selected)?.origin == mac.origin, presence.buildLabel(mac),
                connections[NativeMacIdentity(mac.deviceId, mac.instanceTag)] ?: NativeComputerConnection()) }, onSelect, onPair)
    }
    val currentOwner by rememberUpdatedState(owner)
    val currentOwnerCheck by rememberUpdatedState(isOwnerCurrent)
    val currentPairingCheck by rememberUpdatedState(canSelect)
    val dismiss by rememberUpdatedState(onOpen)
    fun admitted(menu: ComputerMenuOpening) = menu.owner == currentOwner && currentOwnerCheck(menu.owner)
    LaunchedEffect(open, owner) {
        if (open && opening != null && !admitted(opening)) dismiss(false)
    }
    Box {
        IconButton(onClick = { onOpen(true) }, modifier = Modifier.semantics {
            contentDescription = "Computer filter"
            stateDescription = pending?.let { "Connecting to ${appearances.name(it)}" }
                ?: selected?.let(appearances::name) ?: "All Computers"
        }) {
            if (pending != null) CircularProgressIndicator(Modifier.size(20.dp).testTag("computer.switch.progress"),
                strokeWidth = 2.dp, color = Color(0xFF76B9FF))
            else if (selected == null) Icon(painterResource(R.drawable.ic_feed_computer), null,
                tint = Color(0xFF9B9FA8), modifier = Modifier.size(22.dp))
            else NativeMacAvatar(appearances.get(selected), selected.colorIdentity.colorSeed, Modifier.size(28.dp), colorIndices[selected.colorIdentity])
        }
        DropdownMenu(open && opening?.owner == owner, onDismissRequest = { onOpen(false) }) {
            opening?.let { menu ->
                DropdownMenuItem(text = { Text("All Computers") }, onClick = {
                    dismiss(false)
                    if (admitted(menu)) menu.select(null)
                }, modifier = Modifier.semantics { this.selected = menu.allSelected },
                    leadingIcon = { Text(if (menu.allSelected) "✓" else " ") })
                menu.rows.forEach { row ->
                    DropdownMenuItem(text = { Column {
                        Text(row.name)
                        row.buildLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } }, onClick = {
                        dismiss(false)
                        if (admitted(menu) && currentPairingCheck(row.mac)) menu.select(row.mac)
                    }, modifier = Modifier.semantics { this.selected = row.selected },
                        leadingIcon = { Text(if (row.selected) "✓" else " ") },
                        trailingIcon = { NativeMacAwakeIndicator(row.connection) })
                }
                menu.pair?.let { pair ->
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Add Computer") }, leadingIcon = { Text("+") }, onClick = {
                        dismiss(false)
                        if (admitted(menu)) pair()
                    })
                }
            }
        }
    }
}
