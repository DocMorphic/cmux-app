package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NativeTailscaleSection(target: NativeComputerTarget, method: NativeMacConnectionMethod,
    routes: List<TailscaleSavedGrant>?, pair: suspend (String, TailscaleSavedGrant?) -> Unit,
    remove: suspend (TailscaleSavedGrant) -> Unit, reload: () -> Unit,
    enabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<TailscaleSavedGrant?>(null) }
    var removing by remember { mutableStateOf<TailscaleSavedGrant?>(null) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editorGeneration by remember { mutableIntStateOf(0) }
    val activeEditor by rememberUpdatedState(editor && !busy && enabled)
    val generation by rememberUpdatedState(editorGeneration)
    fun openEditor(route: TailscaleSavedGrant?) {
        replacing = route; input = route?.route?.displayAddress().orEmpty()
        error = null; editorGeneration++; editor = true
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
        if (method == NativeMacConnectionMethod.TAILSCALE && routes?.isEmpty() == true) {
            Text("No authorized Tailscale route yet. This computer stays disconnected until you add a Tailscale connection.",
                color = Color(0xFFFFBC70), fontSize = 13.sp)
        }
        Text("ROUTES", Modifier.padding(top = 12.dp, bottom = 8.dp), color = Color(0xFF9B9FA8), fontSize = 11.sp)
        Text("Iroh", fontSize = 15.sp)
        Text("This Mac’s enrolled identity", color = Color(0xFF9B9FA8), fontSize = 12.sp)
        if (routes == null) {
            Text("Could not read saved Tailscale connections.", color = Color(0xFFFFBC70))
            TextButton(onClick = reload, enabled = !busy && enabled) { Text("Retry Tailscale connections") }
        } else routes.forEach { route -> key(route.id) {
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text("Tailscale", fontSize = 15.sp)
                    Text(route.route.displayAddress(), color = Color(0xFF9B9FA8), fontSize = 13.sp)
                }
                Box {
                    TextButton(onClick = { menu = true }, enabled = !busy && enabled,
                        modifier = Modifier.semantics { contentDescription = "Tailscale actions for ${route.route.displayAddress()}" }) { Text("•••") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit connection") }, onClick = { menu = false; openEditor(route) })
                        DropdownMenuItem(text = { Text("Remove connection") }, onClick = { menu = false; removing = route; error = null })
                    }
                }
            }
        } }
        TextButton(onClick = { openEditor(null) }, enabled = !busy && enabled && routes != null) { Text("Add Tailscale Connection") }
        if (!editor && removing == null) error?.let { Text(it, color = Color(0xFFFF9999)) }
    }
    if (editor) AlertDialog(onDismissRequest = { if (!busy) editor = false },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = { Text(if (replacing == null) "Add Tailscale Connection" else "Edit Tailscale Connection") },
        text = { Column {
            Text("Scan this Mac’s pairing QR, paste its code, or enter its Tailscale IP address and TCP port.", fontSize = 13.sp)
            TextButton(onClick = {
                val scanGeneration = editorGeneration
                runCatching { GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()).startScan() }
                    .onSuccess { scan -> scan.addOnSuccessListener { result ->
                        if (activeEditor && generation == scanGeneration) input = result.rawValue.orEmpty()
                    }.addOnFailureListener {
                        if (activeEditor && generation == scanGeneration) error = "Could not scan the QR code. Paste it below or retry."
                    } }.onFailure { error = "Could not open the QR scanner." }
            }, enabled = !busy && enabled) { Text("Scan cmux QR code") }
            OutlinedTextField(input, { input = it }, label = { Text("Pairing Code or IP Address:Port") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Text("Connect sends your cmux account session over Tailscale and verifies ${target.name} (${target.buildTag}) before saving this route.", fontSize = 12.sp)
            error?.let { Text(it, color = Color(0xFFFF9999)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = {
            val keyboard = LocalSoftwareKeyboardController.current
            val focus = LocalFocusManager.current
            TextButton(enabled = !busy && enabled && runCatching { tailscalePairingInput(input) }.isSuccess,
            onClick = {
                if (busy) return@TextButton
                keyboard?.hide(); focus.clearFocus()
                busy = true; error = null
                val captured = input; val old = replacing
                scope.launch {
                    try { pair(captured, old); editor = false }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        error = failure.message ?: "Could not verify this Tailscale connection. Check the Mac and retry."
                    } finally { busy = false }
                }
            }) { Text("Connect") } },
        dismissButton = {
            val keyboard = LocalSoftwareKeyboardController.current
            TextButton(onClick = { keyboard?.hide(); editor = false }, enabled = !busy) { Text("Cancel") }
        })
    removing?.let { route -> AlertDialog(onDismissRequest = { if (!busy) removing = null },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = { Text("Remove Tailscale connection?") },
        text = { Column {
            Text(route.route.displayAddress())
            Text("If no authorized route remains, Tailscale Only will stay disconnected.", fontSize = 13.sp)
            error?.let { Text(it, color = Color(0xFFFF9999)) }
        } },
        confirmButton = { TextButton(enabled = !busy && enabled, onClick = {
            if (busy) return@TextButton
            busy = true; error = null
            scope.launch {
                try { remove(route); removing = null }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = "Could not remove this connection. Check your account and retry."
                } finally { busy = false }
            }
        }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }, enabled = !busy) { Text("Cancel") } }) }
}

internal fun PairingCode.Route.displayAddress() = "${if (':' in host) "[$host]" else host}:$port"
internal fun tailscalePairingInput(input: String): PairingCode.Tailscale {
    val value = input.trim()
    if (value.contains("://")) return PairingCodeParser.parse(value).getOrThrow() as? PairingCode.Tailscale
        ?: error("Use this Mac’s Tailscale pairing QR, not its Iroh code.")
    val code = "cmux-ios://attach?v=2&r=" + java.net.URLEncoder.encode(value, "UTF-8")
    val parsed = PairingCodeParser.parse(code).getOrThrow() as PairingCode.Tailscale
    require(parsed.routes.all { TailscalePeerAddress.canonical(it.host) != null }) { "Enter a numeric Tailscale IP address and TCP port." }
    return parsed
}
