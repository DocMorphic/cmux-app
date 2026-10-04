package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TerminalArrowRepeatTest {
    @Test fun radialDeadZoneAndDominantAxisMatchIos() {
        assertNull(TerminalArrowDirection.fromDrag(8f, 0f))
        assertNull(TerminalArrowDirection.fromDrag(Float.NaN, 0f))
        assertNull(TerminalArrowDirection.fromDrag(0f, Float.POSITIVE_INFINITY))
        assertEquals(TerminalArrowDirection.DOWN, TerminalArrowDirection.fromDrag(6f, 6f))
        assertEquals(TerminalArrowDirection.UP, TerminalArrowDirection.fromDrag(-6f, -6f))
        assertEquals(TerminalArrowDirection.RIGHT, TerminalArrowDirection.fromDrag(10f, -9f))
        assertEquals(TerminalArrowDirection.LEFT, TerminalArrowDirection.fromDrag(-10f, 9f))
    }
    @Test fun immediateThenEightyMillisecondsDirectionChangesAndDeadZoneCancelOldStream() = runTest {
        val emitted = mutableListOf<Pair<Long, TerminalArrowDirection>>()
        val pad = TerminalArrowRepeat(this, { true }) { emitted += testScheduler.currentTime to it }
        pad.move(TerminalArrowDirection.RIGHT); runCurrent()
        advanceTimeBy(79); assertEquals(1, emitted.size)
        advanceTimeBy(1); runCurrent(); assertEquals(listOf(0L, 80L), emitted.map { it.first })
        pad.move(TerminalArrowDirection.RIGHT); assertEquals(2, emitted.size)
        pad.move(TerminalArrowDirection.UP); runCurrent()
        advanceTimeBy(80); runCurrent()
        assertEquals(listOf(TerminalArrowDirection.RIGHT, TerminalArrowDirection.RIGHT,
            TerminalArrowDirection.UP, TerminalArrowDirection.UP), emitted.map { it.second })
        pad.move(null); advanceTimeBy(800); runCurrent(); assertEquals(4, emitted.size)
        pad.close()
    }
    @Test fun disabledClosedOrCancelledOwnersCannotResumeHeldInput() = runTest {
        var enabled = true
        var count = 0
        val pad = TerminalArrowRepeat(this, { enabled }) { count++ }
        pad.move(TerminalArrowDirection.LEFT); runCurrent()
        enabled = false; advanceTimeBy(80); runCurrent()
        enabled = true; advanceTimeBy(800); runCurrent(); assertEquals(1, count)
        pad.move(TerminalArrowDirection.DOWN); runCurrent(); assertEquals(2, count)
        pad.stop(); advanceTimeBy(800); runCurrent(); assertEquals(2, count)
        pad.close(); pad.move(TerminalArrowDirection.UP); advanceUntilIdle(); assertEquals(2, count)
    }
}
