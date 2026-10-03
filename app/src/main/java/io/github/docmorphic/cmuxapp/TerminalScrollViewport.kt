package io.github.docmorphic.cmuxapp

import kotlin.math.ceil
import kotlin.math.floor

/** Local primary-screen position in cell units; gesture deltas remain pixel precise. */
internal data class TerminalScrollViewport private constructor(val position: Double) {
    val rowOffset = ceil(position).toInt()
    val topClipFraction = (rowOffset - position).toFloat()

    fun lines(grid: TerminalDisplay): List<List<RenderGrid.Span>> {
        val rows = grid.visibleLines(rowOffset)
        // The extra row fills the bottom edge while the first row is clipped.
        return if (topClipFraction > 0 && rowOffset > 0)
            rows + listOf(grid.visibleLines(rowOffset - 1).lastOrNull().orEmpty()) else rows
    }

    fun cell(geometry: TerminalGeometry, x: Float, y: Float): TerminalGeometry.Cell =
        geometry.cell(x, y).copy(row = floor((y - geometry.originY) / geometry.cellHeight + topClipFraction)
            .toInt().coerceIn(0, geometry.rows - 1 + if (topClipFraction > 0) 1 else 0))

    companion object {
        fun at(position: Double, historyRows: Int, activeScreen: String = "primary") = TerminalScrollViewport(
            if (!position.isFinite() || activeScreen != "primary") 0.0
            else position.coerceIn(0.0, historyRows.coerceAtLeast(0).toDouble()))
    }
}

/** Producer row-space identity makes history-growth arithmetic safe across replays. */
internal data class TerminalScrollAnchor(val surface: String, val epoch: String, val columns: Int,
    val rows: Int, val screen: String, val rowSpace: Long?, val historyRows: Long?) {
    fun sameSpace(other: TerminalScrollAnchor) = copy(historyRows = null) == other.copy(historyRows = null)
    fun rebase(position: Double, previous: TerminalScrollAnchor?, retainedRows: Int): Double {
        if (position <= 0 || previous == null || screen != "primary" ||
            !sameSpace(previous)) return 0.0
        val growth = if (rowSpace != null && historyRows != null && previous.historyRows != null) {
            if (historyRows < 0 || previous.historyRows < 0 || historyRows < previous.historyRows) return 0.0
            (historyRows - previous.historyRows).toDouble()
        } else 0.0
        return TerminalScrollViewport.at(position + growth, retainedRows, screen).position
    }
}
