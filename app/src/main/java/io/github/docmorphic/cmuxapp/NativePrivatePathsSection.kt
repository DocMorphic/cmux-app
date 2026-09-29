package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NativePrivatePathsSettings(runtime: NativeIrohRuntime?, state: NativeComputersState) {
    val team = state.account
    if (runtime == null || team == null || !state.ready) return
    key(runtime, team) {
        NativePrivatePathsSection(state.computers,
            load = { runtime.privatePaths(team) },
            change = { action -> runtime.privatePaths(team, action) })
    }
}

@Composable
internal fun NativePrivatePathsSection(computers: List<IrohV2Computer>,
    load: suspend () -> List<NativePrivatePath>,
    change: suspend ((NativePrivatePathStore) -> Unit) -> List<NativePrivatePath>) {
    var paths by remember { mutableStateOf(emptyList<NativePrivatePath>()) }
    var pending by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<NativePrivatePath?>(null) }
    var removing by remember { mutableStateOf<NativePrivatePath?>(null) }
    var resetting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun apply(action: (NativePrivatePathStore) -> Unit) {
        if (pending) return
        pending = true; error = null
        scope.launch {
            try { paths = change(action); editor = null; removing = null; resetting = false }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "Could not save private addresses. Try again." }
            finally { pending = false }
        }
    }
    LaunchedEffect(Unit) {
        try { paths = load() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "Could not load private addresses. Reopen Settings to retry." }
        finally { pending = false }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text("PRIVATE ADDRESSES", color = Color(0xFF9B9FA8), fontSize = 11.sp)
        Text("Use an IP address and UDP port to reach a Mac over your LAN or private VPN. Changes apply on the next connection.",
            fontSize = 13.sp, color = Color(0xFF9B9FA8))
        paths.forEach { path ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(path.name + if (path.buildTag != "default") " (${path.buildTag})" else "")
                Text(if (path.enabled) "Enabled · ${path.addresses.size} addresses" else "Disabled", fontSize = 12.sp)
                Row {
                    TextButton(enabled = !pending, onClick = { error = null; editor = path }) { Text("Edit ${path.name}") }
                    TextButton(enabled = !pending, onClick = { error = null; removing = path }) { Text("Remove ${path.name}") }
                }
            }
        }
        computers.filter { mac -> paths.none { it.deviceId == mac.deviceId && it.buildTag == mac.buildTag } }.forEach { mac ->
            TextButton(enabled = !pending, onClick = {
                error = null
                editor = NativePrivatePath(mac.deviceId, mac.buildTag, mac.name, emptyList())
            }) { Text("Add addresses for ${mac.name}" + if (mac.buildTag != "default") " (${mac.buildTag})" else "") }
        }
        if (paths.isEmpty() && computers.isEmpty()) Text("No Macs available in this team.", fontSize = 13.sp)
        if (paths.any { it.enabled }) TextButton(enabled = !pending, onClick = { error = null; resetting = true }) { Text("Reset Private Addresses") }
        error?.let { Text(it, color = Color(0xFFFFC170), fontSize = 13.sp) }
        Text("Addresses stay on this Android device and are not included in connection reports.", fontSize = 12.sp, color = Color(0xFF9B9FA8))
    }
    editor?.let { path ->
        key(path.deviceId, path.buildTag) {
            NativePrivatePathEditor(path, pending, error, onDismiss = { if (!pending) editor = null },
                onSave = { value -> apply { it.upsert(value) } })
        }
    }
    removing?.let { path -> AlertDialog(onDismissRequest = { if (!pending) removing = null },
        title = { Text("Remove Private Addresses?") }, text = { Column { Text("Remove saved addresses for ${path.name} from this device?"); error?.let { Text(it, color = Color(0xFFFFC170)) } } },
        confirmButton = { TextButton(enabled = !pending, onClick = { apply { it.remove(path.deviceId, path.buildTag) } }) { Text("Remove") } },
        dismissButton = { TextButton(enabled = !pending, onClick = { removing = null }) { Text("Cancel") } }) }
    if (resetting) AlertDialog(onDismissRequest = { if (!pending) resetting = false },
        title = { Text("Reset Private Addresses?") }, text = { Column { Text("Disable all private addresses in this team. Saved addresses are kept so you can enable them again."); error?.let { Text(it, color = Color(0xFFFFC170)) } } },
        confirmButton = { TextButton(enabled = !pending, onClick = { apply { it.reset() } }) { Text("Reset") } },
        dismissButton = { TextButton(enabled = !pending, onClick = { resetting = false }) { Text("Cancel") } })
}

@Composable
private fun NativePrivatePathEditor(path: NativePrivatePath, pending: Boolean, error: String?,
    onDismiss: () -> Unit, onSave: (NativePrivatePath) -> Unit) {
    var text by remember { mutableStateOf(path.addresses.joinToString("\n")) }
    var enabled by remember { mutableStateOf(path.enabled) }
    val parsed = remember(text) { runCatching { NativePrivateAddress.lines(text) }.getOrNull() }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (path.addresses.isEmpty()) "Add Private Addresses" else "Edit Private Addresses") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(path.name + " (${path.buildTag})")
            OutlinedTextField(value = text, onValueChange = { if (it.length <= 1024) text = it },
                enabled = !pending, label = { Text("IP Addresses and Ports") }, minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                isError = text.isNotBlank() && parsed == null)
            Text("Enter up to 8 addresses, one per line: 192.168.1.5:58470 or [fd00::5]:58470. Use the Mac’s Iroh UDP port.", fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Use These Addresses", Modifier.weight(1f))
                Switch(modifier = Modifier.semantics { contentDescription = "Use These Addresses" }, checked = enabled, onCheckedChange = { enabled = it }, enabled = !pending)
            }
            error?.let { Text(it, color = Color(0xFFFFC170)) }
        } },
        confirmButton = { TextButton(enabled = parsed != null && !pending,
            onClick = { parsed?.let { onSave(path.copy(addresses = it, enabled = enabled)) } }) { Text(if (pending) "Saving…" else "Save") } },
        dismissButton = { TextButton(enabled = !pending, onClick = onDismiss) { Text("Cancel") } })
}
