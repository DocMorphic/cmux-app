package io.github.docmorphic.cmuxapp

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler

internal val LocalMacCompatibilityWarnings = compositionLocalOf<Map<NativeMacIdentity, MacCompatibilityViolation>> { emptyMap() }

@Composable
internal fun NativeMacUpdateGuidance(identity: NativeMacIdentity?) {
    val warning = identity?.let { LocalMacCompatibilityWarnings.current[
        NativeMacIdentity(canonicalMacDeviceId(it.deviceId), it.buildTag)] } ?: return
    var show by remember(identity, warning) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    TextButton(onClick = { show = true }) { Text("Mac update required", color = MaterialTheme.colorScheme.error) }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("Update cmux on your Mac") },
        text = { Text(warning.message) }, confirmButton = {
            TextButton(onClick = { runCatching { uri.openUri(warning.downloadUrl) } }) { Text("View Mac release") }
        }, dismissButton = { TextButton(onClick = { show = false }) { Text("Done") } })
}
