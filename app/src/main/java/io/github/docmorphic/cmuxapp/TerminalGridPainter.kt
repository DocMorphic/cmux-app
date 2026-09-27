package io.github.docmorphic.cmuxapp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Android canvas implementation shared by the live view and bitmap rendering checks. */
class TerminalGridPainter {
    data class PlacedSpan(val span: RenderGrid.Span, val glyphs: List<TerminalGlyphLayout.Glyph>)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val faces = (0..3).map { Typeface.create(Typeface.MONOSPACE, it) }

    fun draw(canvas: Canvas, width: Float, height: Float, grid: TerminalDisplay,
             lines: List<List<PlacedSpan>>, cells: TerminalCellMetrics, scrollOffset: Int, blinkVisible: Boolean) {
        val background = color(if (grid.reverseVideo) grid.foreground else grid.background, Color.rgb(17, 19, 22))
        canvas.drawColor(background)
        if (grid.columns <= 0 || grid.rows <= 0) return
        // A keyboard animation can resize the view before the host's next grid.
        // Scale both axes together so the transient frame cannot stretch letter spacing.
        val geometry = TerminalGeometry.fit(width, height, grid.columns, grid.rows, cells) ?: return
        val scale = geometry.scale
        val cellWidth = geometry.cellWidth; val cellHeight = geometry.cellHeight
        val originX = geometry.originX; val originY = geometry.originY
        val foreground = color(if (grid.reverseVideo) grid.background else grid.foreground, Color.rgb(224, 229, 235))
        paint.typeface = faces[0]
        paint.textSize = cells.fontSizePx * scale
        val baselineOffset = (cellHeight - (paint.fontMetrics.descent + paint.fontMetrics.ascent)) / 2f
        lines.forEachIndexed { row, spans ->
            for ((span, glyphs) in spans) {
                val x = originX + span.column * cellWidth
                val y = originY + row * cellHeight
                val style = span.style
                var fg = color(grid.foreground(style), foreground)
                var bg = color(grid.background(style), background)
                if (style.inverse) { val swapped = fg; fg = bg; bg = swapped }
                paint.alpha = 255; paint.color = bg
                canvas.drawRect(x, y, x + span.width * cellWidth, y + cellHeight, paint)
                if (!style.invisible && (!style.blink || blinkVisible)) {
                    paint.typeface = faces[(if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)]
                    paint.color = fg; paint.alpha = if (style.faint) 150 else 255
                    for (glyph in glyphs) {
                        val glyphX = originX + glyph.column * cellWidth
                        val allocated = glyph.width * cellWidth
                        paint.textScaleX = 1f
                        val measured = paint.measureText(glyph.text)
                        // Shrink a fallback font only when necessary; never stretch surrounding ASCII.
                        if (measured > allocated && measured > 0) paint.textScaleX = allocated / measured
                        val inset = ((allocated - measured * paint.textScaleX) / 2f).coerceAtLeast(0f)
                        val saved = canvas.save()
                        canvas.clipRect(glyphX, y, glyphX + allocated, y + cellHeight)
                        canvas.drawText(glyph.text, glyphX + inset, y + baselineOffset, paint)
                        canvas.restoreToCount(saved)
                    }
                    val stroke = maxOf(1f, cellHeight / 16f)
                    if (style.underline) canvas.drawRect(x, y + cellHeight - stroke, x + span.width * cellWidth, y + cellHeight, paint)
                    if (style.strikethrough) canvas.drawRect(x, y + cellHeight * 0.52f, x + span.width * cellWidth, y + cellHeight * 0.52f + stroke, paint)
                    if (style.overline) canvas.drawRect(x, y, x + span.width * cellWidth, y + stroke, paint)
                }
            }
        }
        grid.cursor?.takeIf { scrollOffset == 0 && it.visible && (!it.blinking || blinkVisible) &&
            it.row in 0 until grid.rows && it.column in 0 until grid.columns }?.let { cursor ->
            val glyph = lines.getOrNull(cursor.row)?.flatMap { it.glyphs }
                ?.firstOrNull { cursor.column >= it.column && cursor.column < it.column + it.width }
            paint.color = color(grid.cursorColor, foreground); paint.alpha = 180
            val x = originX + (glyph?.column ?: cursor.column) * cellWidth
            val y = originY + cursor.row * cellHeight
            val cursorWidth = (glyph?.width ?: 1) * cellWidth
            val stroke = maxOf(1f, cellHeight / 16f)
            when (cursor.style) {
                "bar" -> canvas.drawRect(x, y, x + stroke, y + cellHeight, paint)
                "underline" -> canvas.drawRect(x, y + cellHeight - stroke, x + cursorWidth, y + cellHeight, paint)
                else -> canvas.drawRect(x, y, x + cursorWidth, y + cellHeight, paint)
            }
        }
    }

    companion object {
        fun plan(lines: List<List<RenderGrid.Span>>): List<List<PlacedSpan>> = lines.map { spans ->
            spans.map { PlacedSpan(it, TerminalGlyphLayout.layout(it.text, it.column, it.width)) }
        }
        private fun color(value: String?, fallback: Int): Int = try {
            if (value.isNullOrBlank()) fallback else Color.parseColor(value)
        } catch (_: IllegalArgumentException) { fallback }
    }
}
