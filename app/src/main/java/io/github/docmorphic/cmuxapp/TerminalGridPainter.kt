package io.github.docmorphic.cmuxapp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/** Android canvas implementation shared by the live view and bitmap rendering checks. */
class TerminalGridPainter : AutoCloseable {
    private val images = TerminalImagePainter()
    override fun close() = images.close()
    data class PlacedSpan(val span: RenderGrid.Span, val glyphs: List<TerminalGlyphLayout.Glyph>)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val decoration = Path()
    private val faces = (0..3).map { Typeface.create(Typeface.MONOSPACE, it) }

    fun draw(canvas: Canvas, width: Float, height: Float, grid: TerminalDisplay,
             lines: List<List<PlacedSpan>>, cells: TerminalCellMetrics, scrollOffset: Int, blinkVisible: Boolean,
             topClipFraction: Float = 0f, displayGeometry: TerminalGeometry? = null) {
        val background = color(if (grid.reverseVideo) grid.foreground else grid.background, Color.rgb(17, 19, 22))
        canvas.drawColor(background)
        if (grid.columns <= 0 || grid.rows <= 0) { images.prepare(null); return }
        // A keyboard animation can resize the view before the host's next grid.
        // Scale both axes together so the transient frame cannot stretch letter spacing.
        val geometry = displayGeometry ?: TerminalGeometry.fit(width, height, grid.columns, grid.rows, cells) ?: return
        val scale = geometry.scale
        val cellWidth = geometry.cellWidth; val cellHeight = geometry.cellHeight
        val clipped = canvas.save()
        try {
            canvas.clipRect(geometry.originX, geometry.originY, geometry.originX + grid.columns * cellWidth,
                geometry.originY + grid.rows * cellHeight)
            val originX = geometry.originX
            val fraction = if (topClipFraction.isFinite()) topClipFraction.coerceIn(0f, 1f) else 0f
            val originY = geometry.originY - fraction * cellHeight
            val graphics = (grid as? TerminalGraphicsDisplay)?.graphicsSnapshot(scrollOffset, cells)
            images.prepare(graphics)
            fun imageLayer(layer: TerminalImagePainter.Layer) = images.draw(canvas, layer,
                originX, originY, cellWidth, cellHeight, grid.rows, fraction > 0)
            imageLayer(TerminalImagePainter.Layer.BELOW_BACKGROUND)
            // Default cell backgrounds stay transparent over the terminal background.
            // Explicit and inverse backgrounds occlude the lowest Kitty layer.
            lines.forEachIndexed { row, spans ->
                for ((span, _) in spans) {
                    val style = span.style
                    val explicitBackground = style.backgroundSource?.let { it != "default" } ?: (style.background != null)
                    if (!style.inverse && !explicitBackground) continue
                    paint.alpha = 255
                    paint.color = color(if (style.inverse) grid.foreground(style) else grid.background(style), background)
                    val x = originX + span.column * cellWidth
                    val y = originY + row * cellHeight
                    canvas.drawRect(x, y, x + span.width * cellWidth, y + cellHeight, paint)
                }
            }
            imageLayer(TerminalImagePainter.Layer.BELOW_TEXT)
            val foreground = color(if (grid.reverseVideo) grid.background else grid.foreground, Color.rgb(224, 229, 235))
            paint.typeface = faces[0]
            paint.textSize = cells.fontSizePx * scale
            val baselineOffset = (cellHeight - (paint.fontMetrics.descent + paint.fontMetrics.ascent)) / 2f
            lines.forEachIndexed { row, spans ->
                for ((span, glyphs) in spans) {
                    val x = originX + span.column * cellWidth
                    val y = originY + row * cellHeight
                    val style = span.style
                    val fg = color(if (style.inverse) grid.background(style) else grid.foreground(style),
                        if (style.inverse) background else foreground)
                    if (!style.invisible && (!style.blink || blinkVisible)) {
                        paint.typeface = faces[(if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)]
                        paint.color = fg; paint.alpha = if (style.faint) 150 else 255
                        for (glyph in glyphs) {
                            // Ghostty shapes Kitty placeholders as blanks; the image resolver
                            // paints their fragments. Keep original text for copy/accessibility.
                            if (glyph.text.isNotEmpty() && glyph.text.codePointAt(0) == 0x10EEEE) continue
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
                        if (style.underline) {
                            paint.color = color(style.underlineColor, fg)
                            paint.alpha = if (style.faint) 150 else 255
                            underline(canvas, x, y, span.width * cellWidth, cellWidth, cellHeight, stroke, style.underlineStyle)
                            paint.color = fg
                            paint.alpha = if (style.faint) 150 else 255
                        }
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
                    "hollow" -> {
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke
                        canvas.drawRect(x + stroke / 2, y + stroke / 2,
                            x + cursorWidth - stroke / 2, y + cellHeight - stroke / 2, paint)
                        paint.style = Paint.Style.FILL
                    }
                    else -> canvas.drawRect(x, y, x + cursorWidth, y + cellHeight, paint)
                }
            }
            imageLayer(TerminalImagePainter.Layer.ABOVE_TEXT)
        } finally { canvas.restoreToCount(clipped) }
    }

    private fun underline(canvas: Canvas, x: Float, y: Float, width: Float, cellWidth: Float,
                          cellHeight: Float, stroke: Float, style: Int) {
        val bottom = y + cellHeight
        when (style) {
            2 -> {
                canvas.drawRect(x, bottom - stroke, x + width, bottom, paint)
                canvas.drawRect(x, bottom - 3 * stroke, x + width, bottom - 2 * stroke, paint)
            }
            3 -> {
                val center = bottom - 2 * stroke
                val half = maxOf(cellWidth / 2, stroke * 2)
                decoration.reset(); decoration.moveTo(x, center)
                var position = x; var direction = -1
                while (position < x + width) {
                    val end = minOf(position + half, x + width)
                    decoration.quadTo((position + end) / 2, center + direction * stroke * 2, end, center)
                    position = end; direction = -direction
                }
                paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke
                canvas.drawPath(decoration, paint)
                paint.style = Paint.Style.FILL
            }
            4, 5 -> {
                val segment = if (style == 4) stroke else maxOf(cellWidth / 2, stroke * 2)
                var position = x
                while (position < x + width) {
                    canvas.drawRect(position, bottom - stroke, minOf(position + segment, x + width), bottom, paint)
                    position += segment + stroke
                }
            }
            else -> canvas.drawRect(x, bottom - stroke, x + width, bottom, paint)
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
