package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun NativeConnectionCheckSettings(client: MobileRpcClient?, macs: List<NativeCredentialStore.PairedMac>,
                                          code: String, ready: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mac = macs.singleOrNull { it.code == code }
    key(client, mac?.origin, ready) {
        NativeConnectionCheckSection(enabled = client != null && mac != null && ready,
            run = { NativeConnectionCheck.run(checkNotNull(client), checkNotNull(mac)) },
            share = { report ->
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, report)
                context.startActivity(android.content.Intent.createChooser(intent, "Share Connection Report"))
            })
    }
}

@Composable
internal fun NativeConnectionCheckSection(enabled: Boolean, run: suspend () -> NativeConnectionReport,
                                         share: (String) -> Unit) {
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<NativeConnectionReport?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text("CONNECTION CHECK", color = Color(0xFF9B9FA8), fontSize = 11.sp)
        TextButton(enabled = enabled && !running, onClick = {
            if (!running) {
                running = true; report = null
                scope.launch { try { report = run() } finally { running = false } }
            }
        }) { Text(if (running) "Checking…" else "Check Connection") }
        if (!enabled) Text("Connect to a Mac to run a check.", fontSize = 13.sp, color = Color(0xFF9B9FA8))
        report?.let { result ->
            CheckRow("Active Route", result.route)
            CheckRow("Encrypted Transport", result.encryption)
            CheckRow("Mac Identity", if (result.identity) "Verified" else "Not Verified")
            CheckRow("Account Access", if (result.accountAccess) "Verified" else "Not Verified")
            result.transport?.roundTripMillis?.let { CheckRow("Transport RTT", "$it ms") }
            result.responseMillis?.let { CheckRow("RPC Response", "$it ms") }
            result.failure?.let { Text(it.advice, color = Color(0xFFFFC170), fontSize = 13.sp) }
            TextButton(onClick = { share(result.shareText()) }) { Text("Share Connection Report") }
        }
        Text("Reports exclude computer names, terminal content, network addresses and credentials.",
            fontSize = 12.sp, color = Color(0xFF9B9FA8))
    }
}

@Composable private fun CheckRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        Text(value, Modifier.weight(1f), fontSize = 13.sp)
    }
}
