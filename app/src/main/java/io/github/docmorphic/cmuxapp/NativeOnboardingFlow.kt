package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun NativeOnboardingFlow(initialProgress: NativeOnboardingProgress, replay: Boolean,
    phase: NativeOnboardingPhase, hostName: String?, policy: NativeMacCompatibilityPolicy,
    notificationBusy: Boolean, notificationResult: Long, error: String?,
    onEnableNotifications: () -> Unit, onReachedConnection: () -> Unit, onComplete: () -> Unit,
    onRetry: () -> Unit, onScan: () -> Unit, onPairing: (String) -> Unit, onSettings: () -> Unit,
    onMethod: (NativeOnboardingMethod) -> Unit = {}, initialMethod: NativeOnboardingMethod = NativeOnboardingMethod.AUTOMATIC, canConnect: Boolean = true,
    computers: @Composable ColumnScope.() -> Unit = {}, keepAwake: @Composable () -> Unit = {}) {
    val initial = if (!replay && initialProgress == NativeOnboardingProgress.CONNECT) NativeOnboardingStage.CONNECT else NativeOnboardingStage.AGENTS
    val pager = rememberPagerState(initialPage = initial.ordinal, pageCount = { NativeOnboardingStage.entries.size })
    val stage = NativeOnboardingStage.entries[pager.settledPage]
    var method by rememberSaveable { mutableStateOf(initialMethod) }
    var reached by rememberSaveable { mutableStateOf(false) }
    var seenPermissionResult by rememberSaveable { mutableLongStateOf(notificationResult) }
    var pairingDraft by rememberSaveable(stateSaver = NativePairingDraftSaver) { mutableStateOf("") }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latestReached by rememberUpdatedState(onReachedConnection)
    val latestMethod by rememberUpdatedState(onMethod)
    LaunchedEffect(method) { latestMethod(method) }
    fun navigate(next: NativeOnboardingStage) { scope.launch { pager.scrollToPage(next.ordinal) } }
    LaunchedEffect(stage) {
        if (stage == NativeOnboardingStage.CONNECT && !reached) { reached = true; latestReached() }
    }
    LaunchedEffect(notificationResult) {
        if (notificationResult != seenPermissionResult) {
            seenPermissionResult = notificationResult
            if (stage == NativeOnboardingStage.PUSH) pager.scrollToPage(NativeOnboardingStage.PAIRING.ordinal)
        }
    }
    BackHandler {
        if (stage != NativeOnboardingStage.AGENTS) navigate(NativeOnboardingStage.entries[stage.ordinal - 1])
        else if (replay) onComplete() else onSettings()
    }
    val chrome = if (stage == NativeOnboardingStage.CONNECT && !canConnect)
        NativeOnboardingChrome("Open Settings", back = true, skip = false)
        else NativeOnboardingChrome.forStage(stage, phase, method)
    val compact = LocalConfiguration.current.screenHeightDp < 480
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().testTag("onboarding.flow")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (chrome.back) TextButton(onClick = { navigate(NativeOnboardingStage.entries[stage.ordinal - 1]) }, modifier = Modifier.testTag("onboarding.back")) { Text("Back") }
            else Spacer(Modifier.width(64.dp))
            Text("${stage.ordinal + 1} of 5", Modifier.weight(1f).semantics { contentDescription = "Introduction, step ${stage.ordinal + 1} of 5" }, textAlign = TextAlign.Center)
            if (chrome.skip) TextButton(onClick = onComplete, enabled = !notificationBusy, modifier = Modifier.testTag("onboarding.skip")) { Text("Skip") }
            else TextButton(onClick = onSettings, modifier = Modifier.testTag("onboarding.settings")) { Text("Settings") }
        }
        HorizontalPager(pager, Modifier.weight(1f).testTag("onboarding.pager"), userScrollEnabled = !notificationBusy) { index ->
            val page = NativeOnboardingStage.entries[index]
            val active = pager.settledPage == index
            val title = when (page) {
                    NativeOnboardingStage.AGENTS -> "Your agents keep working on your Mac"
                    NativeOnboardingStage.NOTIFICATIONS -> "Every agent alert, in one place"
                    NativeOnboardingStage.PUSH -> "Know when an agent needs you"
                    NativeOnboardingStage.PAIRING -> "Enable iOS pairing on your Mac"
                    NativeOnboardingStage.CONNECT -> if (phase == NativeOnboardingPhase.READY) "Your Mac is connected"
                        else if (method == NativeOnboardingMethod.TAILSCALE) "Connect over Tailscale" else "Your Mac connects automatically"
                }
            val configuration = LocalConfiguration.current
            OnboardingPageLayout(title, page, active,
                (compact || configuration.screenWidthDp >= 700) && configuration.fontScale < 1.3f) {
                when (page) {
                    NativeOnboardingStage.AGENTS -> {
                        Text("Track every workspace from your phone.", textAlign = TextAlign.Center)
                        OnboardingExample("Workspaces", listOf("Website · Agent working", "Android app · Ready for review", "Documentation · Terminal"), R.drawable.ic_workspace_folder)
                    }
                    NativeOnboardingStage.NOTIFICATIONS -> {
                        Text("Review every agent alert in one feed. Open an alert to return to its workspace.", textAlign = TextAlign.Center)
                        OnboardingExample("Notifications", listOf("Android app · Review requested", "Website · Task finished", "Documentation · Waiting for input"), R.drawable.ic_computer_terminal)
                    }
                    NativeOnboardingStage.PUSH -> {
                        Text("Get agent alerts while cmux keeps a connection to your Mac, and reply from a notification.", textAlign = TextAlign.Center)
                        OnboardingExample("Agent needs your input", listOf("Review the changes in your workspace", "Reply from your phone"), R.drawable.ic_computer_terminal)
                        Text("This build uses a background connection with an ongoing notification. Push delivery while that connection is stopped still requires Firebase setup.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("onboarding.push.status"))
                    }
                    NativeOnboardingStage.PAIRING -> {
                        Text("On your Mac, open cmux Settings > Mobile and turn on Enable iOS pairing. This setting also enables the Android companion.", textAlign = TextAlign.Center)
                        Image(painterResource(if (MaterialTheme.colorScheme.surface.luminance() < .5f) R.drawable.mac_pairing_settings_dark else R.drawable.mac_pairing_settings_light),
                            "Mac Settings showing Enable iOS pairing", Modifier.fillMaxWidth().aspectRatio(618f / 171f).testTag("onboarding.pairing.image"))
                        Text("Required for Mac discovery", fontWeight = FontWeight.SemiBold)
                        Text("Sign in on your Mac and this phone with the same cmux account and team.", textAlign = TextAlign.Center)
                        policy.pairingMinimumCopy()?.let { Text(it, textAlign = TextAlign.Center) }
                        TextButton(enabled = active, onClick = { runCatching { uri.openUri("https://github.com/manaflow-ai/cmux/releases/latest") } }) { Text("Download cmux for Mac") }
                    }
                    NativeOnboardingStage.CONNECT -> {
                        Text(if (phase == NativeOnboardingPhase.READY) "${hostName ?: "Your Mac"} is ready. Open any workspace and respond when an agent needs you."
                            else if (method == NativeOnboardingMethod.TAILSCALE) "Install Tailscale on both devices and join the same network, then scan or paste the Mac's pairing code."
                            else "Use the same cmux account and team on both devices. Keep Enable iOS pairing on in your Mac's Mobile settings.", textAlign = TextAlign.Center)
                        policy.pairingMinimumCopy()?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
                        OnboardingConnectionPreview(phase, hostName)
                        if (phase == NativeOnboardingPhase.SEARCHING) {
                            CircularProgressIndicator(Modifier.size(32.dp))
                            Text("Connecting to your Mac…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilterChip(method == NativeOnboardingMethod.AUTOMATIC, {
                                method = NativeOnboardingMethod.AUTOMATIC; onMethod(method)
                                if (canConnect && phase != NativeOnboardingPhase.READY) onRetry()
                            }, { Text("Automatic") }, enabled = active, modifier = Modifier.testTag("onboarding.automatic"))
                            FilterChip(method == NativeOnboardingMethod.TAILSCALE, {
                                method = NativeOnboardingMethod.TAILSCALE; onMethod(method)
                                if (canConnect && phase == NativeOnboardingPhase.READY) onScan()
                            }, { Text("Tailscale") }, enabled = active, modifier = Modifier.testTag("onboarding.tailscale"))
                        }
                        if (active && method == NativeOnboardingMethod.AUTOMATIC && phase != NativeOnboardingPhase.READY) computers()
                        if (method == NativeOnboardingMethod.TAILSCALE) {
                            TextButton(enabled = active, onClick = { showPaste = !showPaste }) { Text("Paste a pairing code") }
                            if (showPaste) {
                                OutlinedTextField(pairingDraft, { pairingDraft = it }, enabled = active, label = { Text("Pairing code") }, modifier = Modifier.fillMaxWidth().testTag("onboarding.paste"))
                                Button(enabled = active && canConnect && pairingDraft.isNotBlank(), onClick = { onPairing(pairingDraft) }) { Text("Connect") }
                            }
                        }
                        if (active && phase == NativeOnboardingPhase.READY) keepAwake()
                    }
                }
            }
        }
        error?.let { Text(it, Modifier.padding(horizontal = 24.dp).testTag("onboarding.error"), color = MaterialTheme.colorScheme.error) }
        val primary: () -> Unit = {
            when (stage) {
                NativeOnboardingStage.AGENTS -> navigate(NativeOnboardingStage.NOTIFICATIONS)
                NativeOnboardingStage.NOTIFICATIONS -> navigate(NativeOnboardingStage.PUSH)
                NativeOnboardingStage.PUSH -> onEnableNotifications()
                NativeOnboardingStage.PAIRING -> navigate(NativeOnboardingStage.CONNECT)
                NativeOnboardingStage.CONNECT -> if (!canConnect) onSettings() else if (phase == NativeOnboardingPhase.READY) onComplete()
                    else if (method == NativeOnboardingMethod.TAILSCALE) onScan() else onRetry()
            }
        }
        val secondary: () -> Unit = {
            if (stage == NativeOnboardingStage.PUSH) navigate(NativeOnboardingStage.PAIRING) else onRetry()
        }
        OnboardingFooter(chrome, compact, !notificationBusy && !pager.isScrollInProgress, primary, secondary)
    }
}

/** Static examples are explicitly labeled and never claim to be the user's live work. */
@Composable
private fun OnboardingExample(title: String, rows: List<String>, icon: Int) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.cmux_logo), null, Modifier.size(30.dp))
                Text(title, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
            }
            rows.forEach { text -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null, Modifier.size(22.dp))
                Text(text, Modifier.padding(start = 12.dp))
            } }
            Text("Example", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OnboardingFooter(chrome: NativeOnboardingChrome, compact: Boolean, enabled: Boolean,
    primary: () -> Unit, secondary: () -> Unit) {
    val actions: @Composable (Modifier) -> Unit = { actionModifier ->
        chrome.primary?.let { label -> Button(onClick = primary, enabled = enabled,
            modifier = actionModifier.testTag("onboarding.primary")) { Text(label, textAlign = TextAlign.Center) } }
        chrome.secondary?.let { label -> TextButton(onClick = secondary, enabled = enabled,
            modifier = actionModifier.testTag("onboarding.secondary")) { Text(label, textAlign = TextAlign.Center) } }
    }
    if (compact) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions(Modifier.weight(1f)) }
    else Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        actions(Modifier.fillMaxWidth())
        if (chrome.secondary == null && chrome.primary != null) Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun OnboardingPageLayout(title: String, stage: NativeOnboardingStage, active: Boolean, wide: Boolean,
    content: @Composable ColumnScope.() -> Unit) {
    val heading: @Composable () -> Unit = {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.testTag("onboarding.title.${stage.name}"))
    }
    val modifier = Modifier.fillMaxSize().then(if (active) Modifier else Modifier.clearAndSetSemantics {})
    if (wide) Row(modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(.8f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) { heading() }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    } else Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        heading(); content()
    }
}

@Composable
private fun OnboardingConnectionPreview(phase: NativeOnboardingPhase, hostName: String?) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().testTag("onboarding.connection.preview")) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_computer_desktop), null, Modifier.size(40.dp))
                Text(if (phase == NativeOnboardingPhase.READY) hostName ?: "Your Mac" else "Your Mac", textAlign = TextAlign.Center)
            }
            Text(when (phase) {
                NativeOnboardingPhase.READY -> "Connected"
                NativeOnboardingPhase.SEARCHING -> "Searching"
                NativeOnboardingPhase.FALLBACK -> "Not connected"
                NativeOnboardingPhase.IDLE -> "Ready to pair"
            }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_menu_phone), null, Modifier.size(40.dp))
                Text("This phone", textAlign = TextAlign.Center)
            }
        }
    }
}
