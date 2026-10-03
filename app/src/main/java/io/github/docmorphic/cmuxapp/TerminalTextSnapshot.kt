package io.github.docmorphic.cmuxapp

/** Immutable local capture: opening the copy sheet never makes a Mac RPC. */
data class TerminalTextSnapshot(val text: String, val truncated: Boolean, val lineBudget: Int) {
    companion object {
        fun capture(grid: TerminalDisplay, lineBudget: Int = 5000): TerminalTextSnapshot {
            require(lineBudget > 0)
            val rows = grid.rows
            if (rows <= 0) return TerminalTextSnapshot("", false, lineBudget)
            val history = if (grid.activeScreen == "alternate") 0 else grid.historyLineCount
            val lines = ArrayDeque<String>()
            var offset = 0
            var count = rows
            // Read newest first. Never materialize old native viewports which
            // cannot fit the copy budget. Keep reads on the terminal owner's
            // thread so output/replay cannot change row positions mid-capture.
            while (true) {
                val page = grid.visibleLines(offset)
                for (index in minOf(count, page.size) - 1 downTo 0) {
                    val line = RenderGrid.plainText(listOf(page[index]))
                    // Blank rows below output must not consume the budget;
                    // blank lines within the retained output still do.
                    if (lines.isEmpty() && line.isBlank()) continue
                    lines.addFirst(line)
                    if (lines.size == lineBudget) return TerminalTextSnapshot(
                        lines.joinToString("\n"), history - offset + index > 0, lineBudget)
                }
                if (offset == history) return TerminalTextSnapshot(lines.joinToString("\n"), false, lineBudget)
                count = minOf(rows, history - offset)
                offset += count
                // A partial oldest viewport overlaps the previous one. Its
                // first count rows are the only rows not captured already.
            }
        }
    }
}
