package io.github.docmorphic.cmuxapp

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlinx.coroutines.delay
import kotlin.math.min

/** Draws cmux's styled cell grid at fixed cell coordinates, preserving TUI layout. */
@Composable
fun RenderGridView(
    grid: RenderGrid, cells: TerminalCellMetrics,
    modifier: Modifier = Modifier, scrollOffset: Int = 0
) {
    val fallback = Color(0xFF111316)
    var blinkVisible by remember(grid) { mutableStateOf(true) }
    LaunchedEffect(grid) {
        while (true) { delay(600); blinkVisible = !blinkVisible }
    }
    Box(modifier.background(fallback)) {
        Canvas(Modifier.fillMaxSize()) {
            if (grid.columns == 0 || grid.rows == 0) return@Canvas
            val cellWidth = min(cells.widthPx, size.width / grid.columns)
            val cellHeight = min(cells.heightPx, size.height / grid.rows)
            val originX = ((size.width - cellWidth * grid.columns) / 2f).coerceAtLeast(0f)
            val originY = ((size.height - cellHeight * grid.rows) / 2f).coerceAtLeast(0f)
            val background = parseColor(grid.background, android.graphics.Color.rgb(17, 19, 22))
            val foreground = parseColor(grid.foreground, android.graphics.Color.rgb(224, 229, 235))
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                    textSize = cells.fontSizePx *
                        min(cellWidth / cells.widthPx, cellHeight / cells.heightPx)
                }
                native.drawColor(background)
                val baselineOffset = (cellHeight - (paint.fontMetrics.descent + paint.fontMetrics.ascent)) / 2f
                val allLines = grid.scrollbackLines + grid.lines
                val firstLine = (allLines.size - grid.rows - scrollOffset).coerceAtLeast(0)
                allLines.drop(firstLine).take(grid.rows).forEachIndexed { row, spans ->
                    for (span in spans) {
                        val x = originX + span.column * cellWidth
                        val y = originY + row * cellHeight
                        val style = span.style
                        var fg = parseColor(style.foreground, foreground)
                        var bg = parseColor(style.background, background)
                        if (style.inverse) { val swapped = fg; fg = bg; bg = swapped }
                        if (bg != background) {
                            paint.alpha = 255
                            paint.color = bg
                            native.drawRect(x, y, x + span.width * cellWidth, y + cellHeight, paint)
                        }
                        if (!style.invisible && (!style.blink || blinkVisible)) {
                            paint.color = fg
                            paint.alpha = if (style.faint) 150 else 255
                            paint.typeface = Typeface.create(Typeface.MONOSPACE,
                                (if (style.bold) Typeface.BOLD else Typeface.NORMAL) or
                                    (if (style.italic) Typeface.ITALIC else Typeface.NORMAL))
                            paint.textScaleX = 1f
                            val measuredWidth = paint.measureText(span.text)
                            if (measuredWidth > 0f) paint.textScaleX = span.width * cellWidth / measuredWidth
                            native.drawText(span.text, x, y + baselineOffset, paint)
                            if (style.underline) {
                                native.drawRect(x, y + cellHeight - 2f, x + span.width * cellWidth, y + cellHeight, paint)
                            }
                            if (style.strikethrough) {
                                native.drawRect(x, y + cellHeight * 0.52f, x + span.width * cellWidth,
                                    y + cellHeight * 0.52f + 2f, paint)
                            }
                            if (style.overline) {
                                native.drawRect(x, y + 1f, x + span.width * cellWidth, y + 3f, paint)
                            }
                        }
                    }
                }
                grid.cursor?.takeIf { scrollOffset == 0 && it.visible && (!it.blinking || blinkVisible) &&
                    it.row in 0 until grid.rows && it.column in 0 until grid.columns }?.let { cursor ->
                    paint.color = foreground
                    paint.alpha = 160
                    val x = originX + cursor.column * cellWidth
                    val y = originY + cursor.row * cellHeight
                    when (cursor.style) {
                        "bar" -> native.drawRect(x, y, x + 2f, y + cellHeight, paint)
                        "underline" -> native.drawRect(x, y + cellHeight - 2f, x + cellWidth, y + cellHeight, paint)
                        else -> native.drawRect(x, y, x + cellWidth, y + cellHeight, paint)
                    }
                }
            }
        }
    }
}

private fun parseColor(value: String?, fallback: Int): Int = try {
    if (value.isNullOrBlank()) fallback else android.graphics.Color.parseColor(value)
} catch (_: IllegalArgumentException) { fallback }
