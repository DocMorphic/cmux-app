package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.abs

internal data class NativeChiefBubbleMetrics(val radius: Float, val drop: Dp, val horizontal: Dp,
    val vertical: Dp, val accessibility: Boolean, val captionLine: Double, val density: Double) {
    fun space(base: Float) = NativeChiefBubbleGeometry.space(base.toDouble(), captionLine, density).toFloat().dp
}

@Composable
internal fun nativeChiefBubbleMetrics(): NativeChiefBubbleMetrics {
    val density = LocalDensity.current
    val style = nativeChiefTextStyle()
    val captionFont = nativeChiefCaptionFont()
    val measurer = rememberTextMeasurer()
    return remember(measurer, density, style, captionFont) {
        val line = measurer.measure("Hg", style).let { it.getLineBottom(0) - it.getLineTop(0) }
        val accessibility = with(density) { 17.sp.toDp().value >= 28 }
        val vertical = if (accessibility) 12.dp else 10.dp
        val radius = (line + with(density) { 2 * vertical.toPx() }) / 2
        NativeChiefBubbleMetrics(radius, with(density) { NativeChiefBubbleGeometry.tailDrop(radius.toDouble()).toFloat().toDp() },
            if (accessibility) (50f / 3).dp else 14.dp, vertical, accessibility,
            captionFont.spacing, density.density.toDouble())
    }
}

/** The body's height excludes its tail. Reserving the drop protects the last visible tail; the
 * next row subtracts that reservation so run spacing remains measured from body to body. */
@Composable
internal fun NativeChiefBubble(outgoing: Boolean, tail: Boolean, modifier: Modifier = Modifier,
    bodyTag: String? = null, expanded: Boolean = false, quote: Boolean = false,
    text: String? = null,
    metrics: NativeChiefBubbleMetrics = nativeChiefBubbleMetrics(),
    content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val view = LocalView.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val textColor = if (outgoing || dark) Color.White else Color.Black
    val blue = if (dark) Color(0xFF409CFF) else Color(0xFF007AFF)
    val quoteColor = if (outgoing) blue else if (dark) Color.White.copy(alpha = .30f) else Color.Black.copy(alpha = .22f)
    var windowTop by remember { mutableFloatStateOf(0f) }
    var windowHeight by remember { mutableFloatStateOf(1f) }
    val bottomInset = WindowInsets.navigationBars.getBottom(density)
    val reserve = if (tail && !quote) metrics.drop else 0.dp
    val reservePx = with(density) { reserve.toPx() }
    val strokePx = with(density) { 1.dp.toPx() }
    val entryHeight = with(density) { 35.dp.toPx() }
    val bodyStyle = nativeChiefTextStyle()
    val measurementStyle = nativeChiefTextStyle(if (quote) 15 else 17)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val available = maxWidth.value.toDouble()
        val maximum = (if (expanded) available else NativeChiefBubbleGeometry.maxWidth(available, metrics.accessibility)).coerceAtLeast(0.0).toFloat().dp
        val minimum = if (quote) 0.dp else minOf(48.dp, maximum)
        val horizontal = if (quote) 12.dp else metrics.horizontal
        val vertical = if (quote) 7.dp else metrics.vertical
        val hPx = with(density) { horizontal.toPx() }
        val vPx = with(density) { vertical.toPx() }
        val rawWidth = remember(text, maximum, horizontal, measurer, measurementStyle, density) {
            text?.let {
                val limit = with(density) { maximum.toPx() - 2 * hPx }.toInt().coerceAtLeast(0)
                val layout = measurer.measure(it, measurementStyle, constraints = Constraints(maxWidth = limit))
                (0 until layout.lineCount).maxOfOrNull { line -> abs(layout.getLineRight(line) - layout.getLineLeft(line)) } ?: 0f
            }
        }
        Box(Modifier.onGloballyPositioned {
            windowTop = it.positionInWindow().y; windowHeight = view.rootView.height.toFloat()
        }.drawWithCache {
            val height = (size.height - reservePx).coerceAtLeast(0f)
            val radius = minOf(metrics.radius, size.width / 2, height / 2).coerceAtLeast(0f)
            val path = if (!tail) Path().apply {
                // UIKit's tailless wrapper uses circular corners, not the shared continuous path.
                addRoundRect(RoundRect(0f, 0f, size.width, height, CornerRadius(radius)))
            } else Path().apply {
                NativeChiefBubbleGeometry.outline(size.width.toDouble(), height.toDouble(), metrics.radius.toDouble(), outgoing, true)
                    .forEach { c ->
                        val p = c.points
                        when (c.kind) {
                            "move" -> moveTo(p[0].x.toFloat(), p[0].y.toFloat())
                            "line" -> lineTo(p[0].x.toFloat(), p[0].y.toFloat())
                            "curve" -> cubicTo(p[0].x.toFloat(), p[0].y.toFloat(), p[1].x.toFloat(), p[1].y.toFloat(), p[2].x.toFloat(), p[2].y.toFloat())
                            "close" -> close()
                        }
                    }
            }
            val stops = if (dark) listOf(Color(0xFF409CFF), Color(0xFF3499FF), Color(0xFF2596FF), Color(0xFF1694FF), Color(0xFF0091FF))
                else listOf(Color(0xFF5AC8FA), Color(0xFF49B9FB), Color(0xFF35A9FC), Color(0xFF2099FE), Color(0xFF0088FF))
            val span = (windowHeight - bottomInset - entryHeight).coerceAtLeast(1f)
            val brush = Brush.verticalGradient(stops, startY = 0f, endY = span)
            onDrawBehind {
                when {
                    quote -> drawPath(path, quoteColor, style = Stroke(strokePx))
                    outgoing -> clipPath(path) {
                        // Read the scrolling position in draw, leaving the path and shader cached.
                        translate(top = -windowTop) { drawRect(brush, topLeft = Offset(0f, windowTop), size = size) }
                    }
                    else -> drawPath(path, if (dark) Color(0xFF262629) else Color(0xFFE9E9EB))
                }
            }
        }.padding(bottom = reserve)) {
            CompositionLocalProvider(LocalTextStyle provides bodyStyle, LocalContentColor provides textColor) {
                val tag = if (bodyTag != null) Modifier.testTag(bodyTag) else Modifier
                if (rawWidth != null) Layout(content = { Column(content = content) }, modifier = tag) { children, constraints ->
                    // Measure at the wrapping limit, then hug the longest actual line. A Text's
                    // default wrapped box fills the limit, unlike NSString.boundingRect.
                    val child = children.single().measure(Constraints(maxWidth = ceil(rawWidth).toInt().coerceAtLeast(0)))
                    val width = maxOf(with(density) { minimum.toPx() }.roundToInt(), ceil(rawWidth + 2 * hPx - .001f).toInt())
                        .coerceIn(constraints.minWidth, constraints.maxWidth)
                    val height = (child.height + 2 * vPx).roundToInt()
                    val x = if (width > rawWidth + 2 * hPx + 1) (width - child.width) / 2 else hPx.roundToInt()
                    layout(width, height) { child.place(x, vPx.roundToInt()) }
                } else Column(Modifier.widthIn(min = minimum, max = maximum).then(tag)
                    .padding(horizontal = horizontal, vertical = vertical),
                    horizontalAlignment = if (expanded) Alignment.Start else Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}

/** Quotes sit above the reply on their original sender's side. The leading connector is painted
 * behind both outlines, using the same control points as MessageCellLayout's direct-message path. */
@Composable
internal fun NativeChiefBalloonRow(quoted: NativeChiefMessage?, outgoing: Boolean, tail: Boolean,
    messageId: String, expanded: Boolean, text: String?, metrics: NativeChiefBubbleMetrics,
    emojiSize: Int? = null, modifier: Modifier = Modifier, pending: Boolean = false,
    onQuote: (() -> Unit)? = null, onSwipeReply: ((Float) -> Unit)? = null,
    onBubblePositioned: ((androidx.compose.ui.layout.LayoutCoordinates) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    var quoteSize by remember { mutableStateOf(IntSize.Zero) }
    var bodySize by remember { mutableStateOf(IntSize.Zero) }
    val gap = metrics.space(6f)
    Column(modifier.fillMaxWidth().drawBehind {
        if (quoted != null && quoteSize.height > 0 && bodySize.height > 0) {
            val x = 16.dp.toPx()
            val y = quoteSize.height / 2f
            val quoteLeft = if (quoted.authorKind == "human") size.width - quoteSize.width else 0f
            val primaryHeight = bodySize.height - if (tail && emojiSize == null) with(density) { metrics.drop.toPx() } else 0f
            val end = maxOf(y + 28.dp.toPx(), quoteSize.height + gap.toPx() + primaryHeight / 2)
            val path = Path().apply {
                moveTo(minOf(quoteLeft - 4.dp.toPx(), x + 14.dp.toPx()), y)
                quadraticTo(x, y, x, y + 14.dp.toPx())
                lineTo(x, end - 14.dp.toPx())
                quadraticTo(x, end, x + 14.dp.toPx(), end)
            }
            drawPath(path, if (dark) Color.White.copy(alpha = .28f) else Color.Black.copy(alpha = .18f),
                style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
    }) {
        if (quoted != null) {
            val fromMe = quoted.authorKind == "human"
            val quoteText = NativeChiefPresentation.receipt(quoted) ?: NativeChiefPresentation.displayText(quoted)
            NativeChiefBubble(fromMe, true, Modifier.align(if (fromMe) AbsoluteAlignment.Right else AbsoluteAlignment.Left)
                .testTag("ChiefQuote:$messageId").then(if (onQuote != null) Modifier.clickable(onClick = onQuote) else Modifier).onSizeChanged { quoteSize = it }, quote = true, text = quoteText, metrics = metrics) {
                Text(quoteText,
                    style = nativeChiefTextStyle(15), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (fromMe) Color(0xFF409CFF) else if (dark) Color(0xFF8E8E93) else Color(0xFF8A8A8E))
            }
            Spacer(Modifier.height(gap))
        }
        NativeChiefReplySwipe(messageId, outgoing, onSwipeReply != null && !pending,
            if (tail && emojiSize == null) metrics.drop else 0.dp, { onSwipeReply?.invoke(it) }) { swipeModifier ->
        val bodyModifier = swipeModifier
            .testTag(if (pending) "ChiefPending:$messageId" else "ChiefMessage:$messageId").onSizeChanged { bodySize = it }
            .onGloballyPositioned { onBubblePositioned?.invoke(it) }
        if (emojiSize != null && text != null) NativeChiefEmojiBalloon(text, emojiSize, messageId, bodyModifier, metrics)
        else NativeChiefBubble(outgoing, tail, bodyModifier, if (pending) "ChiefPendingBody:$messageId" else "ChiefBubbleBody:$messageId", expanded,
                text = text, metrics = metrics, content = content)
        }
    }
}

/** iOS 26's plain big-emoji balloon: no fill/tail, fixed glyph size and source vertical room.
 * Android supplies its system emoji artwork and font metrics. */
@Composable
internal fun NativeChiefEmojiBalloon(text: String, fontSize: Int, messageId: String,
    modifier: Modifier = Modifier, metrics: NativeChiefBubbleMetrics = nativeChiefBubbleMetrics()) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val maximum = NativeChiefBubbleGeometry.maxWidth(maxWidth.value.toDouble(), metrics.accessibility).coerceAtLeast(0.0).toFloat().dp
        val style = nativeChiefTextStyle(fontSize).copy(fontSize = with(density) { fontSize.dp.toSp() })
        Box(Modifier.widthIn(max = maximum).testTag("ChiefEmoji:$messageId")
            .padding(top = 10.dp, bottom = (NativeChiefEmojiPolicy.verticalRoom(metrics.accessibility).toFloat() - 10).dp)) {
            Text(text, Modifier.testTag("ChiefEmojiGlyph:$messageId"), style = style)
        }
    }
}
