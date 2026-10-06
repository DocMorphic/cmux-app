package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class TerminalToolbarScrollTest {
    private fun sample(offset: Int, maximum: Int = 400, width: Int = 200, active: Boolean = false) =
        TerminalToolbarScrollSample(offset, maximum, width, active)
    @Test fun firstMeasurementKeepsRestoredPositionAndUnmeasuredBoundsDoNotBecomeAnAnchor() {
        val state = TerminalToolbarScrollAnchor()
        assertNull(state.observe(sample(150, Int.MAX_VALUE, 0)))
        assertNull(state.observe(sample(150)))
        assertNull(state.observe(sample(150, 700)))
    }
    @Test fun restingTrailingEdgeTracksWidthAndContentChanges() {
        val state = TerminalToolbarScrollAnchor()
        state.observe(sample(400))
        assertEquals(520, state.observe(sample(400, 520, 80)))
        state.observe(sample(520, 520, 80))
        assertEquals(900, state.observe(sample(520, 900, 80)))
    }
    @Test fun leadingEdgeWinsWhenContentPreviouslyFitAndMiddleOffsetStaysAbsolute() {
        val leading = TerminalToolbarScrollAnchor(); leading.observe(sample(0, 0))
        assertNull(leading.observe(sample(0, 100)))
        val middle = TerminalToolbarScrollAnchor(); middle.observe(sample(150))
        assertNull(middle.observe(sample(150, 600, 100)))
        assertNull(middle.observe(sample(80, 80, 700))) // Compose already clamped a shrinking range.
    }
    @Test fun resizeDuringTouchOrFlingNeverSnapsBackAfterInteractionEnds() {
        val state = TerminalToolbarScrollAnchor(); state.observe(sample(400))
        state.observe(sample(400, active = true))
        assertNull(state.observe(sample(400, 600, 100, active = true)))
        assertNull(state.observe(sample(470, 600, 100, active = true)))
        assertNull(state.observe(sample(470, 600, 100)))
        assertNull(state.observe(sample(470, 600, 100)))
    }
    @Test fun deferredOverscrollClampsOnlyAtRestIncludingCoalescedInteractionEnd() {
        val state = TerminalToolbarScrollAnchor(); state.observe(sample(400, active = true))
        assertNull(state.observe(sample(380, 300, active = true)))
        assertEquals(300, state.observe(sample(330, 300)))
        val coalesced = TerminalToolbarScrollAnchor(); coalesced.observe(sample(400, active = true))
        assertEquals(300, coalesced.observe(sample(330, 300)))
    }
    @Test fun unchangedGeometryDoesNotFightScrollingAndCoalescedReaderMovementWins() {
        val state = TerminalToolbarScrollAnchor(); state.observe(sample(400))
        assertNull(state.observe(sample(200)))
        state.observe(sample(400))
        assertNull(state.observe(sample(270, 600, 100)))
        assertNull(state.observe(sample(260, 600, 100)))
    }
    @Test fun densityTolerancePinsNearEdgesWithoutMovingMiddleContent() {
        val state = TerminalToolbarScrollAnchor(); state.observe(sample(398), 3)
        assertEquals(600, state.observe(sample(398, 600), 3))
        val middle = TerminalToolbarScrollAnchor(); middle.observe(sample(390), 3)
        assertNull(middle.observe(sample(390, 600), 3))
    }
}
