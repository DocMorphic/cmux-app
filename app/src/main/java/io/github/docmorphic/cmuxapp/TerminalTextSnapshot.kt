package io.github.docmorphic.cmuxapp

/** Immutable local capture: opening the copy sheet never makes a Mac RPC. */
data class TerminalTextSnapshot(val text: String, val truncated: Boolean, val lineBudget: Int) {
    companion object {
        fun capture(grid: TerminalDisplay, lineBudget: Int = 5000): TerminalTextSnapshot {
            require(lineBudget > 0)
            if (grid.rows <= 0) return TerminalTextSnapshot("", false, lineBudget)
            val history = if (grid.activeScreen == "alternate") 0 else grid.historyLineCount
            val lines = mutableListOf<List<RenderGrid.Span>>()
            var remaining = history
            while (remaining > 0) {
                val count = minOf(grid.rows, remaining)
                lines.addAll(grid.visibleLines(remaining).take(count))
                remaining -= count
            }
            lines.addAll(grid.visibleLines())
            // Like iOS, blank screen rows must not consume the recent-output budget.
            while (lines.isNotEmpty() && RenderGrid.plainText(listOf(lines.last())).isBlank()) {
                lines.removeAt(lines.lastIndex)
            }
            return TerminalTextSnapshot(RenderGrid.plainText(lines.takeLast(lineBudget)), lines.size > lineBudget, lineBudget)
        }
    }
}
