package io.github.docmorphic.cmuxapp

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TerminalScrollMotionTest {
    @Test fun subRowMotionReversesWithoutInventingRowsAndResetDropsResidue() {
        val carry = TerminalScrollRemainder()
        assertEquals(0, carry.take(7f, 10f))
        assertEquals(0, carry.take(-5f, 10f))
        assertEquals(2, carry.take(19f, 10f))
        assertEquals(-1, carry.take(-12f, 10f))
        carry.reset()
        assertEquals(0, carry.take(9f, 10f))
        assertEquals(0, carry.take(Float.NaN, 10f))
        assertEquals(0, carry.take(2f, 10f))
    }

    @Test fun nativeDecayContinuesAfterReleaseButLinePathStopsWithin450Milliseconds() = runBlocking {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        val lines = mutableListOf<Pair<Long, Double>>()
        var time = 0L
        val motion = TerminalScrollMotion(scope, exponentialDecay(frictionMultiplier = 0.5f), 50f, 8000f)
        motion.fling(3000f, 10f, TerminalGeometry.Cell(2, 3), true) { rows, cell ->
            assertEquals(TerminalGeometry.Cell(2, 3), cell); lines += time to rows; true
        }
        yield()
        for (frame in 0..60) { time = frame * 16_000_000L; clock.sendFrame(time); yield() }
        assertTrue(lines.size > 2)
        assertTrue(lines.sumOf { it.second } > 0)
        assertTrue(lines.all { it.first < 450_000_000L })
        motion.stop()
    }

    @Test fun inputCancellationAndHistoryBoundaryStopFurtherFlingDelivery() = runBlocking {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        val motion = TerminalScrollMotion(scope, exponentialDecay(), 50f, 8000f)
        var delivered = 0
        fun begin(accept: Boolean) { motion.fling(-4000f, 10f, TerminalGeometry.Cell(0, 0), false) { rows, _ ->
            assertTrue(rows < 0); delivered++; accept
        } }
        begin(true); yield()
        clock.sendFrame(0); yield(); clock.sendFrame(16_000_000); yield()
        assertTrue(delivered > 0)
        motion.stop(); val before = delivered
        for (frame in 2..30) { clock.sendFrame(frame * 16_000_000L); yield() }
        assertEquals(before, delivered)
        begin(false); yield()
        for (frame in 31..60) { clock.sendFrame(frame * 16_000_000L); yield() }
        assertEquals(before + 1, delivered)
        motion.stop()
    }
}
