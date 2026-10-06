package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun NativeCloudVpnControl(runtime: NativeCloudVpnRuntime, modifier: Modifier = Modifier) {
    val state by runtime.state.collectAsState()
    val owner by runtime.owner.collectAsState()
    val failure by runtime.failure.collectAsState()
    val context = LocalContext.current
    var confirming by remember { mutableStateOf<NativeTeamScope?>(null) }
    var permissionOwner by remember { mutableStateOf<NativeTeamScope?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val expected = permissionOwner; permissionOwner = null
        if (result.resultCode != Activity.RESULT_OK) error = "VPN permission was not granted."
        else if (expected == null || !runtime.enable(expected)) error = "Your account changed. Turn on the Cloud VPN again."
    }
    LaunchedEffect(owner) { confirming = null; permissionOwner = null; error = null }
    val checked = state.phase in setOf(CloudSystemVpnPhase.PREPARING, CloudSystemVpnPhase.CONNECTING, CloudSystemVpnPhase.CONNECTED)
    val busy = state.phase in setOf(CloudSystemVpnPhase.PREPARING, CloudSystemVpnPhase.CONNECTING, CloudSystemVpnPhase.DISCONNECTING)
    Column(modifier.fillMaxWidth().padding(16.dp).testTag("cloud.vpn.controls"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("System VPN", style = MaterialTheme.typography.titleMedium)
                Text(when (state.phase) {
                    CloudSystemVpnPhase.PREPARING -> "Setting up…"
                    CloudSystemVpnPhase.CONNECTING -> "Connecting…"
                    CloudSystemVpnPhase.CONNECTED -> "Connected"
                    CloudSystemVpnPhase.DISCONNECTING -> "Disconnecting…"
                    else -> "Off"
                }, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked, onCheckedChange = { enable ->
                error = null
                if (enable) confirming = owner else runtime.disable()
            }, enabled = owner != null && !busy && permissionOwner == null, modifier = Modifier.testTag("cloud.vpn.switch")
                .semantics { contentDescription = "System VPN" })
        }
        Text("Reach private Cloud services from your browser and other apps. Enabling this replaces Tailscale or another active Android VPN.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        (error ?: failure ?: state.message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.pendingCleanup > 0) Text("${state.pendingCleanup} Cloud VPN peer(s) awaiting cleanup.", style = MaterialTheme.typography.bodySmall)
        if (!busy && (failure != null || state.phase == CloudSystemVpnPhase.FAILED || state.pendingCleanup > 0))
            TextButton(onClick = { error = null; runtime.retry() }, modifier = Modifier.testTag("cloud.vpn.retry")) { Text("Retry cleanup") }
    }
    confirming?.let { expected -> AlertDialog(onDismissRequest = { confirming = null },
        title = { Text("Enable Cloud VPN?") },
        text = { Text("Android can run one system VPN at a time. This connects private Cloud services and replaces any active VPN, including Tailscale. Cloud terminals can connect without enabling this setting.") },
        confirmButton = { TextButton(onClick = {
            confirming = null
            try {
                val intent = VpnService.prepare(context)
                if (intent == null) {
                    if (!runtime.enable(expected)) error = "Your account changed. Turn on the Cloud VPN again."
                } else { permissionOwner = expected; launcher.launch(intent) }
            } catch (_: Exception) { permissionOwner = null; error = "Could not request VPN permission." }
        }) { Text("Continue") } }, dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } }) }
}
