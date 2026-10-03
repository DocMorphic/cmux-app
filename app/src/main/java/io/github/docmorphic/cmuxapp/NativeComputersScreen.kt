package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A management destination. Mac row callbacks open details; they never select a workspace. */
@Composable
internal fun NativeComputersRoute(ssh: NativeSshRuntime?, onDone: () -> Unit, onPair: () -> Unit,
    computers: @Composable () -> Unit) {
    val state = ssh?.state?.collectAsState()?.value
    val session = state?.resource?.takeIf { it.admitted() }
    if (session != null) {
        key(state.login) { SshComputersScreen(session, onDone, computers, onPair) }
    } else {
        BackHandler(onBack = onDone)
        Column(Modifier.fillMaxSize().testTag("computers.management")) {
            NativeComputersHeader(onDone, onPair, null)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp)) {
                computers()
                if (ssh != null) {
                    Text("SSH", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 14.dp))
                    when {
                        state?.failed == true -> {
                            Text("Could not open saved SSH computers. Your saved data has been retained.")
                            TextButton(onClick = ssh::retry) { Text("Try again") }
                        }
                        state?.login == null -> Text("Sign in to manage SSH computers.")
                        else -> CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
internal fun NativeComputersHeader(onDone: () -> Unit, onPair: () -> Unit, onAddSsh: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "Add Computer" }) { Text("+") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Pair Mac…") }, onClick = { menu = false; onPair() })
                DropdownMenuItem(text = { Text("Add SSH Computer…") }, enabled = onAddSsh != null,
                    onClick = { menu = false; onAddSsh?.invoke() })
            }
        }
        Text("Computers", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onDone, modifier = Modifier.testTag("computers.done")) { Text("Done") }
    }
}

@Composable
internal fun NativeManagedComputerRows(rows: List<NativeComputerListRow>, appearances: NativeMacAppearances,
    colors: Map<NativeMacIdentity, Int>, connections: Map<NativeMacIdentity, NativeComputerConnection>,
    presence: NativeMacPresenceState, onDetails: (NativeCredentialStore.PairedMac) -> Unit, onPair: () -> Unit,
    hiddenOrigins: Set<String> = emptySet(),
    onVisibility: ((NativeCredentialStore.PairedMac, Boolean) -> Unit)? = null) {
    if (rows.isEmpty()) {
        Text("No Computers", style = MaterialTheme.typography.titleMedium)
        Text("Pair a Mac to manage its connection settings here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    NativeComputerMethodSections(rows.filterNot { NativeComputerVisibility.isHidden(hiddenOrigins, it.mac) }) { row ->
        val mac = row.mac
        val connection = connections[NativeMacIdentity(mac.deviceId, mac.instanceTag)] ?: NativeComputerConnection()
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { onDetails(mac) }
                .semantics { contentDescription = "Computer details: ${row.name}" }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                NativeMacAvatar(appearances.get(mac), mac.colorIdentity.colorSeed, index = colors[mac.colorIdentity])
                NativeComputerRowLabel(row.name, presence.buildLabel(mac), connection, row.presence,
                    reconnect = false, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    routeDescription = row.route.endpoint, olderPairing = row.olderPairing,
                    identity = NativeMacIdentity(mac.deviceId, mac.instanceTag))
                NativeMacAwakeIndicator(connection)
                NativeComputerStatusDot(connection, row.presence, reconnect = false)
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                onVisibility?.let { change -> NativeComputerVisibilitySwitch(mac, row.name, true) { change(mac, it) } }
            }
        }
    }
    if (onVisibility != null) NativeHiddenComputerRows(
        rows.map { it.mac }.filter { NativeComputerVisibility.isHidden(hiddenOrigins, it) }, appearances, colors, onVisibility)
    TextButton(onClick = onPair) { Text("Add Computer") }
    Text("Each computer connects using the method set in its own configuration. Turning a computer off hides its workspaces on this phone; it stays signed in to your account.",
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun NativeLegacyComputerDetails(row: NativeComputerListRow, onBack: () -> Unit, onReconnect: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        TextButton(onClick = onBack) { Text("‹  Computers") }
        Text(row.name, style = MaterialTheme.typography.headlineSmall)
        Text("Legacy TCP pairing", Modifier.padding(vertical = 12.dp))
        Text(row.route.endpoint ?: "No saved endpoint")
        Text("Reconnect to this computer to use its connection controls in Settings.", Modifier.padding(vertical = 12.dp))
        Button(onClick = onReconnect) { Text("Reconnect") }
    }
}


@Composable
internal fun NativeComputerVisibilitySwitch(mac: NativeCredentialStore.PairedMac, name: String,
    visible: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Switch(checked = visible, onCheckedChange = onChange, enabled = enabled,
        modifier = Modifier.testTag("computer.visibility." + mac.origin)
            .semantics { contentDescription = "Show $name on this phone" })
}

@Composable
internal fun NativeHiddenComputerRows(macs: List<NativeCredentialStore.PairedMac>, appearances: NativeMacAppearances,
    colors: Map<NativeMacIdentity, Int>, onVisibility: (NativeCredentialStore.PairedMac, Boolean) -> Unit) {
    if (macs.isEmpty()) return
    Text("Hidden Computers", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 14.dp))
    macs.sortedBy { appearances.name(it).lowercase(java.util.Locale.ROOT) }.forEach { mac -> key(mac.origin) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                NativeMacAvatar(appearances.get(mac), mac.colorIdentity.colorSeed, index = colors[mac.colorIdentity])
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(appearances.name(mac), fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    NativeMacPresenceState().buildLabel(mac)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    NativeMacUpdateGuidance(NativeMacIdentity(mac.deviceId, mac.instanceTag))
                }
                NativeComputerVisibilitySwitch(mac, appearances.name(mac), false) { onVisibility(mac, it) }
            }
        }
    } }
}
