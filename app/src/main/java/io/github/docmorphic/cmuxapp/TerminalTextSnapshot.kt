package io.github.docmorphic.cmuxapp

/** Immutable local capture: opening the copy sheet never makes a Mac RPC. */
data class TerminalTextSnapshot(val text: String, val truncated: Boolean, val lineBudget: Int) {
    companion object {
        /** Match iOS's logical-line cap without allocating a String for every old line. */
        fun capped(text: String, lineBudget: Int = 5000): TerminalTextSnapshot {
            require(lineBudget > 0)
            var end = text.length
            while (end > 0) {
                val start = text.lastIndexOf('\n', end - 1) + 1
                if ((start until end).any { !text[it].isWhitespace() }) break
                end = (start - 1).coerceAtLeast(0)
            }
            if (end == 0) return TerminalTextSnapshot("", false, lineBudget)
            var start = end
            repeat(lineBudget) {
                val newline = text.lastIndexOf('\n', start - 1)
                if (newline < 0) return TerminalTextSnapshot(text.substring(0, end), false, lineBudget)
                start = newline
            }
            return TerminalTextSnapshot(text.substring(start + 1, end), true, lineBudget)
        }

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


/** Captured display identity never follows selection/replay to another engine. */
internal class TerminalTextSource(val current: () -> Boolean, val read: suspend () -> TerminalTextSnapshot)

internal fun terminalTextSource(display: TerminalDisplay, current: () -> Boolean): TerminalTextSource {
    if (display is GhosttyVtTerminal) {
        val reader = display.textReader()
        return TerminalTextSource(current) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                TerminalTextSnapshot.capped(reader())
            }
        }
    }
    // Compatibility grids are mutable UI-owned row models. Freeze those on their
    // owner thread; native byte terminals use the independent atomic path above.
    val captured = TerminalTextSnapshot.capture(display)
    return TerminalTextSource(current) { captured }
}
