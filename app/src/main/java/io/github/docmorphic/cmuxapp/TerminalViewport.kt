package io.github.docmorphic.cmuxapp

import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil

/** The same cell geometry is used for viewport reports and terminal painting. */
data class TerminalCellMetrics(val widthPx: Float, val heightPx: Float, val fontSizePx: Float) {
    companion object {
        fun fromFontSize(fontSizePx: Float, verticalPaddingPx: Float): TerminalCellMetrics {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                textSize = fontSizePx
            }
            return TerminalCellMetrics(
                widthPx = ceil(paint.measureText("M").toDouble()).toFloat().coerceAtLeast(1f),
                heightPx = ceil((paint.fontMetrics.descent - paint.fontMetrics.ascent +
                    verticalPaddingPx).toDouble()).toFloat().coerceAtLeast(1f),
                fontSizePx = fontSizePx
            )
        }
    }
}

data class TerminalViewport(val columns: Int, val rows: Int) {
    companion object {
        fun fit(widthPx: Int, heightPx: Int, cells: TerminalCellMetrics): TerminalViewport? {
            if (widthPx <= 0 || heightPx <= 0) return null
            val columns = (widthPx / cells.widthPx).toInt().coerceIn(1, 1000)
            val rows = (heightPx / cells.heightPx).toInt().coerceIn(1, 1000)
            return TerminalViewport(columns, rows)
        }
    }
}
