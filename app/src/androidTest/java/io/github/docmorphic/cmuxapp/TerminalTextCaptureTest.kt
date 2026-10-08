package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.launch

class TerminalTextCaptureTest {
    @Test fun nativeExportKeepsSoftWrappedCommandsLogicalAndDoesNotMoveHeldHistory() = kotlinx.coroutines.runBlocking<Unit> {
        GhosttyVtTerminal(20, 4).use { terminal ->
            val command = "printf '" + "λ中".repeat(30) + "'"
            terminal.append(("old\r\n\u001b[31m" + command + "\u001b[0m\r\nnext\r\n").toByteArray())
            terminal.holdScrollback(2.5)
            val before = terminal.scrollbackPosition()
            val captured = terminalTextSource(terminal) { true }.read()
            assertEquals("old\n$command\nnext", captured.text)
            assertFalse(captured.truncated)
            assertEquals(before, terminal.scrollbackPosition(), 0.0)
            terminal.append("\u001b[?1049h\u001b[2J\u001b[HEditor λ 中".toByteArray())
            assertEquals("Editor λ 中", terminalTextSource(terminal) { true }.read().text)
        }
    }

    @Test fun atomicNativeExportKeepsOneOutputGenerationAndFencesClose() = kotlinx.coroutines.runBlocking<Unit> {
        val terminal = io.github.docmorphic.cmuxapp.ghostty.GhosttyTerminal(40, 6)
        try {
            val writer = launch(kotlinx.coroutines.Dispatchers.Default) {
                repeat(100) { n -> terminal.append(("\u001b[2J\u001b[H" + List(5) { "Generation $n" }.joinToString("\r\n")).toByteArray()) }
            }
            repeat(30) {
                val lines = TerminalTextSnapshot.capped(terminal.copyText()).text.lines().filter { it.isNotEmpty() }
                assertTrue("A text read mixed output generations", lines.distinct().size <= 1)
            }
            writer.join()
            terminal.close()
            assertThrows(IllegalStateException::class.java) { terminal.copyText() }
        } finally { terminal.close() }
    }

    @Test fun nativeLogicalBudgetKeepsLatestFiveThousandLines() = kotlinx.coroutines.runBlocking<Unit> {
        GhosttyVtTerminal(32, 24).use { terminal ->
            terminal.append((0 until 7000).joinToString("\r\n") { "Line $it λ 中" }.toByteArray())
            val captured = terminalTextSource(terminal) { true }.read()
            assertEquals((2000..6999).joinToString("\n") { "Line $it λ 中" }, captured.text)
            assertTrue(captured.truncated)
        }
    }

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
