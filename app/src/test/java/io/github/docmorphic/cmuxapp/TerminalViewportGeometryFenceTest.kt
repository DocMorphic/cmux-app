package io.github.docmorphic.cmuxapp

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class TerminalViewportGeometryFenceTest {
    @Test fun animationAndOverlappingRotationRetainCommittedCapacityUntilThreeQuietFrames() {
        val fence = TerminalViewportGeometryFence()
        val initial = IntSize(400, 800)
        val rotated = IntSize(800, 240)
        assertEquals(initial, fence.snapshotForApply(initial))
        repeat(20) { assertFalse(fence.sample(IntSize(400, 500), true)) }
        assertEquals(initial, fence.snapshotForApply(rotated))
        assertFalse(fence.sample(rotated, true))
        repeat(2) { assertFalse(fence.sample(rotated, false)) }
        assertTrue(fence.sample(rotated, false))
        assertEquals(rotated, fence.committed)
    }

    @Test fun interruptedCandidateAndReturnToCommittedResetQuietFrameCount() {
        val fence = TerminalViewportGeometryFence()
        val initial = IntSize(400, 800)
        val middle = IntSize(400, 650)
        val final = IntSize(400, 500)
        fence.snapshotForApply(initial)
        repeat(2) { assertFalse(fence.sample(middle, false)) }
        assertFalse(fence.sample(final, false))
        assertTrue(fence.sample(initial, false))
        repeat(2) { assertFalse(fence.sample(final, false)) }
        assertTrue(fence.sample(final, false))
        assertEquals(final, fence.committed)
    }

    @Test fun announcedTargetAndReversalNeverReportIntermediateKeyboardFrames() {
        val fence = TerminalViewportGeometryFence()
        fence.snapshotForApply(IntSize(400, 800))
        for (keyboard in listOf(0, 30, 80, 220, 300)) {
            val sample = TerminalViewportMeasurement(IntSize(400, 800 - keyboard), keyboard)
            fence.prepareTarget(sample.targetSize(300))
            assertEquals(IntSize(400, 500), fence.committed)
        }
        for (keyboard in listOf(300, 160, 30, 0)) {
            val sample = TerminalViewportMeasurement(IntSize(400, 800 - keyboard), keyboard)
            fence.prepareTarget(sample.targetSize(0))
            assertEquals(IntSize(400, 800), fence.committed)
        }
    }

    @Test fun invalidMountCannotReplaceCapacityAndOversizedKeyboardLeavesOnePixel() {
        val fence = TerminalViewportGeometryFence()
        assertNull(fence.snapshotForApply(IntSize.Zero))
        fence.prepareTarget(IntSize.Zero)
        assertNull(fence.committed)
        val size = TerminalViewportMeasurement(IntSize(400, 500), 300).targetSize(1500)
        assertEquals(IntSize(400, 1), size)
        fence.prepareTarget(size)
        assertFalse(fence.sample(IntSize.Zero, false))
        assertEquals(size, fence.committed)
    }
}
