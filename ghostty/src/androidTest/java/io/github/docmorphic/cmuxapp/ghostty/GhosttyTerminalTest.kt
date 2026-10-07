package io.github.docmorphic.cmuxapp.ghostty

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GhosttyTerminalTest {
    @Test fun bellsAreParserEventsDrainedOnceWithoutOscTerminatorFalsePositives() {
        GhosttyTerminal(20, 4).use { terminal ->
            assertFalse(terminal.takeBell())
            // The same BEL byte ends OSC; it must not ring, including split chunks.
            "\u001b]0;title\u0007\u001b]10;#123456\u0007".toByteArray().forEach { terminal.append(byteArrayOf(it)) }
            assertFalse(terminal.takeBell())
            terminal.append("hello\u0007\u0007".toByteArray())
            assertTrue(terminal.takeBell()); assertFalse(terminal.takeBell())
            terminal.append("\u001b[?1049h\u0007".toByteArray())
            assertTrue(terminal.takeBell())
            terminal.snapshot(); terminal.resize(30, 5, 8, 16)
            assertFalse(terminal.takeBell())
        }
        val closed = GhosttyTerminal(20, 4); closed.close()
        assertThrows(IllegalStateException::class.java) { closed.takeBell() }
    }

    @Test fun singleCellRemoteGridCanRenderResizeAndExpand() {
        GhosttyTerminal(1, 1).use { terminal ->
            terminal.append("Z".toByteArray())
            val initial = terminal.snapshot()
            assertEquals(1, initial.columns); assertEquals(1, initial.rows)
            assertEquals("Z", initial.lines.single().joinToString("") { it.text }.trimEnd())
            terminal.resize(12, 4, 8, 16)
            terminal.append("\r\nexpanded".toByteArray())
            assertEquals(12, terminal.snapshot().columns)
            terminal.resize(1, 1, 8, 16)
            terminal.append("\u001b[2J\u001b[HZ".toByteArray())
            val resized = terminal.snapshot()
            assertEquals(1, resized.columns); assertEquals(1, resized.rows)
            assertEquals("Z", resized.lines.single().joinToString("") { it.text }.trimEnd())
        }
    }
    private fun GhosttyTerminal.write(text: String) = append(text.toByteArray())
    private fun GhosttyFrame.text(row: Int) = lines[row].joinToString("") { it.text }.trimEnd()

    @Test fun byteBoundariesPreserveGraphemesWideCellsAndRichStyles() {
        GhosttyTerminal(20, 4).use { terminal ->
            val text = "\u001b[?2027h\u001b[1;3;4:3;38;2;18;52;86;58;2;101;102;103me\u0301🧑‍💻中"
            text.toByteArray().forEach { terminal.append(byteArrayOf(it)) }
            val frame = terminal.snapshot()
            val first = frame.lines[0][0]
            assertEquals("e\u0301", first.text)
            assertTrue(first.bold && first.italic)
            assertEquals(3, first.underlineStyle)
            assertEquals(0x123456, first.foreground)
            assertEquals(0x656667, first.underlineColor)
            assertEquals("🧑‍💻", frame.lines[0][1].text)
            assertEquals(2, frame.lines[0][1].width)
            assertEquals(3, frame.lines[0][2].column)
            assertEquals("中", frame.lines[0][2].text)
            assertEquals(2, frame.lines[0][2].width)
        }
    }

    @Test fun historyReadDoesNotMoveLiveOutputAndSnapshotsOwnTheirData() {
        GhosttyTerminal(12, 3).use { terminal ->
            terminal.write("one\r\ntwo\r\nthree\r\nfour")
            val live = terminal.snapshot()
            assertEquals(1, live.historyRows)
            assertEquals("two", live.text(0))
            val history = terminal.snapshot(99)
            assertEquals(1, history.scrollOffset)
            assertEquals("one", history.text(0))
            assertFalse(history.cursorVisible)
            terminal.write("\r\nfive")
            assertEquals("three", terminal.snapshot().text(0))
            assertEquals("one", history.text(0))
            assertEquals("two", live.text(0))
            terminal.resize(8, 4, 10, 20)
            assertEquals(8, terminal.snapshot().columns)
            assertEquals(4, terminal.snapshot().lines.size)
        }
    }

    @Test fun contentAnchorFollowsOutputPreservesFractionAndReadSnapshotsDoNotMoveIt() {
        GhosttyTerminal(16, 3).use { terminal ->
            terminal.write((0..12).joinToString("\r\n") { "row$it" })
            assertEquals(2.25, terminal.holdScrollback(2.25), 0.0)
            val held = terminal.snapshot(3).text(0)
            terminal.write("\r\nrow13\r\nrow14")
            assertEquals(4.25, terminal.scrollbackPosition(), 0.0)
            assertEquals(held, terminal.snapshot(5).text(0))
            terminal.snapshot(); terminal.graphicsSnapshot()
            assertEquals(4.25, terminal.scrollbackPosition(), 0.0)
            terminal.holdScrollback(0.0)
            terminal.write("\r\nrow15")
            assertEquals(0.0, terminal.scrollbackPosition(), 0.0)
            assertThrows(IllegalArgumentException::class.java) { terminal.holdScrollback(Double.NaN) }
            assertThrows(IllegalArgumentException::class.java) { terminal.holdScrollback(-.5) }
        }
    }

    @Test fun anchorSurvivesPruningOfEarlierRowsAndExpiresWhenItsContentIsDiscarded() {
        GhosttyTerminal(80, 3, scrollbackBytes = 64 * 1024).use { terminal ->
            terminal.write((0..2999).joinToString("\r\n") { "row$it" })
            val history = terminal.snapshot().historyRows
            assertTrue("Fixture must fill bounded history: $history", history in 20 until 2900)
            terminal.holdScrollback(10.25)
            val held = terminal.snapshot(11).text(0)
            var pushed = 0
            var previousHistory = history
            var pruned = false
            while (!pruned && pushed < 4096) {
                terminal.write((3000 + pushed until 3008 + pushed).joinToString("") { "\r\nrow$it" })
                pushed += 8
                val nextHistory = terminal.snapshot().historyRows
                pruned = nextHistory < previousHistory + 8
                previousHistory = nextHistory
            }
            assertTrue("Fixture must actually prune rows before checking its anchor", pruned)
            val position = terminal.scrollbackPosition()
            assertEquals(10.25 + pushed, position, 0.0)
            assertEquals(held, terminal.snapshot(kotlin.math.ceil(position).toInt()).text(0))
            terminal.write((8000..15999).joinToString("", transform = { "\r\nrow$it" }))
            assertEquals(0.0, terminal.scrollbackPosition(), 0.0)
            terminal.holdScrollback(1.5)
            assertEquals(1.5, terminal.scrollbackPosition(), 0.0)
        }
    }

    @Test fun anchorNeverResurrectsAcrossBatchedScreenSwitchClearResizeOrClose() {
        val terminal = GhosttyTerminal(16, 3)
        try {
            fun hold() {
                terminal.write((0..20).joinToString("\r\n") { "row$it" })
                assertEquals(2.5, terminal.holdScrollback(2.5), 0.0)
            }
            hold()
            terminal.write("\u001b[?1049h\u001b[?1049l")
            assertEquals(0.0, terminal.scrollbackPosition(), 0.0)
            hold()
            terminal.write("\u001b[3J")
            assertEquals(0.0, terminal.scrollbackPosition(), 0.0)
            hold()
            terminal.resize(16, 3, 12, 24) // Cell metrics alone do not reflow rows.
            assertEquals(2.5, terminal.scrollbackPosition(), 0.0)
            terminal.resize(18, 4, 12, 24)
            assertEquals(0.0, terminal.scrollbackPosition(), 0.0)
            hold()
        } finally { terminal.close() }
        assertThrows(IllegalStateException::class.java) { terminal.scrollbackPosition() }
        assertThrows(IllegalStateException::class.java) { terminal.holdScrollback(1.0) }
        terminal.close()
    }

    @Test fun alternateScreenModesThemeAndErasedBackgroundRoundTrip() {
        GhosttyTerminal(12, 3).use { terminal ->
            terminal.write("primary\u001b[?1h\u001b[?2004h\u001b[?5h\u001b[?1049h\u001b[Halt")
            var frame = terminal.snapshot(50)
            assertTrue(frame.alternateScreen && frame.applicationCursorKeys && frame.bracketedPaste && frame.reverseVideo)
            assertEquals(0, frame.scrollOffset)
            assertEquals("alt", frame.text(0))
            terminal.write("\u001b[?1049l\u001b[?5l\u001b]10;#123456\u0007\u001b]11;#234567\u0007\u001b]12;#345678\u0007")
            frame = terminal.snapshot()
            assertFalse(frame.alternateScreen || frame.reverseVideo)
            assertEquals("primary", frame.text(0))
            assertEquals(0x123456, frame.foreground)
            assertEquals(0x234567, frame.background)
            assertEquals(0x345678, frame.cursorColor)
            terminal.write("\u001b[48;2;1;2;3m\u001b[2J")
            assertEquals(0x010203, terminal.snapshot().lines[0][0].background)
        }
    }

    @Test fun closeIsIdempotentAndNativeRegistryRejectsStaleIds() {
        val terminal = GhosttyTerminal(10, 3)
        val handle = terminal.javaClass.getDeclaredField("handle").also { it.isAccessible = true }.getLong(terminal)
        val baseline = terminal.activeHandlesForTest()
        repeat(100) { GhosttyTerminal(10, 3).use { it.write("synthetic"); it.snapshot() } }
        assertEquals(baseline, terminal.activeHandlesForTest())
        terminal.close(); terminal.close()
        assertEquals(baseline - 1, terminal.activeHandlesForTest())
        assertThrows(IllegalStateException::class.java) { terminal.append(byteArrayOf(65)) }
        assertThrows(IllegalStateException::class.java) { terminal.snapshot() }
        val native = terminal.javaClass.getDeclaredMethod("nativeSnapshot", java.lang.Long.TYPE, Integer.TYPE)
            .also { it.isAccessible = true }
        val failure = assertThrows(InvocationTargetException::class.java) { native.invoke(terminal, handle, 0) }
        assertTrue(failure.cause is IllegalStateException)
    }

    @Test fun concurrentUseAndCloseAreSerializedWithoutLeaking() {
        val terminal = GhosttyTerminal(20, 3)
        val baseline = terminal.activeHandlesForTest()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val unexpected = AtomicReference<Throwable?>()
        val workers = List(2) { worker -> thread {
            ready.countDown(); start.await()
            try {
                repeat(100) {
                    if (worker == 0) terminal.write("a") else terminal.snapshot()
                }
            } catch (closed: IllegalStateException) {
                if (closed.message != "Ghostty terminal is closed") unexpected.set(closed)
            } catch (failure: Throwable) { unexpected.set(failure) }
        } }
        ready.await(); start.countDown(); terminal.close(); workers.forEach { it.join() }
        assertNull(unexpected.get())
        assertEquals(baseline - 1, terminal.activeHandlesForTest())
    }

    @Test fun rejectsOversizedInputAndInvalidDimensionsBeforeMutation() {
        GhosttyTerminal(10, 3).use { terminal ->
            terminal.write("kept")
            assertThrows(IllegalArgumentException::class.java) { terminal.append(ByteArray(2 * 1024 * 1024 + 1)) }
            assertThrows(IllegalArgumentException::class.java) { terminal.resize(0, 3, 10, 20) }
            assertThrows(IllegalArgumentException::class.java) { terminal.snapshot(-1) }
            assertEquals("kept", terminal.snapshot().text(0))
        }
    }

    @Test fun scrollbackBudgetUsesBytesAndLargeHistoryRemainsReadable() {
        GhosttyTerminal(12, 3, scrollbackBytes = 2 * 1024 * 1024).use { terminal ->
            terminal.write((0 until 4000).joinToString("\r\n") { "line$it" })
            val live = terminal.snapshot()
            assertEquals(3997, live.historyRows)
            assertEquals("line3997", live.text(0))
            assertEquals("line0", terminal.snapshot(live.historyRows).text(0))
        }
        GhosttyTerminal(12, 3, scrollbackBytes = 0).use { terminal ->
            terminal.write("a\r\nb\r\nc\r\nd")
            assertEquals(0, terminal.snapshot().historyRows)
        }
    }
    @Test fun sshRepliesAreExplicitOrderedAndTrackResizeWhileMirrorsStaySilent() {
        GhosttyTerminal(20, 5).use { mirror ->
            assertTrue(mirror.append("\u001b[6n\u001b[5n".toByteArray()).isEmpty())
        }
        GhosttyTerminal(20, 5, replyToQueries = true).use { terminal ->
            assertTrue(terminal.append("\u001b[3;4H\u001b[".toByteArray()).isEmpty())
            assertEquals("\u001b[3;4R\u001b[0n", terminal.append("6n\u001b[5n".toByteArray()).toString(Charsets.UTF_8))
            assertTrue(terminal.append("ordinary".toByteArray()).isEmpty())
            terminal.resize(42, 12, 10, 20)
            assertEquals("\u001b[8;12;42t", terminal.append("\u001b[18t".toByteArray()).toString(Charsets.UTF_8))
            terminal.append("\u001b[?2048h".toByteArray())
            assertEquals("\u001b[48;10;30;200;300t", terminal.resize(30, 10, 10, 20).toString(Charsets.UTF_8))
            assertTrue(terminal.append("no stale resize reply".toByteArray()).isEmpty())
            // A terminal query must not install clipboard or filesystem effects.
            assertTrue(terminal.append("\u001b]52;c;?\u0007".toByteArray()).isEmpty())
        }
    }

    @Test fun sshReplyOverflowFailsClosedWithoutReturningAPartialResponse() {
        GhosttyTerminal(20, 5, replyToQueries = true).use { terminal ->
            val queries = "\u001b[6n".repeat(50000).toByteArray()
            assertThrows(IllegalStateException::class.java) { terminal.append(queries) }
            assertThrows(IllegalStateException::class.java) { terminal.append("x".toByteArray()) }
        }
    }

}
