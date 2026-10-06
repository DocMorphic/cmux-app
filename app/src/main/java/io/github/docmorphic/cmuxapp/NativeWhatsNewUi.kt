package io.github.docmorphic.cmuxapp

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
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
    webArchive: NativeNoticeArchiveOwner? = null, replay: NativeWhatsNewReplay? = null,
    owner: String? = null, onDismiss: () -> Unit) {
    var showReplay by rememberSaveable { mutableStateOf(false) }
    val replayAvailable = BuildConfig.DEBUG && replay != null && owner != null
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = pages.singleOrNull { it.key == selectedKey }
    fun back() {
        if (showReplay) { replay?.dismiss(); webArchive?.dismiss(); showReplay = false }
        else if (selected != null) { webArchive?.dismiss(); selectedKey = null }
        else onDismiss()
    }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("whatsnew.archive")) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::back, modifier = Modifier.testTag("whatsnew.archive.back")) { Text("‹ Back") }
                    Text("What's New", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (replayAvailable && !showReplay) TextButton(onClick = { showReplay = true },
                        modifier = Modifier.testTag("whatsnew.replay.open")) { Text("Replay") }
                }
                if (showReplay && replayAvailable) NativeWhatsNewReplayUi(pages, checkNotNull(replay),
                    checkNotNull(owner), policy, webArchive)
                else if (selected?.body is WhatsNewBody.Web) NativeNoticeArchiveWeb(selected, webArchive, Modifier.weight(1f))
                else if (selected != null) NativeWhatsNewPageBody(selected, policy, Modifier.weight(1f))
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
    error: String?, onAppeared: () -> Unit, onPage: (Int) -> Unit, onDismiss: () -> Unit,
    webPage: (WhatsNewPage) -> NativeNoticeRenderer? = { null },
    webContent: (@Composable (WhatsNewPage) -> Unit)? = null) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        var laidOut by remember { mutableStateOf(false) }
        val focused = LocalWindowInfo.current.isWindowFocused
        val appear by rememberUpdatedState(onAppeared)
        LaunchedEffect(laidOut, focused) {
            if (laidOut && focused) { withFrameNanos { }; appear() }
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
            val density = LocalDensity.current
            val pager = rememberPagerState(initialPage = presentation.pageIndex) { presentation.pages.size }
            // Natural compact content height does not depend on the viewport height.
            // Insets can change maxHeight without remeasuring unchanged content; clearing
            // the cache then would leave the first page stuck at the full-height fallback.
            val pageHeights = remember(maxWidth, density.density, density.fontScale, presentation.pages) {
                mutableStateMapOf<String, Int>()
            }
            var headerHeight by remember(density) { mutableIntStateOf(0) }
            var footerHeight by remember(density) { mutableIntStateOf(0) }
            var errorHeight by remember(error, density) { mutableIntStateOf(0) }
            val selected = presentation.pages[pager.currentPage]
            val fullHeight = density.fontScale >= 1.3f || maxHeight < 600.dp || selected.body is WhatsNewBody.Web
            val measured = pageHeights[selected.key]
            val height = if (fullHeight || measured == null || headerHeight == 0 || footerHeight == 0) maxHeight
                else minOf(maxHeight, with(density) { (measured + headerHeight + footerHeight + errorHeight).toDp() })
            // Compose animations use Android's animator duration scale, including zero/reduced motion.
            val animatedHeight by animateDpAsState(height, tween(300), label = "What's New sheet height")
            Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().height(animatedHeight).testTag("whatsnew.sheet")
                .onGloballyPositioned { laidOut = it.isAttached && it.size.width > 0 && it.size.height > 0 },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("What's New", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("whatsnew.close")) { Text("Done") }
                    }
                    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { onPage(it) } }
                    HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f).testTag("whatsnew.pager"),
                        key = { presentation.pages[it].key }) { index ->
                        val page = presentation.pages[index]
                        if (page.body is WhatsNewBody.Web) {
                            if (webContent != null) webContent(page)
                            else {
                                val renderer = webPage(page)
                                NativeNoticeWebContent(renderer, renderer == null, Modifier.fillMaxSize())
                            }
                        } else NativeWhatsNewPageBody(page, policy, Modifier.fillMaxSize(), fitting = false) { pageHeights[page.key] = it }
                    }
                    error?.let { Text(it, Modifier.onSizeChanged { size -> errorHeight = size.height }.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.error) }
                    val scope = rememberCoroutineScope()
                    Column(Modifier.fillMaxWidth().onSizeChanged { footerHeight = it.height }.padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${pager.currentPage + 1} of ${presentation.pages.size}",
                            Modifier.semantics { stateDescription = "Page ${pager.currentPage + 1} of ${presentation.pages.size}" },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            if (pager.currentPage == presentation.pages.lastIndex) onDismiss()
                            else scope.launch { pager.animateScrollToPage(pager.currentPage + 1, animationSpec = tween(300)) }
                        }, modifier = Modifier.fillMaxWidth().testTag("whatsnew.continue")) { Text("Continue") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NativeWhatsNewHost(center: NativeWhatsNewCenter, presentation: NativeWhatsNewPresentation,
    owner: String?, eligible: Boolean, archive: Boolean, onCloseArchive: () -> Unit,
    policy: NativeMacCompatibilityPolicy, webArchive: NativeNoticeArchiveOwner? = null,
    isOwnerCurrent: (String) -> Boolean = { false },
    sessionCookies: suspend (String) -> List<okhttp3.Cookie> = { emptyList() },
    replay: NativeWhatsNewReplay? = null) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    SideEffect {
        webArchive?.configure(owner, center.webPolicy, isOwnerCurrent, sessionCookies)
        presentation.theme(dark)
        replay?.reconcile(owner, archive && BuildConfig.DEBUG)
    }
    LaunchedEffect(archive) { if (!archive) webArchive?.dismiss() }
    val state by center.state.collectAsState()
    val sheet by presentation.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val suppressed = BuildConfig.DEBUG && LocalSuppressWhatsNewLaunch.current
    val allowed = eligible && resumed && !suppressed
    LaunchedEffect(owner, allowed, archive, state.unseen, state.initialRefreshComplete) {
        presentation.reconcile(owner, allowed && !archive)
    }
    if (archive && owner != null) NativeWhatsNewArchive(state.archive, policy, webArchive, replay, owner) { webArchive?.dismiss(); onCloseArchive() }
    else sheet?.takeIf { allowed && it.owner == owner }?.let { active ->
        key(active.token) {
            NativeWhatsNewLaunchSheet(active, policy, state.error,
                { presentation.appeared(active.token, owner, allowed && !archive) },
                { presentation.select(active.token, it) }, { presentation.dismiss(active.token) },
                webPage = { presentation.webPage(it) as? NativeNoticeRenderer })
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
