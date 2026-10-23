package io.github.docmorphic.cmuxapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    machines: Map<String, CloudWorkspaceSnapshot> = emptyMap(), onRetryConnections: () -> Unit = {},
    connectionFailures: Map<String, CloudSessionFailure> = emptyMap()) {
    BackHandler(enabled = !LocalWorkspaceShellChrome.current.split, onBack = onBack)
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
            machines = machines, onRetryConnections = onRetryConnections, connectionFailures = connectionFailures)
        else NativeCloudIntroduction(false, error, onComplete = {
            runCatching { store.complete() }.onSuccess { completed = true; error = null }
                .onFailure { error = it.message ?: "Could not save Cloud introduction progress. Try again." }
        }, onBack = onBack, vpnControl = vpnControl)
    }
    if (replay) NativeModalBottomSheet(onDismissRequest = { replay = false },
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
    val reducedMotion = cloudOnboardingReducedMotion()
    fun move(index: Int) { scope.launch {
        if (reducedMotion) pager.scrollToPage(index.coerceIn(0, 2)) else pager.animateScrollToPage(index.coerceIn(0, 2))
    } }
    BackHandler(enabled = replay || !LocalWorkspaceShellChrome.current.split) { if (page > 0) move(page - 1) else onBack() }
    val configuration = LocalConfiguration.current
    val dark = NativeAppColors.dark
    val background = if (dark) Color.Black else Color.White
    val accent = cloudIntroductionTint(0, dark)
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Size classes refer to this actual pane. A wide device can have a
        // narrow Cloud sidebar. Compact-height controls also work at large type.
        val compact = maxHeight < 480.dp && maxWidth >= 560.dp
        val wide = compact || (maxWidth >= 700.dp && configuration.fontScale < 1.3f)
        NativeCloudOnboardingBackdrop(reducedMotion, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().testTag(if (replay) "cloud.introduction.replay" else "cloud.introduction.inline")) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onComplete, modifier = Modifier.testTag("cloud.introduction.skip")) { Text(stringResource(R.string.cmux_cloud_intro_skip)) }
                Text(stringResource(if (replay) R.string.cmux_cloud_intro_title else R.string.cmux_cloud_intro_cloud_title),
                    Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!replay && LocalWorkspaceShellChrome.current.split) NativeWorkspaceSidebarToggle()
                else Spacer(Modifier.width(64.dp))
            }
            val progressLabel = stringResource(R.string.cmux_cloud_intro_progress_label)
            val progressValue = stringResource(R.string.cmux_cloud_intro_progress_format, page + 1, 3)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).testTag("cloud.introduction.progress")
                .clearAndSetSemantics { contentDescription = progressLabel; stateDescription = progressValue },
                horizontalArrangement = Arrangement.Center) {
                Row(Modifier.drawBehind {
                    withTransform({ scale(1f, .22f, center) }) {
                        drawCircle(Brush.radialGradient(listOf(background, background, background.copy(alpha = 0f)), center, 100.dp.toPx()),
                            radius = 100.dp.toPx(), center = center)
                    }
                }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) { index ->
                        val width by animateDpAsState(if (index == page) 22.dp else 7.dp,
                            animationSpec = if (reducedMotion) snap() else tween(220), label = "Cloud page dot")
                        Box(Modifier.size(width, 7.dp).background(if (index == page) accent
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .24f), CircleShape))
                    }
                }
            }
            HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f).testTag("cloud.introduction.pager")) { index ->
                CloudIntroductionPage(index, compact, wide, vpnControl)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 568.dp).fillMaxWidth().padding(horizontal = 24.dp)
                    .padding(top = if (compact) 8.dp else 16.dp, bottom = if (compact) 8.dp else 12.dp)
                    .testTag("cloud.introduction.footer"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    val primary: @Composable (Modifier) -> Unit = { actionModifier ->
                        Button(onClick = { if (page < 2) move(page + 1) else onComplete() }, enabled = !pager.isScrollInProgress,
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                            shape = CircleShape, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 15.dp),
                            modifier = actionModifier.heightIn(min = 50.dp).testTag("cloud.introduction.next")) {
                            Text(stringResource(if (page < 2) R.string.cmux_cloud_intro_continue else R.string.cmux_cloud_intro_done),
                                style = TextStyle(fontSize = 17.sp, lineHeight = (20f + 1f / 3f).sp, letterSpacing = 0.sp,
                                    platformStyle = PlatformTextStyle(includeFontPadding = false)),
                                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        }
                    }
                    val secondary: @Composable (Modifier) -> Unit = { actionModifier ->
                        TextButton(onClick = { move(page - 1) }, enabled = page > 0 && !pager.isScrollInProgress,
                            colors = ButtonDefaults.textButtonColors(contentColor = accent),
                            modifier = actionModifier.heightIn(min = 44.dp).alpha(if (page > 0) 1f else 0f)
                                .then(if (page == 0) Modifier.clearAndSetSemantics {} else Modifier.testTag("cloud.introduction.back"))) {
                            Text(stringResource(R.string.cmux_cloud_intro_back), fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                        }
                    }
                    if (compact) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        primary(Modifier.weight(1f)); if (page > 0) secondary(Modifier.weight(1f))
                    } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        primary(Modifier.fillMaxWidth()); secondary(Modifier)
                    }
                }
            }
        }
    }
}

@Composable private fun CloudIntroductionPage(index: Int, compact: Boolean, wide: Boolean, vpnControl: (@Composable () -> Unit)?) {
    val dark = NativeAppColors.dark
    val title = stringResource(when (index) {
        0 -> R.string.cmux_cloud_intro_workspace_title
        1 -> R.string.cmux_cloud_intro_vpn_title
        else -> R.string.cmux_cloud_intro_key_title
    })
    val message = stringResource(when (index) {
        0 -> R.string.cmux_cloud_intro_workspace_message
        1 -> R.string.cmux_cloud_intro_android_vpn_message
        else -> R.string.cmux_cloud_intro_android_key_message
    })
    val copy: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("cloud.introduction.copy.$index")) {
            Text(title, fontSize = 34.sp, lineHeight = 41.sp, color = if (dark) Color.White else Color.Black,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                style = TextStyle(letterSpacing = 0.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)), modifier = Modifier.semantics { heading() })
            Text(message, fontSize = 17.sp, lineHeight = 22.sp, color = if (dark) Color(0x99EBEBF5) else Color(0x993C3C43),
                style = TextStyle(letterSpacing = 0.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)), textAlign = TextAlign.Center)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("cloud.introduction.page.$index")) {
        val availableHeight = maxHeight
        val horizontalPadding = if (compact) 16.dp else 48.dp
        val verticalPadding = if (compact) 4.dp else 32.dp
        val spacing = if (compact) 16.dp else 48.dp
        val innerWidth = (minOf(980.dp, maxWidth) - horizontalPadding * 2).coerceAtLeast(0.dp)
        // Source copy has layout priority and takes its capped width before the
        // flexible visual. Equal non-filling weights wrongly centered both
        // intrinsic children together and pushed the copy far toward the card.
        val copyWidth = minOf(if (compact) 280.dp else 390.dp,
            (innerWidth - spacing - if (compact) 88.dp else 152.dp).coerceAtLeast(0.dp))
        val visualWidth = minOf(520.dp, (innerWidth - copyWidth - spacing).coerceAtLeast(0.dp))
        val visualHeight = minOf(280.dp,
            (availableHeight - if (wide) verticalPadding * 2 else 20.dp).coerceAtLeast(0.dp))
        val visualContent: @Composable () -> Unit = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                CloudIntroductionGraphic(index, compact)
                // Android's permission warning/cleanup can exceed the source's
                // capped visual; its complete natural height stays reachable.
                if (index == 1) vpnControl?.invoke()
            }
        }
        // Preserve the source's centered page; scrolling is a fallback when
        // Android text scaling or the permission/error card needs more height.
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().heightIn(min = availableHeight), contentAlignment = Alignment.Center) {
                if (wide) Row(Modifier.widthIn(max = 980.dp).fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(copyWidth)) { copy() }
                    Box(Modifier.width(visualWidth).heightIn(min = visualHeight), contentAlignment = Alignment.Center) { visualContent() }
                } else CloudIntroductionNarrowLayout(availableHeight, copy, visualContent,
                    Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 8.dp))
            }
        }
    }
}

/** Measure priority copy first, then shrink the capped visual before adding scroll. */
@Composable private fun CloudIntroductionNarrowLayout(availableHeight: Dp, copy: @Composable () -> Unit,
    visual: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Layout(content = { Box { copy() }; Box { visual() } }, modifier = modifier) { children, constraints ->
        val width = constraints.maxWidth
        val copySize = children[0].measure(Constraints(maxWidth = minOf(width, 560.dp.roundToPx())))
        val visualSize = children[1].measure(Constraints(maxWidth = minOf(width, 520.dp.roundToPx())))
        val viewport = (availableHeight.roundToPx() - 20.dp.roundToPx()).coerceAtLeast(0)
        val gap = 18.dp.roundToPx()
        val remaining = (viewport - copySize.height - gap).coerceAtLeast(0)
        val visualFrame = maxOf(visualSize.height, minOf(280.dp.roundToPx(), remaining))
        val contentHeight = copySize.height + gap + visualFrame
        val height = maxOf(viewport, contentHeight)
        layout(width, height) {
            val top = (height - contentHeight) / 2
            copySize.placeRelative((width - copySize.width) / 2, top)
            visualSize.placeRelative((width - visualSize.width) / 2,
                top + copySize.height + gap + (visualFrame - visualSize.height) / 2)
        }
    }
}

@Composable private fun CloudIntroductionGraphic(index: Int, compact: Boolean) {
    val tint = cloudIntroductionTint(index, NativeAppColors.dark)
    val corner = if (compact) 20.dp else 30.dp
    val icon = if (compact) 32.dp else 58.dp
    Box(Modifier.size(if (compact) 88.dp else 152.dp).testTag("cloud.introduction.graphic.$index")
        .drawBehind {
            val radius = CornerRadius(corner.toPx())
            drawRoundRect(tint.copy(alpha = .14f), cornerRadius = radius)
            // SwiftUI .stroke is centered on the nominal card, including its
            // half-point outer extent; Modifier.border draws entirely inside.
            drawRoundRect(tint.copy(alpha = .30f), cornerRadius = radius, style = Stroke(1.dp.toPx()))
        }
        .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Icon(painterResource(when (index) {
            0 -> R.drawable.ic_cloud_intro_cloud
            1 -> R.drawable.ic_cloud_intro_shield
            else -> R.drawable.ic_cloud_intro_key
        }), null, when (index) {
            0 -> Modifier.size(icon * 1.3f, icon * .9f)
            2 -> Modifier.size(icon * .75f, icon * 1.3f)
            else -> Modifier.size(icon * 1.1f)
        }, tint = tint)
    }
}

/** Actual iOS 26.4.1 source palette captured in run 39894117425; local to onboarding. */
private fun cloudIntroductionTint(index: Int, dark: Boolean) = when (index) {
    0 -> if (dark) Color(0xFF0091FF) else Color(0xFF0088FF)
    1 -> if (dark) Color(0xFF30D158) else Color(0xFF34C759)
    else -> if (dark) Color(0xFFDB34F2) else Color(0xFFCB30E0)
}
