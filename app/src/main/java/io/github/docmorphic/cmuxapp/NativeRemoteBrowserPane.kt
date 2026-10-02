package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun NativeRemoteBrowserPane(client: MobileRpcClient?, browser: NativeBrowser, busy: Boolean,
    connectionError: String?, onBack: () -> Unit, onReconnect: () -> Unit,
    modeRevision: Any? = null, prefersOnDevice: Boolean = false,
    availability: (suspend () -> MacBrowserAvailability)? = null, onOnDevice: ((String) -> Unit)? = null) {
    key(client, browser.id) {
        val scope = rememberCoroutineScope()
        val currentAvailability by rememberUpdatedState(availability)
        val currentOpen by rememberUpdatedState(onOnDevice)
        var access by remember(modeRevision) { mutableStateOf(MacBrowserAvailability.NOT_CONNECTED) }
        suspend fun refresh(): MacBrowserAvailability {
            val next = try { currentAvailability?.invoke() ?: MacBrowserAvailability.NOT_CONNECTED }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); MacBrowserAvailability.NOT_CONNECTED }
            access = next
            return next
        }
        LaunchedEffect(modeRevision, prefersOnDevice) {
            val next = refresh()
            if (prefersOnDevice && next.bindsBrowserToMac) currentOpen?.invoke("")
        }
        val switch: ((String) -> Unit)? = if (onOnDevice == null) null else { url ->
            scope.launch {
                // A displayed capability can become stale between rendering and tapping.
                if (refresh() == MacBrowserAvailability.AVAILABLE) currentOpen?.invoke(url)
            }; Unit
        }
        if (client != null) NativeBrowserView(client, browser.id, browser.title, onBack,
            onOnDevice = switch, onDeviceUnavailable = access.onDeviceUnavailableReason)
        else {
            BackHandler(onBack = onBack)
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onBack) { Text("‹  Workspaces") }
                if (switch != null) BrowserModePicker(BrowserMode.STREAMED, access.onDeviceUnavailableReason) { switch("") }
                Text(browser.title.ifBlank { "Browser" }, style = MaterialTheme.typography.titleMedium)
                Text(if (busy) "Reconnecting to your Mac…" else "Browser disconnected", color = Color(0xFF9B9FA8))
                connectionError?.let { Text(it, color = Color(0xFFFF9999)) }
                TextButton(onClick = onReconnect, enabled = !busy) { Text("Reconnect") }
            }
        }
    }
}
