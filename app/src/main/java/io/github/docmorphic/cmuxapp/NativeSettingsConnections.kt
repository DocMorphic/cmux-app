package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

internal data class NativeSettingsConnectionRow(val mac: NativeCredentialStore.PairedMac,
    val connection: NativeComputerConnection) {
    val identity get() = NativeMacIdentity(canonicalMacDeviceId(mac.deviceId), mac.instanceTag)
}

/** Live app instances only. Discovery, filters and ambiguous saved identities cannot create rows. */
internal fun nativeSettingsConnectionRows(macs: List<NativeCredentialStore.PairedMac>,
    connections: Map<NativeMacIdentity, NativeComputerConnection>): List<NativeSettingsConnectionRow> =
    macs.groupBy { NativeMacIdentity(canonicalMacDeviceId(it.deviceId), it.instanceTag) }.mapNotNull { (id, candidates) ->
        val mac = candidates.singleOrNull() ?: return@mapNotNull null
        val connection = (connections[NativeMacIdentity(mac.deviceId, mac.instanceTag)] ?: connections[id])
            ?.takeIf { it.availability == NativeFeedAvailability.CONNECTED }
            ?: return@mapNotNull null
        NativeSettingsConnectionRow(mac, connection)
    }

@Composable
internal fun NativeSettingsConnections(owner: NativeComputerMenuOwner, rows: List<NativeSettingsConnectionRow>,
    appearances: NativeMacAppearances, onDetails: (NativeCredentialStore.PairedMac) -> Unit,
    onComputers: () -> Unit, fallbackName: String? = null, fallbackTransport: MobileConnectionTransport? = null) {
    val currentOwner by rememberUpdatedState(owner)
    val currentRows by rememberUpdatedState(rows)
    val currentDetails by rememberUpdatedState(onDetails)
    var admitted by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { admitted = false } }
    Text("CONNECTION", Modifier.padding(horizontal = 22.dp, vertical = 10.dp).semantics { heading() },
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    rows.forEach { row -> key(owner, row.identity) {
        val name = appearances.name(row.mac)
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .testTag("settings.connection:${row.identity.deviceId}:${row.identity.buildTag.orEmpty()}")
            .clickable(role = Role.Button) {
                if (admitted && currentOwner == owner) {
                    val latest = currentRows.singleOrNull { it.identity == row.identity }
                    if (latest != null && NativeComputerMenuPairing.isCurrent(row.mac, listOf(latest.mac)))
                        currentDetails(latest.mac)
                }
            }.padding(horizontal = 22.dp, vertical = 12.dp)) {
            val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name)
                    if (stacked) row.connection.transport?.let {
                        Text(it.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!stacked) row.connection.transport?.let {
                    Text(it.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", Modifier.clearAndSetSemantics {}, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } }
    if (rows.isEmpty() && !fallbackName.isNullOrBlank()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
            Text("Connection", Modifier.weight(1f)); Text(fallbackName, Modifier.weight(1f))
        }
        fallbackTransport?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
                Text("Active Transport", Modifier.weight(1f)); Text(it.label)
            }
        }
    }
    TextButton(onClick = onComputers, modifier = Modifier.padding(horizontal = 14.dp).testTag("settings.computers")) {
        Text("All Computers")
    }
    Text("Each computer connects using its own method, shown on the right. Select one to inspect or configure it.",
        Modifier.padding(horizontal = 22.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
