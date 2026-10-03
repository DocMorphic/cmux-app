package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class TerminalContentBottomTest {
    private fun span(text: String) = RenderGrid.Span(0, text.length, text,
        RenderGrid.Style(null, null, false, false, false, false, false))

    @Test fun largeBlankViewportHasBoundedWorkAndDisablesUnprovenBlankAbsorption() {
        val lines = List(1000) { listOf(span(" ".repeat(1000))) }
        val result = TerminalContentBottom.measure(lines, 0, 1000, 1000, 0f)
        assertNull(result.rows)
        assertTrue(result.inspectedUnits <= TerminalContentBottom.MAX_WORK)
        val geometry = TerminalGeometry(1f, 10f, 20f, 0f, 0f, 40, 40)
        assertEquals(300f, TerminalKeyboardLayout(geometry, 800f, 500f, result.rows).slide)
    }

    @Test fun emptySpansCannotEvadeWorkLimit() {
        var visited = 0
        val empty = span("")
        val spans = object : AbstractList<RenderGrid.Span>() {
            override val size = 1_000_000
            override fun get(index: Int): RenderGrid.Span { visited++; return empty }
        }
        val result = TerminalContentBottom.measure(listOf(spans), null, 1000, 1, 0f)
        assertNull(result.rows)
        assertTrue(visited <= TerminalContentBottom.MAX_WORK + 1)
    }

    @Test fun scanStopsAtFooterWithoutReadingEarlierRows() {
        var visited = 0
        val lines = object : AbstractList<List<RenderGrid.Span>>() {
            override val size = 1000
            override fun get(index: Int): List<RenderGrid.Span> {
                assertEquals(999, index); visited++; return listOf(span("footer"))
            }
        }
        assertEquals(1000f, TerminalContentBottom.measure(lines, 0, 1000, 1000, 0f).rows!!)
        assertEquals(1, visited)
    }

    @Test fun utf8WhitespaceConsumesItsActualByteCost() {
        val ascii = TerminalContentBottom.measure(listOf(listOf(span(" ".repeat(50_000)))), null, 1000, 1, 0f)
        assertEquals(0f, ascii.rows!!)
        assertEquals(50_002, ascii.inspectedUnits)
        val wide = TerminalContentBottom.measure(listOf(listOf(span("\u3000".repeat(50_000)))), null, 1000, 1, 0f)
        assertNull(wide.rows)
        assertTrue(wide.inspectedUnits <= TerminalContentBottom.MAX_WORK)
    }

    @Test fun footerCursorAndFractionalHistoryUseTheLowestVisibleContent() {
        val lines = List(20) { row -> listOf(span(if (row == 10) "footer" else "")) }
        assertEquals(11f, TerminalContentBottom.measure(lines, 1, 40, 20, 0f).rows!!)
        assertEquals(16f, TerminalContentBottom.measure(lines, 15, 40, 20, 0f).rows!!)
        assertEquals(10.5f, TerminalContentBottom.measure(lines, null, 40, 20, .5f).rows!!)
    }

    @Test fun cursorOnLastRowNeedsNoTextScan() {
        val lines = object : AbstractList<List<RenderGrid.Span>>() {
            override val size = 1000
            override fun get(index: Int): List<RenderGrid.Span> = error("Rows above the cursor must not be scanned")
        }
        val result = TerminalContentBottom.measure(lines, 999, 1000, 1000, 0f)
        assertEquals(1000f, result.rows!!)
        assertEquals(0, result.inspectedUnits)
    }
}
