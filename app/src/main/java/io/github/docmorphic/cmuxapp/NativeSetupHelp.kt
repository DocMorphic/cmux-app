package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Settings presents setup as a reference, without identifying an active connection gate. */
@Composable
internal fun NativeSettingsSetupHelp(policy: NativeMacCompatibilityPolicy) {
    var showing by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showing = true }, modifier = Modifier.padding(horizontal = 14.dp).testTag("settings.setup-help")) {
        Text("Set Up Computer")
    }
    if (showing) NativeSetupHelp(policy) { showing = false }
}

/** Re-enterable guidance reads the current durable gate, without invoking connection actions. */
@Composable
internal fun NativeSetupHelpButton(policy: NativeMacCompatibilityPolicy, highlight: NativeSetupGuidance,
    tag: String, label: String = "Trouble connecting?", visible: Boolean = true) {
    var showing by rememberSaveable { mutableStateOf(false) }
    if (visible) TextButton(onClick = { showing = true }, modifier = Modifier.testTag(tag)) { Text(label) }
    if (showing) NativeSetupHelp(policy, highlight) { showing = false }
}

/** Static guidance only: no account, pairing, discovery or connection controller is passed in. */
@Composable
internal fun NativeSetupHelp(policy: NativeMacCompatibilityPolicy, highlight: NativeSetupGuidance? = null,
    onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val density = LocalDensity.current
    var linkError by remember { mutableStateOf<String?>(null) }
    fun open(target: String) {
        runCatching { uri.openUri(target) }.onSuccess { linkError = null }
            .onFailure { linkError = "Could not open the download page. Try again." }
    }
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
        // A Dialog has its own AndroidComposeView; retain the host's text scaling.
        CompositionLocalProvider(LocalDensity provides density) {
            Surface(Modifier.fillMaxSize().testTag("setup.help")) {
                Column(Modifier.safeDrawingPadding().imePadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(horizontal = 14.dp)
                        .testTag("setup.help.header"), verticalAlignment = Alignment.CenterVertically) {
                        Text("Set Up Computer", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("setup.help.done")) { Text("Done") }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(if (highlight == null) "To see your computer's terminals here, sign in to the same cmux account on both devices and keep cmux running on the computer. Connection is automatic after that."
                            else "To see your computer's terminals here, sign in to the same cmux account on both devices and keep cmux running on the computer. The step you are on is marked below.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        NativeSetupGuidance.ordered(highlight).forEach { gate -> key(gate) {
                        when (gate) {
                        NativeSetupGuidance.NOT_SIGNED_IN -> SetupHelpSection(gate.id, "Sign in", R.drawable.ic_workspace_users, gate == highlight) {
                            Text("Sign in to cmux on this phone with the same account your computer uses. Once you sign in, your computer is found automatically.")
                        }
                        NativeSetupGuidance.NEVER_PAIRED -> SetupHelpSection(gate.id, "Run cmux on your computer", R.drawable.ic_computer_desktop, gate == highlight) {
                            Text("Install cmux on your computer, sign in to the same account, and leave it running. The computer then appears on this phone automatically. To pair through Tailscale, install Tailscale on both devices and connect them to the same Tailscale network. Open Mobile Pairing on the Mac and scan its QR here, or enter the Mac's numeric Tailscale IP and port.")
                            Text("On your Mac, open cmux Settings > Mobile and turn on Enable iOS pairing. This setting also enables this Android companion.")
                            policy.pairingMinimumCopy()?.let { Text(it, Modifier.testTag("setup.help.minimum")) }
                        }
                        NativeSetupGuidance.MAC_UNREACHABLE -> SetupHelpSection(gate.id, "Wake the computer", R.drawable.ic_workspace_network, gate == highlight) {
                            Text("You paired this computer before, but it is not reachable now. Wake it and make sure cmux is running; this phone reconnects on its own.")
                        }
                        NativeSetupGuidance.ACCOUNT_MISMATCH -> SetupHelpSection(gate.id, "Match the account", R.drawable.ic_task_warning, gate == highlight) {
                            Text("The computer rejected this phone's sign-in, so the two are on different cmux accounts or this phone's session is stale. Sign either one out and back in so both use the same account, then try again.")
                        }
                        }
                        } }
                        SetupHelpSection("network", "How it connects", R.drawable.ic_workspace_lock) {
                            Text("cmux connects through Iroh, which links this phone to your computer directly when possible and through an encrypted cmux relay when not. Both devices verify your account, and the relay cannot read your terminals. No network setup is required.")
                            TextButton(onClick = { open("https://play.google.com/store/apps/details?id=com.tailscale.ipn") },
                                modifier = Modifier.testTag("setup.help.tailscale.android")) { Text("Optional: Tailscale for Android") }
                            TextButton(onClick = { open("https://tailscale.com/download") },
                                modifier = Modifier.testTag("setup.help.tailscale.computer")) { Text("Optional: Tailscale for the computer") }
                            Text("If both devices are on Tailscale, another VPN, or the same network, cmux can use it as a faster direct path. The connection stays encrypted and verified either way.", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Both devices must be signed in to the same cmux account. A private network never replaces that check.",
                            Modifier.testTag("setup.help.footer"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        linkError?.let { Text(it, Modifier.testTag("setup.help.link-error").semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error) }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupHelpSection(id: String, title: String, icon: Int, highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("setup.help.gate:$id"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f
        Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (highlighted && !stacked) SetupHereMarker()
        }
        if (highlighted && stacked) SetupHereMarker(Modifier.padding(start = 30.dp, top = 4.dp))
        }
        }
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium,
            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { content() }
    }
}

@Composable
private fun SetupHereMarker(modifier: Modifier = Modifier) {
    Text("You are here", modifier.testTag("setup.help.you-are-here"), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary)
}
