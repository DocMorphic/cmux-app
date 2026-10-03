package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NativeMacConnectionSection(target: NativeComputerTarget, state: NativeMacConnectionPreferences,
    save: suspend ((NativeMacConnectionPreference) -> NativeMacConnectionPreference) -> Unit, retry: () -> Unit) {
    val preference = state.get(target)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<NativeDirectAddress?>(null) }
    var address by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    fun change(transform: (NativeMacConnectionPreference) -> NativeMacConnectionPreference, saved: () -> Unit = {}) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { save(transform); saved() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "Could not save connection settings. Check the address and your account, then retry."
            } finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
        Text("CONNECTION METHOD", color = Color(0xFF9B9FA8), fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Connection Method", Modifier.weight(1f))
            Box {
                TextButton(onClick = { menu = true }, enabled = !busy && !state.error,
                    modifier = Modifier.semantics { contentDescription = "Choose connection method" }) {
                    Text(when (preference.method) {
                        NativeMacConnectionMethod.IROH -> "Iroh ▾"
                        NativeMacConnectionMethod.TAILSCALE -> "Tailscale Only ▾"
                        NativeMacConnectionMethod.DIRECT -> "Direct ▾"
                    })
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf(NativeMacConnectionMethod.IROH to "Iroh", NativeMacConnectionMethod.TAILSCALE to "Tailscale Only",
                        NativeMacConnectionMethod.DIRECT to "Direct").forEach { (method, name) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            menu = false
                            if (method != preference.method) change({ it.copy(method = method) })
                        })
                    }
                }
            }
        }
        if (state.error) {
            Text("Could not read connection settings. Connections stay blocked until these settings can be loaded.", color = Color(0xFFFFBC70))
            TextButton(onClick = retry, enabled = !busy) { Text("Retry connection settings") }
        } else {
            Text(when (preference.method) {
                NativeMacConnectionMethod.DIRECT -> "Connects to this Mac’s encrypted Iroh identity using only the enabled addresses below. Relays and automatic address discovery are disabled."
                NativeMacConnectionMethod.TAILSCALE -> "Uses only this Mac’s authorized Tailscale connections. Keep Tailscale connected on this phone."
                NativeMacConnectionMethod.IROH -> "Connects automatically over an authenticated, end-to-end encrypted Iroh connection."
            }, color = Color(0xFF9B9FA8), fontSize = 13.sp)
        }
        if (preference.method == NativeMacConnectionMethod.DIRECT && !state.error) {
            Text("DIRECT ADDRESSES", Modifier.padding(top = 20.dp, bottom = 8.dp), color = Color(0xFF9B9FA8), fontSize = 11.sp)
            preference.addresses.forEach { entry -> key(entry.address) {
                var actions by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NativeDirectAddressToggle(entry.enabled, enabled = !busy, onCheckedChange = { enabled ->
                        change({ it.replaceAddress(entry, entry.copy(enabled = enabled)) })
                    }, modifier = Modifier.semantics { contentDescription = "Enable direct address ${entry.address}" })
                    Column(Modifier.weight(1f).clickable(enabled = !busy) {
                        change({ it.replaceAddress(entry, entry.copy(enabled = !entry.enabled)) })
                    }) {
                        Text(entry.label ?: entry.address)
                        if (entry.label != null) Text(entry.address, color = Color(0xFF9B9FA8), fontSize = 12.sp)
                    }
                    Box {
                        TextButton(onClick = { actions = true }, enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = "Actions for ${entry.address}" }) { Text("•••") }
                        DropdownMenu(actions, onDismissRequest = { actions = false }) {
                            DropdownMenuItem(text = { Text("Edit address") }, onClick = {
                                actions = false; editing = entry; address = entry.address; label = entry.label.orEmpty(); error = null; editor = true
                            })
                            DropdownMenuItem(text = { Text("Remove address") }, onClick = {
                                actions = false; change({ it.replaceAddress(entry, null) })
                            })
                        }
                    }
                }
            } }
            TextButton(onClick = { editing = null; address = ""; label = ""; error = null; editor = true },
                enabled = !busy && preference.addresses.size < 16) { Text("Add Address") }
            Text(if (preference.addresses.none { it.enabled })
                "No address is enabled. This computer stays disconnected until you enable or add one."
            else "Use a numeric IP address and UDP port. These addresses stay on this Android device.",
                color = Color(0xFF9B9FA8), fontSize = 13.sp)
        }
        if (!editor) error?.let { Text(it, color = Color(0xFFFF9999), modifier = Modifier.padding(top = 10.dp)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
    }
    if (editor) AlertDialog(onDismissRequest = { if (!busy) editor = false },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = { Text(if (editing == null) "Add Direct Address" else "Edit Direct Address") },
        text = { Column {
            OutlinedTextField(address, { address = it }, label = { Text("IP Address and UDP Port") },
                placeholder = { Text("192.168.1.20:58470") }, singleLine = true, enabled = !busy)
            OutlinedTextField(label, { label = it }, label = { Text("Label (optional)") }, singleLine = true, enabled = !busy)
            error?.let { Text(it, color = Color(0xFFFF9999), modifier = Modifier.padding(top = 10.dp)) }
        } },
        confirmButton = { TextButton(enabled = !busy && runCatching { NativePrivateAddress.parse(address) }.isSuccess,
            onClick = {
                val updated = NativeDirectAddress(NativePrivateAddress.parse(address), label, editing?.enabled ?: true)
                val original = editing
                change({ if (original == null) it.copy(addresses = it.addresses + updated) else it.replaceAddress(original, updated) },
                    saved = { editor = false })
            }) { Text("Save address") } },
        dismissButton = { TextButton(onClick = { editor = false }, enabled = !busy) { Text("Cancel") } })
}

private fun NativeMacConnectionPreference.replaceAddress(original: NativeDirectAddress, updated: NativeDirectAddress?): NativeMacConnectionPreference {
    check(addresses.singleOrNull { it.address == original.address } == original) { "Address changed. Reopen its editor." }
    return copy(addresses = addresses.mapNotNull { if (it == original) updated else it })
}

@Composable
private fun NativeDirectAddressToggle(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clip(CircleShape).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            val opacity = if (enabled) 1f else 0.4f
            if (checked) {
                drawCircle(Color(0xFF76B9FF).copy(alpha = opacity))
                val check = Path().apply {
                    moveTo(size.width * 0.25f, size.height * 0.51f)
                    lineTo(size.width * 0.43f, size.height * 0.68f)
                    lineTo(size.width * 0.76f, size.height * 0.33f)
                }
                drawPath(check, Color(0xFF0B0C0E).copy(alpha = opacity), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            } else drawCircle(Color(0xFF9B9FA8).copy(alpha = opacity), radius = size.minDimension / 2 - 1.dp.toPx(),
                style = Stroke(2.dp.toPx()))
        }
    }
}
