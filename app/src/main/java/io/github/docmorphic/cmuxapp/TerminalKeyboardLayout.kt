package io.github.docmorphic.cmuxapp

import androidx.compose.ui.unit.IntSize
import kotlin.math.min

/** Capture view size and unconsumed IME overlap in the same layout callback. */
internal data class TerminalViewportMeasurement(val visible: IntSize = IntSize.Zero, val keyboard: Int = 0) {
    fun reportSize(keepGrid: Boolean): IntSize = if (visible.width <= 0 || visible.height <= 0) IntSize.Zero else
        IntSize(visible.width, visible.height + if (keepGrid) keyboard.coerceAtLeast(0) else 0)

    fun targetSize(targetKeyboard: Int): IntSize {
        val natural = reportSize(true)
        return if (natural == IntSize.Zero) natural else
            IntSize(natural.width, (natural.height - targetKeyboard.coerceAtLeast(0)).coerceAtLeast(1))
    }
}

/** iOS blank-space absorption and top reveal, expressed in Android surface pixels. */
internal class TerminalKeyboardLayout(val base: TerminalGeometry, naturalHeight: Float, visibleHeight: Float,
    contentBottomRows: Float?, reveal: Float = 0f) {
    val intrusion = (naturalHeight - visibleHeight).coerceAtLeast(0f)
    val blank = contentBottomRows?.let { (naturalHeight - (base.originY + it * base.cellHeight)).coerceAtLeast(0f) }
    val maximumReveal = (intrusion - min(blank ?: 0f, intrusion)).coerceAtLeast(0f)
    val reveal = if (reveal.isFinite()) reveal.coerceIn(0f, maximumReveal) else 0f
    val slide = maximumReveal - this.reveal
    val geometry = base.copy(originY = base.originY - slide)

    companion object {
        /** Text below the cursor counts too (for example a TUI's footer hints). */
        fun contentBottomRows(grid: TerminalDisplay, viewport: TerminalScrollViewport,
            lines: List<List<RenderGrid.Span>>? = null, graphics: TerminalGraphicsDisplay.Snapshot? = null): Float? {
            if (grid.activeScreen != "primary" || grid.rows <= 0) return null
            val cursor = grid.cursor?.row?.takeIf { viewport.rowOffset == 0 }
            return TerminalContentBottom.measure(lines ?: viewport.lines(grid), cursor, grid.columns, grid.rows,
                viewport.topClipFraction, graphics).rows
        }

        data class Scroll(val position: Double, val reveal: Float, val remainingRows: Double)

        /** position is Android's distance from live bottom, opposite the iOS top-origin axis. */
        fun scroll(position: Double, historyRows: Int, reveal: Float, maximumReveal: Float,
                   deltaRows: Double, cellHeight: Float, localPrimary: Boolean): Scroll {
            if (!deltaRows.isFinite() || !cellHeight.isFinite() || cellHeight <= 0f) return Scroll(position, reveal, 0.0)
            val budget = maximumReveal.coerceAtLeast(0f)
            val held = if (reveal.isFinite()) reveal.coerceIn(0f, budget) else 0f
            if (!localPrimary) {
                val next = (held + deltaRows * cellHeight).coerceIn(0.0, budget.toDouble()).toFloat()
                return Scroll(position, next, deltaRows - (next - held) / cellHeight)
            }
            val maxPosition = historyRows.coerceAtLeast(0).toDouble()
            val current = if (position.isFinite()) position.coerceIn(0.0, maxPosition) else 0.0
            val combined = current + held / cellHeight
            val next = (combined + deltaRows).coerceIn(0.0, maxPosition + budget / cellHeight)
            val scroll = min(next, maxPosition)
            return Scroll(scroll, ((next - scroll) * cellHeight).toFloat(), scroll - current)
        }
    }
}
