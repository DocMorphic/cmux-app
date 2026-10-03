package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun NativeWhatsNewArchive(pages: List<WhatsNewPage>, policy: NativeMacCompatibilityPolicy,
    onDismiss: () -> Unit) {
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = pages.singleOrNull { it.key == selectedKey }
    fun back() { if (selected != null) selectedKey = null else onDismiss() }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("whatsnew.archive")) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::back, modifier = Modifier.testTag("whatsnew.archive.back")) { Text("‹ Back") }
                    Text("What's New", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                if (selected != null) NativeWhatsNewPageBody(selected, policy, Modifier.weight(1f))
                else Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    if (pages.isEmpty()) Text("No announcements right now.", Modifier.padding(vertical = 24.dp))
                    pages.forEach { page ->
                        Column(Modifier.fillMaxWidth().clickable { selectedKey = page.key }
                            .testTag("whatsnew.archive.${page.key}").padding(vertical = 18.dp)) {
                            Text(page.title, fontWeight = FontWeight.SemiBold)
                            if (page.kind == WhatsNewKind.ANNOUNCEMENT) Text("Announcement", color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge)
                            else page.releaseLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
internal fun NativeWhatsNewLaunchSheet(presentation: WhatsNewPresentation, policy: NativeMacCompatibilityPolicy,
    error: String?, onAppeared: () -> Unit, onPage: (Int) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        var laidOut by remember { mutableStateOf(false) }
        val focused = LocalWindowInfo.current.isWindowFocused
        val appear by rememberUpdatedState(onAppeared)
        LaunchedEffect(laidOut, focused) {
            if (laidOut && focused) { withFrameNanos { }; appear() }
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
            val large = LocalDensity.current.fontScale >= 1.3f || maxHeight < 600.dp
            val height = if (large) maxHeight else minOf(maxHeight, 660.dp)
            Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().height(height).testTag("whatsnew.sheet")
                .onGloballyPositioned { laidOut = it.isAttached && it.size.width > 0 && it.size.height > 0 },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("What's New", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("whatsnew.close")) { Text("Done") }
                    }
                    val pager = rememberPagerState(initialPage = presentation.pageIndex) { presentation.pages.size }
                    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { onPage(it) } }
                    HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f).testTag("whatsnew.pager"),
                        key = { presentation.pages[it].key }) { index ->
                        NativeWhatsNewPageBody(presentation.pages[index], policy, Modifier.fillMaxSize())
                    }
                    error?.let { Text(it, Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.error) }
                    val scope = rememberCoroutineScope()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${pager.currentPage + 1} of ${presentation.pages.size}",
                            Modifier.semantics { stateDescription = "Page ${pager.currentPage + 1} of ${presentation.pages.size}" },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            if (pager.currentPage == presentation.pages.lastIndex) onDismiss()
                            else scope.launch { pager.scrollToPage(pager.currentPage + 1) }
                        }, modifier = Modifier.fillMaxWidth().testTag("whatsnew.continue")) { Text("Continue") }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeWhatsNewPageBody(page: WhatsNewPage, policy: NativeMacCompatibilityPolicy, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Image(painterResource(R.drawable.cmux_logo), "cmux", Modifier.size(48.dp))
        page.releaseLabel?.let { Text(it, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(page.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }.testTag("whatsnew.title.${page.key}"))
        when (val body = page.body) {
            is WhatsNewBody.Features -> body.rows.forEach { feature ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(feature.title, style = MaterialTheme.typography.titleMedium)
                    Text(feature.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            WhatsNewBody.Pairing -> {
                Text("On your Mac, open cmux Settings > Mobile and turn on Enable iOS pairing. This setting also enables the Android companion.")
                Image(painterResource(if (MaterialTheme.colorScheme.surface.luminance() < .5f)
                    R.drawable.mac_pairing_settings_dark else R.drawable.mac_pairing_settings_light),
                    "cmux Mac Settings showing Enable iOS pairing", Modifier.fillMaxWidth().aspectRatio(1030f / 285f)
                        .clip(MaterialTheme.shapes.medium).testTag("whatsnew.pairing.image"))
                Text("Use the same cmux account and team on your Mac and this phone. Your Mac appears in Computers after mobile pairing is enabled.")
                policy.pairingMinimumCopy()?.let { Text(it) }
            }
            is WhatsNewBody.Web -> Text("Web announcements aren’t available in this development build yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun NativeWhatsNewHost(center: NativeWhatsNewCenter, presentation: NativeWhatsNewPresentation,
    owner: String?, eligible: Boolean, archive: Boolean, onCloseArchive: () -> Unit,
    policy: NativeMacCompatibilityPolicy) {
    val state by center.state.collectAsState()
    val sheet by presentation.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val allowed = eligible && resumed
    LaunchedEffect(owner, allowed, archive, state.unseen, state.initialRefreshComplete) {
        presentation.reconcile(owner, allowed && !archive)
    }
    if (archive && owner != null) NativeWhatsNewArchive(state.archive, policy, onCloseArchive)
    else sheet?.takeIf { allowed && it.owner == owner }?.let { active ->
        key(active.token) {
            NativeWhatsNewLaunchSheet(active, policy, state.error,
                { presentation.appeared(active.token, owner, allowed && !archive) },
                { presentation.select(active.token, it) }, { presentation.dismiss(active.token) })
        }
    }
}

@Composable
internal fun nativeWhatsNewSshPromptPending(runtime: NativeSshRuntime?): Boolean {
    val resource = runtime?.state?.collectAsState()?.value?.resource ?: return false
    val prompts by resource.connections.prompts.collectAsState()
    val biometrics by resource.biometrics.collectAsState()
    val installations by resource.installPrompts.collectAsState()
    return prompts.isNotEmpty() || biometrics.isNotEmpty() || installations.isNotEmpty()
}
