package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun NativeRemoteBrowserPane(client: MobileRpcClient?, browser: NativeBrowser, busy: Boolean,
    connectionError: String?, onBack: () -> Unit, onReconnect: () -> Unit) {
    if (client != null) NativeBrowserView(client, browser.id, browser.title, onBack)
    else {
        BackHandler(onBack = onBack)
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("‹  Workspaces") }
            Text(browser.title.ifBlank { "Browser" }, style = MaterialTheme.typography.titleMedium)
            Text(if (busy) "Reconnecting to your Mac…" else "Browser disconnected", color = Color(0xFF9B9FA8))
            connectionError?.let { Text(it, color = Color(0xFFFF9999)) }
            TextButton(onClick = onReconnect, enabled = !busy) { Text("Reconnect") }
        }
    }
}
