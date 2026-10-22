package io.github.docmorphic.cmuxapp

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class NativeChiefPollScrollProbe(val offset: Int, val maximum: Int, val drag: Float)
internal val ChiefPollScrollProbe = SemanticsPropertyKey<() -> NativeChiefPollScrollProbe>("ChiefPollScrollProbe")

/** Settled UIKit table measurements from native run 39762098177 / upstream
 * a5911a1f. Normal and accessibility-medium are rendered references; interpolation
 * for other categories and platform sheet motion remain subject to comparison. */
@Composable
internal fun NativeChiefPollSheet(content: NativeChiefPollContents, onClose: () -> Unit) {
    val preferredBodyPoints = with(LocalDensity.current) { 17.sp.toDp().value }
    NativeChiefPollSheetLayout(content, preferredBodyPoints, onClose)
}

/** The presentation consumes resolved font points, independently of the
 * platform's nonlinear system-scale conversion in NativeChiefPollSheet. */
@Composable
internal fun NativeChiefPollSheetLayout(content: NativeChiefPollContents, bodyPoints: Float, onClose: () -> Unit) {
    val inheritedDensity = LocalDensity.current
    // A presented UINavigationController uses the application's direction,
    // independently of a transcript view's semantic-direction override.
    val direction = if (LocalConfiguration.current.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        LayoutDirection.Rtl else LayoutDirection.Ltr
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }
    NativeDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        CompositionLocalProvider(LocalDensity provides inheritedDensity, LocalLayoutDirection provides direction) {
            BackHandler(onBack = onClose)
            val density = LocalDensity.current
            val progress = ((bodyPoints - 17f) / 11f).coerceIn(0f, 1f)
            val footerFont = NativeChiefPollFooterFonts.forBody(bodyPoints.toDouble())
            val rowHeight = 53f + 35f / 3f * progress
            val dark = MaterialTheme.colorScheme.background.luminance() < .5f
            val background = if (dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
            val cell = if (dark) Color(0xFF2C2C2E) else Color.White
            val ink = if (dark) Color.White else Color.Black
            val secondary = if (dark) Color(0x99EBEBF5) else Color(0x993C3C43)
            val separator = if (dark) Color(0x99545458) else Color(0x493C3C43)
            val pollTitle = stringResource(R.string.chief_menu_poll_details)
            val scroll = rememberScrollState()
            val scope = rememberCoroutineScope()
            val close by rememberUpdatedState(onClose)
            var drag by remember { mutableFloatStateOf(0f) }
            var settling by remember { mutableStateOf(false) }
            fun finishDrag() {
                if (drag >= with(density) { 80.dp.toPx() }) { close(); return }
                scope.launch { settling = true; try { animate(drag, 0f) { value, _ -> drag = value } } finally { settling = false } }
            }
            val nested = remember(scroll, density) { object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (settling || source != NestedScrollSource.UserInput || scroll.value != 0 || available.y <= 0) return Offset.Zero
                    drag += available.y
                    return Offset(0f, available.y)
                }
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (settling || source != NestedScrollSource.UserInput || drag <= 0 || available.y >= 0) return Offset.Zero
                    val used = available.y.coerceAtLeast(-drag); drag += used
                    return Offset(0f, used)
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (drag <= 0) return Velocity.Zero
                    finishDrag(); return available
                }
            } }
            BoxWithConstraints(Modifier.fillMaxSize().background(if (dark) Color.Black else Color(0xFFDADADA)).testTag("ChiefPollDetails")) {
                val landscape = maxWidth > maxHeight
                // UIKit's captured 62-point top / landscape safe sides belong
                // to that iPhone. Preserve component geometry inside the real
                // Android status-bar, navigation and cutout safe areas.
                val sheetTop = WindowInsets.statusBars.getTop(density).toFloat().div(density.density).dp
                val navGap = if (landscape) 24f else 16f
                val prompted = content.question.isNotEmpty()
                val headerHeight = navGap + if (prompted) 88f else 44f
                val safeLeft = (WindowInsets.safeDrawing.getLeft(density, direction) / density.density).dp
                val safeRight = (WindowInsets.safeDrawing.getRight(density, direction) / density.density).dp
                val groupedSafe = maxOf(safeLeft, safeRight)
                Column(Modifier.fillMaxSize().padding(top = sheetTop).offset { IntOffset(0, drag.roundToInt()) }
                    .clip(RoundedCornerShape(topStart = if (landscape) 0.dp else 32.dp, topEnd = if (landscape) 0.dp else 32.dp))
                    .background(background).testTag("ChiefPollSheet").semantics { isTraversalGroup = true; paneTitle = pollTitle }) {
                    Box(Modifier.fillMaxWidth().height(headerHeight.dp).testTag("ChiefPollHeader")
                        .pointerInput(density) { detectVerticalDragGestures(onDragEnd = { finishDrag() }, onDragCancel = { finishDrag() }) { change, amount ->
                            if (!settling) { change.consume(); drag = (drag + amount).coerceAtLeast(0f) }
                        } }) {
                        if (prompted) PollLabel(content.question, 13f, FontWeight.Normal, ink,
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp).offset(y = (navGap + 29f / 3f).dp).testTag("ChiefPollPrompt"),
                            maxLines = 1, align = TextAlign.Center)
                        val center = navGap + if (prompted) 56f else 22f
                        Box(Modifier.fillMaxWidth().height(44.dp).offset(y = (center - 22f).dp).padding(horizontal = 68.dp), Alignment.Center) {
                            PollLabel(pollTitle, 17f + 4f * progress, FontWeight.SemiBold, ink,
                                Modifier.fillMaxWidth().testTag("ChiefPollTitle").semantics(mergeDescendants = true) { heading() }, maxLines = 1, align = TextAlign.Center)
                        }
                        val done = stringResource(R.string.cmux_ui_terminal_artifact_gallery_done_ca8c7883)
                        Box(Modifier.align(Alignment.TopEnd).padding(end = 16.dp + if (direction == LayoutDirection.Rtl) safeLeft else safeRight).offset(y = (center - 22f).dp)
                            .size(44.dp).clip(CircleShape).background(if (dark) Color(0xFF0091FF) else Color(0xFF0088FF))
                            .testTag("ChiefPollDone").clickable(role = Role.Button, onClick = { close() }).semantics { contentDescription = done }, Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_menu_check), null, Modifier.size(25.dp), tint = Color.White)
                        }
                    }
                    Column(Modifier.fillMaxWidth().weight(1f).nestedScroll(nested).verticalScroll(scroll)
                        .testTag("ChiefPollTable").semantics {
                            if (BuildConfig.DEBUG) this[ChiefPollScrollProbe] = { NativeChiefPollScrollProbe(scroll.value, scroll.maxValue, drag) }
                        }.padding(horizontal = 20.dp + groupedSafe)
                        .padding(bottom = maxOf(if (landscape) 20.dp else 34.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))) {
                        @Composable fun Header(text: String, index: Int, nonVoters: Boolean = false) {
                            val base = if (nonVoters) 38f else if (index == 0) 166f / 3f else 136f / 3f
                            val top = if (nonVoters) 35f / 3f + 14f * progress else (if (index == 0) 86f / 3f else 56f / 3f) + 43f / 3f * progress
                            PollTableHeader(text, bodyPoints, secondary, base + 30f * progress, top,
                                Modifier.fillMaxWidth().testTag("ChiefPollSection:$index"))
                        }
                        @Composable fun Cell(text: String, tag: String, last: Boolean = true, placeholder: Boolean = false) {
                            PollTableRow(text, bodyPoints, rowHeight, if (placeholder) secondary else ink,
                                Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {}.drawWithContent {
                                    drawContent()
                                    if (!last) drawLine(separator, Offset(16.dp.toPx(), size.height - .5f), Offset(size.width - 16.dp.toPx(), size.height - .5f), 1f)
                                })
                        }
                        content.options.forEachIndexed { index, option ->
                            Header(option, index)
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(cell).testTag("ChiefPollGroup:$index")) {
                                Cell(stringResource(R.string.chief_poll_no_votes_yet), "ChiefPollOption:$index", placeholder = true)
                            }
                            PollTableFooter(stringResource(R.string.chief_poll_votes, 0), footerFont, secondary,
                                30f + 62f / 3f * progress, 23f / 3f + 4f * progress,
                                Modifier.fillMaxWidth().testTag("ChiefPollFooter:$index"))
                        }
                        Header(stringResource(R.string.chief_poll_no_votes), content.options.size, true)
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(cell).testTag("ChiefPollNonVoters")) {
                            content.participants.forEachIndexed { index, participant ->
                                Cell(participant.name, "ChiefPollParticipant:${participant.id}", index == content.participants.lastIndex)
                            }
                        }
                        Spacer(Modifier.height((52f / 3f).dp))
                    }
                }
            }
        }
    }
}

@Composable private fun PollTableHeader(text: String, points: Float, color: Color, minimum: Float, top: Float, modifier: Modifier) {
    val density = LocalDensity.current
    Layout(content = { PollLabel(text, points, FontWeight.SemiBold, color, Modifier.semantics(mergeDescendants = true) { heading() }) }, modifier = modifier) { children, c ->
        val inset = 16.dp.roundToPx()
        val child = children.single().measure(Constraints(maxWidth = (c.maxWidth - 2 * inset).coerceAtLeast(1)))
        val single = ceil(points * 1.193359375 * density.density).toInt()
        val height = maxOf((minimum * density.density).roundToInt() + (child.height - single).coerceAtLeast(0),
            (top * density.density).roundToInt() + child.height + 6.dp.roundToPx())
        layout(c.maxWidth, height) { child.place(inset, (top * density.density).roundToInt()) }
    }
}

@Composable private fun PollTableFooter(text: String, font: NativeChiefPollFooterFonts.Metrics, color: Color, minimum: Float, top: Float, modifier: Modifier) {
    val density = LocalDensity.current
    Layout(content = { PollLabel(text, font.pointSize.toFloat(), FontWeight.Normal, color, Modifier, nativeFont = font) }, modifier = modifier) { children, c ->
        val inset = 16.dp.roundToPx()
        val child = children.single().measure(Constraints(maxWidth = (c.maxWidth - 2 * inset).coerceAtLeast(1)))
        val y = (top * density.density).roundToInt()
        val height = maxOf((minimum * density.density).roundToInt(), y + child.height + (20.dp / 3).roundToPx())
        layout(c.maxWidth, height) { child.place(inset, y) }
    }
}

@Composable private fun PollTableRow(text: String, points: Float, minimum: Float, color: Color, modifier: Modifier) {
    val density = LocalDensity.current
    Layout(content = { PollLabel(text, points, FontWeight.Normal, color, Modifier) }, modifier = modifier) { children, c ->
        val inset = 16.dp.roundToPx()
        val child = children.single().measure(Constraints(maxWidth = (c.maxWidth - 2 * inset).coerceAtLeast(1)))
        val height = maxOf((minimum * density.density).roundToInt(), child.height + 30.dp.roundToPx())
        layout(c.maxWidth, height) { child.place(inset, (height - child.height) / 2) }
    }
}

/** Native line box and baseline; glyphs stay those of the actual Android font. */
@Composable private fun PollLabel(text: String, points: Float, weight: FontWeight, color: Color,
    modifier: Modifier, maxLines: Int = Int.MAX_VALUE, align: TextAlign = TextAlign.Start,
    nativeFont: NativeChiefPollFooterFonts.Metrics? = null) {
    val density = LocalDensity.current
    val style = nativeChiefTextStyle().copy(fontSize = with(density) { points.dp.toSp() }, fontWeight = weight)
    val measurer = rememberTextMeasurer()
    Layout(modifier = modifier, content = {
        Text(text, Modifier.testTag("ChiefPollLabel"), style = style, color = color, maxLines = maxLines,
            overflow = TextOverflow.Ellipsis, textAlign = align)
    }) { children, c ->
        val measured = measurer.measure(text, style, maxLines = maxLines, constraints = Constraints(maxWidth = c.maxWidth))
        val child = children.single().measure(Constraints.fixedWidth(c.maxWidth))
        val natural = (nativeFont?.lineHeight ?: (points * 1.193359375)) * measured.lineCount
        val nativeHeight = ceil(natural * density.density).toInt()
        val nativeBaseline = (((nativeFont?.ascender ?: (points * .9521484375)) + (nativeHeight / density.density - natural) / 2) * density.density).roundToInt()
        // A fallback script/font may have taller glyph metrics. Retain the
        // measured Latin box when it fits, and expand rather than clip when
        // Android's actual text needs more ascent or descent.
        val top = nativeBaseline - child[FirstBaseline]
        val extraTop = (-top).coerceAtLeast(0)
        val height = maxOf(nativeHeight + extraTop, child.height + top.coerceAtLeast(0))
        val baseline = nativeBaseline + extraTop
        layout(c.maxWidth, height, mapOf(FirstBaseline to baseline)) { child.place(0, baseline - child[FirstBaseline]) }
    }
}
