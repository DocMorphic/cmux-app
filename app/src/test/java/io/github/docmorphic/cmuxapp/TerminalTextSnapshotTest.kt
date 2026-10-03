package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class TerminalTextSnapshotTest {
    private class Display(val lines: List<String>, override val rows: Int,
        override val activeScreen: String = "primary") : TerminalDisplay by VtTerminal(80, rows) {
        override val historyLineCount = lines.size - rows
        val offsets = mutableListOf<Int>()
        override fun visibleLines(scrollOffset: Int): List<List<RenderGrid.Span>> {
            offsets += scrollOffset
            return lines.subList(historyLineCount - scrollOffset, historyLineCount - scrollOffset + rows).map {
                if (it.isEmpty()) emptyList() else listOf(RenderGrid.Span(0, it.length, it, RenderGrid.Style(null, null, false, false, false, false, false)))
            }
        }
    }

    @Test fun smallRecentCaptureDoesNotReadTheEntireScrollback() {
        val display = Display((0 until 20_024).map { "Line $it" }, 24)
        val snapshot = TerminalTextSnapshot.capture(display, 7)
        assertEquals((20_017..20_023).joinToString("\n") { "Line $it" }, snapshot.text)
        assertTrue(snapshot.truncated)
        assertEquals("A seven-line copy must not fetch twenty thousand old rows", listOf(0), display.offsets)
    }

    @Test fun partialOldestViewportHasNoDuplicateOrMissingLines() {
        val display = Display((0..10).map { "Line $it" }, 4)
        val snapshot = TerminalTextSnapshot.capture(display, 10)
        assertEquals((1..10).joinToString("\n") { "Line $it" }, snapshot.text)
        assertTrue(snapshot.truncated)
        val complete = TerminalTextSnapshot.capture(display, 11)
        assertEquals((0..10).joinToString("\n") { "Line $it" }, complete.text)
        assertFalse(complete.truncated)
    }

    @Test fun trailingBlankViewportsDoNotConsumeBudgetButInternalBlanksDo() {
        val display = Display(listOf("one", "", "three", "four") + List(11) { " \t" }, 4)
        val snapshot = TerminalTextSnapshot.capture(display, 3)
        assertEquals("\nthree\nfour", snapshot.text)
        assertTrue(snapshot.truncated)
    }

    @Test fun exactlyBudgetedOutputAndBlankScreensAreNotTruncated() {
        assertEquals(TerminalTextSnapshot("a\nb", false, 2),
            TerminalTextSnapshot.capture(Display(listOf("a", "b", "", " "), 4), 2))
        assertEquals(TerminalTextSnapshot("", false, 2),
            TerminalTextSnapshot.capture(Display(List(11) { " " }, 4), 2))
    }

    @Test fun alternateScreenNeverFetchesPrimaryHistory() {
        val display = Display(List(100) { "Old history" } + listOf("Editor λ 中", "", "", ""), 4, "alternate")
        assertEquals(TerminalTextSnapshot("Editor λ 中", false, 1), TerminalTextSnapshot.capture(display, 1))
        assertEquals(listOf(0), display.offsets)
    }
}
