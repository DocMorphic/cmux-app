package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class TerminalTextCaptureTest {
    @Test fun nativeGhosttyRecentCopyIsBoundedAndLeavesLiveViewportUntouched() {
        GhosttyVtTerminal(32, 24).use { terminal ->
            terminal.append((0 until 7_000).joinToString("\r\n") { "Line $it λ 中" }.toByteArray())
            assertTrue(terminal.historyLineCount > 5_000)
            val offsets = mutableListOf<Int>()
            val display = object : TerminalDisplay by terminal {
                override fun visibleLines(scrollOffset: Int): List<List<RenderGrid.Span>> {
                    offsets += scrollOffset
                    return terminal.visibleLines(scrollOffset)
                }
            }
            val live = RenderGrid.plainText(terminal.visibleLines())
            val small = TerminalTextSnapshot.capture(display, 7)
            assertEquals((6_993..6_999).joinToString("\n") { "Line $it λ 中" }, small.text)
            assertTrue(small.truncated)
            assertEquals(listOf(0), offsets)
            assertEquals(live, RenderGrid.plainText(terminal.visibleLines()))
            offsets.clear()
            val normal = TerminalTextSnapshot.capture(display)
            assertEquals((2_000..6_999).joinToString("\n") { "Line $it λ 中" }, normal.text)
            assertTrue(normal.truncated)
            assertTrue("Default copy read beyond its recent line budget", offsets.max() < 5_000)
            assertEquals(live, RenderGrid.plainText(terminal.visibleLines()))
            terminal.append("\r\nNew output".toByteArray())
            assertFalse(normal.text.contains("New output"))
            terminal.append("\u001b[?1049h\u001b[2J\u001b[HEditor λ 中".toByteArray())
            offsets.clear()
            assertEquals(TerminalTextSnapshot("Editor λ 中", false, 1), TerminalTextSnapshot.capture(display, 1))
            assertEquals(listOf(0), offsets)
        }
    }
}
