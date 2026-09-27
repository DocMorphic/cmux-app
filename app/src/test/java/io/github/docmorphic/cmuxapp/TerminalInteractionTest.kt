package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class TerminalInteractionTest {
    @Test fun hitTestsUseLetterboxedScaledCellsAndClampEdges() {
        val geometry = TerminalGeometry.fit(400f, 200f, 40, 40, TerminalCellMetrics(10f, 20f, 14f))!!
        assertEquals(0.25f, geometry.scale)
        assertEquals(150f, geometry.originX)
        assertEquals(TerminalGeometry.Cell(2, 3), geometry.cell(156f, 17f))
        assertEquals(TerminalGeometry.Cell(0, 0), geometry.cell(-10f, -10f))
        assertEquals(TerminalGeometry.Cell(39, 39), geometry.cell(1000f, 1000f))
        assertNull(TerminalGeometry.fit(0f, 20f, 40, 40, TerminalCellMetrics(10f, 20f, 14f)))
    }

    @Test fun slowScrollCoalescesDeltasAndUsesLatestCoordinates() = runBlocking {
        val sent = mutableListOf<TerminalScroll>()
        val release = CompletableDeferred<Unit>()
        val queue = TerminalScrollQueue(this, { throw AssertionError(it) }) {
            sent += it
            if (sent.size == 1) release.await()
        }
        queue.offer(1.0, TerminalGeometry.Cell(1, 2)); yield()
        queue.offer(5.0, TerminalGeometry.Cell(3, 4))
        queue.offer(-2.0, TerminalGeometry.Cell(5, 6)); yield()
        assertEquals(1, sent.size)
        release.complete(Unit); yield()
        assertEquals(listOf(TerminalScroll(1.0, 1, 2, 600), TerminalScroll(3.0, 5, 6)), sent)
        queue.offer(120.0, TerminalGeometry.Cell(7, 8)); yield()
        assertEquals(600, sent.last().prefetchRows)
        queue.close()
        assertFalse(queue.offer(3.0, TerminalGeometry.Cell(0, 0)))
    }

    @Test fun uncertainScrollDropsPendingAndCloseCancelsSurfaceWork() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var sent = 0; var failed = 0
        val queue = TerminalScrollQueue(this, { failed++ }) {
            sent++; release.await(); error("disconnected")
        }
        queue.offer(1.0, TerminalGeometry.Cell(0, 0)); yield()
        queue.offer(10.0, TerminalGeometry.Cell(0, 0))
        release.complete(Unit); yield()
        assertEquals(1, sent); assertEquals(1, failed)
        queue.close()
        var completed = false
        val blocked = TerminalScrollQueue(this, { throw AssertionError(it) }) {
            CompletableDeferred<Unit>().await(); completed = true
        }
        blocked.offer(1.0, TerminalGeometry.Cell(0, 0)); yield()
        blocked.close(); yield()
        assertFalse(completed)
    }

    @Test fun targetChangeStopsScheduledAndCoalescedScrollBeforeCleanup() = runBlocking {
        var current = true
        var sent = 0
        val release = CompletableDeferred<Unit>()
        val queue = TerminalScrollQueue(this, { throw AssertionError(it) }, canSend = { current }) {
            sent++; release.await()
        }
        queue.offer(1.0, TerminalGeometry.Cell(0, 0))
        current = false; yield()
        assertEquals(0, sent) // The screen changed before the scheduled coroutine started.
        current = true
        queue.offer(2.0, TerminalGeometry.Cell(0, 0)); yield()
        queue.offer(3.0, TerminalGeometry.Cell(0, 0))
        current = false
        release.complete(Unit); yield()
        assertEquals(1, sent) // Pending motion is also revalidated after an acknowledgement.
        queue.close()
    }

    @Test fun typingDropsQueuedMotionWithoutReplayingOrCancellingAcknowledgement() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<Double>()
        val queue = TerminalScrollQueue(this, { throw AssertionError(it) }) {
            sent += it.lines
            if (sent.size == 1) release.await()
        }
        queue.offer(1.0, TerminalGeometry.Cell(0, 0)); yield()
        queue.offer(9.0, TerminalGeometry.Cell(0, 0))
        queue.cancelPending()
        release.complete(Unit); yield()
        assertEquals(listOf(1.0), sent)
        queue.offer(-2.0, TerminalGeometry.Cell(0, 0)); yield()
        assertEquals(listOf(1.0, -2.0), sent)
        queue.close()
    }

    @Test fun snapshotKeepsRecentHistoryOnceAndDoesNotChangeAfterOutput() {
        val terminal = VtTerminal(30, 4)
        terminal.append((0..11).joinToString("\r\n") { "Line $it" }.toByteArray())
        val snapshot = TerminalTextSnapshot.capture(terminal, 7)
        assertEquals((5..11).joinToString("\n") { "Line $it" }, snapshot.text)
        assertTrue(snapshot.truncated)
        terminal.append("\r\nNew output".toByteArray())
        assertFalse(snapshot.text.contains("New output"))
        terminal.append("\u001b[?1049h\u001b[2J\u001b[HAlternate".toByteArray())
        assertEquals("Alternate", TerminalTextSnapshot.capture(terminal).text)
        assertFalse(TerminalTextSnapshot.capture(terminal).truncated)
        assertEquals("Alternate", TerminalTextSnapshot.capture(terminal, 1).text)
        assertFalse(TerminalTextSnapshot.capture(terminal, 1).truncated)
    }
}
