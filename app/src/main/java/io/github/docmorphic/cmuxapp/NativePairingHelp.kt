package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Setup copy reads the same policy as admission. It never changes a connection method or enables a listener. */
internal fun NativeMacCompatibilityPolicy.pairingMinimumCopy(): String? = requirement()?.stable?.let {
    "Use cmux $it or newer on your Mac."
}

@Composable
internal fun NativePairingHelp(policy: NativeMacCompatibilityPolicy, signedIn: Boolean,
    onDismiss: () -> Unit, onFindMac: () -> Unit, onTailscalePairing: () -> Unit) {
    val uri = LocalUriHandler.current
    var linkError by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val lightBars = MaterialTheme.colorScheme.surface.luminance() >= .5f
        DisposableEffect(view, lightBars) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            val oldStatus = controller?.isAppearanceLightStatusBars
            val oldNavigation = controller?.isAppearanceLightNavigationBars
            controller?.isAppearanceLightStatusBars = lightBars
            controller?.isAppearanceLightNavigationBars = lightBars
            onDispose {
                oldStatus?.let { controller?.isAppearanceLightStatusBars = it }
                oldNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
            }
        }
        Surface(Modifier.fillMaxSize().testTag("pairing.help")) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Connect your Mac", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("pairing.help.done")) { Text("Done") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("Enable iOS pairing on your Mac", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("On your Mac, open cmux Settings > Mobile and turn on Enable iOS pairing. Your Mac stays hidden until you do.")
                    Image(painterResource(if (MaterialTheme.colorScheme.surface.luminance() < .5f)
                        R.drawable.mac_pairing_settings_dark else R.drawable.mac_pairing_settings_light),
                        contentDescription = "cmux Mac Settings, Mobile section, showing Enable iOS pairing.",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1030f / 285f).clip(MaterialTheme.shapes.medium)
                            .testTag("pairing.help.screenshot"))
                    Text("This Mac setting also enables this Android companion.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Text("Required for Mac discovery", Modifier.padding(14.dp), fontWeight = FontWeight.SemiBold)
                    }
                    Text("Use the same cmux account", style = MaterialTheme.typography.titleMedium)
                    Text("Sign in on your Mac and this phone with the same account, and select the same team.")
                    policy.pairingMinimumCopy()?.let { Text(it, modifier = Modifier.testTag("pairing.help.minimum")) }
                    TextButton(onClick = {
                        runCatching { uri.openUri("https://github.com/manaflow-ai/cmux/releases/latest") }
                            .onFailure { linkError = "Could not open the Mac download page." }
                    }) { Text("Download cmux for Mac") }
                    linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    HorizontalDivider()
                    Text("Automatic connection", style = MaterialTheme.typography.titleMedium)
                    Text("With mobile pairing enabled, your Mac appears in Computers. Select it to connect using Iroh.")
                    Button(onClick = onFindMac, modifier = Modifier.fillMaxWidth().testTag("pairing.help.find")) {
                        Text(if (signedIn) "Find my Mac" else "Back to sign in")
                    }
                    Text("Connect over Tailscale", style = MaterialTheme.typography.titleMedium)
                    Text("Install Tailscale on your Mac and phone and join the same network. Open Mobile Pairing on your Mac, then scan or paste its pairing code on this phone.")
                    OutlinedButton(onClick = onTailscalePairing, enabled = signedIn,
                        modifier = Modifier.fillMaxWidth().testTag("pairing.help.tailscale")) { Text("Use a Tailscale pairing code") }
                    if (!signedIn) Text("Sign in on this phone before pairing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
