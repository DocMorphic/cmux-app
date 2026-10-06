package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Per-install completion, independent of accounts and the Mac introduction. */
internal class CloudOnboardingStore(private val read: () -> Boolean, private val write: () -> Boolean) {
    val completed get() = read()
    fun complete() { check(write()) { "Could not save Cloud introduction progress. Try again." } }
    companion object { const val KEY = "mobile.cloud.onboarding.completed.v2" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NativeCloudFlow(controller: CloudMachinesController?, onSettings: () -> Unit,
    onPlans: (String?) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier,
    connectionState: CloudTunnelState? = null, onRetryConnection: () -> Unit = {},
    progressStore: CloudOnboardingStore? = null, vpn: NativeCloudVpnRuntime? = null,
    machines: Map<String, CloudWorkspaceSnapshot> = emptyMap(), onRetryConnections: () -> Unit = {}) {
    val context = LocalContext.current.applicationContext
    val store = remember(context, progressStore) { progressStore ?: run {
        val prefs = context.getSharedPreferences("native_onboarding", android.content.Context.MODE_PRIVATE)
        CloudOnboardingStore({ prefs.getBoolean(CloudOnboardingStore.KEY, false) },
            { prefs.edit().putBoolean(CloudOnboardingStore.KEY, true).commit() })
    } }
    var completed by remember(store) { mutableStateOf(store.completed) }
    var replay by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val vpnControl: (@Composable () -> Unit)? = vpn?.let { runtime -> { NativeCloudVpnControl(runtime) } }
    Box(modifier.fillMaxSize()) {
        if (completed) NativeCloudScreen(controller, onSettings, onPlans, Modifier.fillMaxSize(), connectionState,
            onRetryConnection, onBasics = { replay = true }, vpnControl = vpnControl,
            machines = machines, onRetryConnections = onRetryConnections)
        else NativeCloudIntroduction(false, error, onComplete = {
            runCatching { store.complete() }.onSuccess { completed = true; error = null }
                .onFailure { error = it.message ?: "Could not save Cloud introduction progress. Try again." }
        }, onBack = onBack, vpnControl = vpnControl)
    }
    if (replay) ModalBottomSheet(onDismissRequest = { replay = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        NativeCloudIntroduction(true, null, onComplete = { replay = false }, onBack = { replay = false },
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false), vpnControl = vpnControl)
    }
}

@Composable internal fun NativeCloudIntroduction(replay: Boolean, error: String?, onComplete: () -> Unit,
    onBack: () -> Unit, modifier: Modifier = Modifier, vpnControl: (@Composable () -> Unit)? = null) {
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val page = pager.settledPage
    fun move(index: Int) { scope.launch { pager.animateScrollToPage(index.coerceIn(0, 2)) } }
    BackHandler { if (page > 0) move(page - 1) else onBack() }
    val configuration = LocalConfiguration.current
    val wide = (configuration.screenWidthDp >= 700 || configuration.screenHeightDp < 480) && configuration.fontScale < 1.3f
    Column(modifier.fillMaxSize().testTag(if (replay) "cloud.introduction.replay" else "cloud.introduction.inline")
        .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .10f), MaterialTheme.colorScheme.surface)))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onComplete, modifier = Modifier.testTag("cloud.introduction.skip")) { Text("Skip") }
            Text(if (replay) "Cloud basics" else "Cloud", Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(64.dp))
        }
        Row(Modifier.fillMaxWidth().padding(12.dp).clearAndSetSemantics {
            contentDescription = "Cloud introduction, step ${page + 1} of 3"
        }, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            repeat(3) { index -> Box(Modifier.size(if (index == page) 28.dp else 8.dp, 8.dp)
                .background(if (index == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))) }
        }
        HorizontalPager(pager, Modifier.weight(1f).testTag("cloud.introduction.pager")) { index ->
            val title = when (index) {
                0 -> "Your workspace lives in the Cloud"
                1 -> "System VPN"
                else -> "A private key keeps it private"
            }
            val message = when (index) {
                0 -> "Workspaces keep their terminals, browsers, and coding agents running on a Cloud machine. Open your Cloud terminals from Workspaces alongside your other computers."
                1 -> "A system VPN lets a browser or another app reach a private service on a Cloud machine. Cloud terminals use their own secure connection."
                else -> "cmux encrypts this phone's Cloud private key with a key protected by Android Keystore. The Cloud private key stays on this phone."
            }
            val copy: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                        textAlign = if (wide) TextAlign.Start else TextAlign.Center, modifier = Modifier.semantics { heading() })
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = if (wide) TextAlign.Start else TextAlign.Center)
                    if (index == 1 && vpnControl == null) Text("System VPN controls are unavailable in this session.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("cloud.introduction.vpn.status"))
                    if (index == 1) vpnControl?.invoke()
                }
            }
            val visual: @Composable () -> Unit = {
                Row(Modifier.heightIn(min = 100.dp, max = 200.dp).padding(24.dp).clearAndSetSemantics {},
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (index != 2) {
                        Icon(painterResource(R.drawable.ic_menu_phone), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("···", style = MaterialTheme.typography.headlineLarge)
                    }
                    Icon(painterResource(if (index == 2) R.drawable.ic_workspace_lock else R.drawable.ic_workspace_cloud),
                        null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), contentAlignment = Alignment.TopCenter) {
                if (wide) Row(Modifier.widthIn(max = 980.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { copy() }; Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { visual() }
                } else Column(Modifier.widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)) { copy(); visual() }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Button(onClick = { if (page < 2) move(page + 1) else onComplete() }, enabled = !pager.isScrollInProgress,
                modifier = Modifier.fillMaxWidth().testTag("cloud.introduction.next")) { Text(if (page < 2) "Continue" else "Get started") }
            if (page > 0) TextButton(onClick = { move(page - 1) }, enabled = !pager.isScrollInProgress,
                modifier = Modifier.fillMaxWidth().testTag("cloud.introduction.back")) { Text("Back") }
        }
    }
}
