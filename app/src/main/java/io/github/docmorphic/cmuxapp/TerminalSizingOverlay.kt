package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Only the chip receives touches; the decoration cannot intercept terminal gestures. */
@Composable
internal fun TerminalSizingOverlay(presentation: TerminalSizingPresentation, display: TerminalDisplay,
    cells: TerminalCellMetrics, displayGeometry: TerminalGeometry? = null, onOpen: () -> Unit) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
    val compact = "${presentation.state.grid.columns}×${presentation.state.grid.rows}"
    fun parse(value: String, fallback: Color) = runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(fallback)
    val background = parse(if (display.reverseVideo) display.foreground else display.background, Color(0xFF111316))
    val foreground = parse(if (display.reverseVideo) display.background else display.foreground, Color(0xFFE0E5EB))
    val fill = lerp(background, foreground, .035f)
    val glyph = sizingContrast(lerp(background, foreground, .85f), fill, 4.5f)
    val line = sizingContrast(lerp(background, foreground, .45f), background, 3f)
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().testTag("terminal-sizing-overlay")) {
        val viewport = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val geometry = displayGeometry ?: TerminalGeometry.fit(viewport.width, viewport.height, display.columns, display.rows, cells)
            ?: return@BoxWithConstraints
        val render = Rect(geometry.originX, geometry.originY, geometry.originX + display.columns * geometry.cellWidth,
            geometry.originY + display.rows * geometry.cellHeight)
        val bounds = TerminalSizingChrome.bounds(viewport, render, density.density)
        val title = "$compact · ${presentation.ownerLabel}" + if (geometry.scale < .999f) " · scaled" else ""
        fun fitting(text: String): Size {
            val measured = measurer.measure(text, style, maxLines = 1).size
            return Size(measured.width + with(density) { 20.dp.toPx() }, measured.height + with(density) { 10.dp.toPx() })
        }
        val placement = TerminalSizingChrome.chip(viewport, bounds.grid, fitting(title), fitting(compact), with(density) { 6.dp.toPx() })
        Canvas(Modifier.fillMaxSize()) {
            bounds.hatch.forEach { band -> clipRect(band.left, band.top, band.right, band.bottom) {
                var x = -size.height
                while (x < size.width) {
                    drawLine(lerp(background, foreground, .12f), Offset(x, size.height), Offset(x + size.height, 0f), 1.dp.toPx())
                    x += 8.dp.toPx()
                }
            } }
            val r = bounds.grid
            val half = .5.dp.toPx()
            bounds.edges.forEach { edge ->
                val (start, end) = when (edge) {
                    TerminalSizingChrome.Edge.TOP -> Offset(r.left + half, r.top + half) to Offset(r.right - half, r.top + half)
                    TerminalSizingChrome.Edge.RIGHT -> Offset(r.right - half, r.top + half) to Offset(r.right - half, r.bottom - half)
                    TerminalSizingChrome.Edge.BOTTOM -> Offset(r.left + half, r.bottom - half) to Offset(r.right - half, r.bottom - half)
                    TerminalSizingChrome.Edge.LEFT -> Offset(r.left + half, r.top + half) to Offset(r.left + half, r.bottom - half)
                }
                drawLine(line, start, end, 1.dp.toPx())
            }
            bounds.cuts.forEach { edge ->
                val dx = minOf(16.dp.toPx(), r.width / 2)
                val dy = minOf(16.dp.toPx(), r.height / 2)
                val fade = when (edge) {
                    TerminalSizingChrome.Edge.TOP -> Rect(r.left, r.top, r.right, r.top + dy)
                    TerminalSizingChrome.Edge.RIGHT -> Rect(r.right - dx, r.top, r.right, r.bottom)
                    TerminalSizingChrome.Edge.BOTTOM -> Rect(r.left, r.bottom - dy, r.right, r.bottom)
                    TerminalSizingChrome.Edge.LEFT -> Rect(r.left, r.top, r.left + dx, r.bottom)
                }
                val (start, end) = when (edge) {
                    TerminalSizingChrome.Edge.TOP -> fade.bottomLeft to fade.topLeft
                    TerminalSizingChrome.Edge.RIGHT -> fade.topLeft to fade.topRight
                    TerminalSizingChrome.Edge.BOTTOM -> fade.topLeft to fade.bottomLeft
                    TerminalSizingChrome.Edge.LEFT -> fade.topRight to fade.topLeft
                }
                drawRect(Brush.linearGradient(listOf(background.copy(alpha = 0f), background.copy(alpha = .85f)), start, end),
                    fade.topLeft, fade.size)
            }
        }
        val shape = RoundedCornerShape(50)
        Box(Modifier.offset { IntOffset(placement.frame.left.roundToInt(), placement.frame.top.roundToInt()) }
            .width(with(density) { placement.frame.width.toDp() }).clip(shape).background(fill).border(1.dp, line, shape)
            .clickable(role = Role.Button, onClick = onOpen).testTag("terminal-sizing-chip")
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = "Terminal size, $title. Open size controls"
                onClick("Open terminal size controls") { onOpen(); true }
            }.padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text(if (placement.anchor == TerminalSizingChrome.Anchor.COMPACT) compact else title,
                style = style, color = glyph, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Keep themed controls readable even when the terminal theme itself has low contrast. */
internal fun sizingContrast(candidate: Color, background: Color, minimum: Float): Color {
    fun ratio(color: Color): Float {
        val a = color.luminance(); val b = background.luminance()
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }
    if (ratio(candidate) >= minimum) return candidate
    val target = if (ratio(Color.White) >= ratio(Color.Black)) Color.White else Color.Black
    for (step in 1..100) {
        val color = lerp(candidate, target, step / 100f)
        if (ratio(color) >= minimum) return color
    }
    return target
}
