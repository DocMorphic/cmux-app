package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeChiefHeaderMotionTest {
    @Test fun settledEntrySeparatesTheBackFadeGlyphSwapAndNewGlyphFade() {
        val m = NativeChiefHeaderMotion()
        m.transition(1, 1000.0, 1f)
        val first = m.frame(1030.0)
        assertEquals(.5f, first.backAlpha, .001f)
        assertEquals(1f, first.trailingAlpha, 0f)
        assertFalse(first.closeLabel)
        assertEquals(0f, m.frame(1060.0).backAlpha, 0f)
        assertEquals(1f, m.frame(1320.0).trailingAlpha, 0f)
        assertFalse(m.frame(1369.0).closeLabel)
        val swapped = m.frame(1370.0)
        assertTrue(swapped.closeLabel); assertEquals(0f, swapped.trailingAlpha, 0f)
        assertEquals(.5f, m.frame(1470.0).trailingAlpha, .001f)
        val settled = m.frame(1570.0)
        assertFalse(settled.running); assertEquals(1f, settled.trailingAlpha, 0f)
    }

    @Test fun closingRestoresTheNormalLabelAtOnceAndDelaysOnlyTheBackFade() {
        val m = NativeChiefHeaderMotion(1)
        m.transition(null, 1000.0, 1f)
        assertFalse(m.frame(1000.0).closeLabel)
        assertEquals(.5f, m.frame(1030.0).closeFraction, .001f)
        assertEquals(0f, m.frame(1299.0).backAlpha, 0f)
        assertEquals(.5f, m.frame(1400.0).backAlpha, .001f)
        assertFalse(m.frame(1500.0).running)
        assertEquals(1f, m.frame(1500.0).backAlpha, 0f)
    }

    @Test fun earlyCloseAndGlyphSwapCloseCannotLaterHideTheStopSymbol() {
        for (closeAt in listOf(30.0, 100.0, 340.0, 369.0, 370.0, 450.0)) {
            val m = NativeChiefHeaderMotion(); m.transition(1, 0.0, 1f)
            val before = m.frame(closeAt)
            m.transition(null, closeAt, 1f)
            assertEquals(before.backAlpha, m.frame(closeAt).backAlpha, 0f)
            assertEquals(before.trailingAlpha, m.frame(closeAt).trailingAlpha, 0f)
            val after = m.frame(closeAt + 2000)
            assertEquals(1f, after.backAlpha, 0f); assertEquals(1f, after.trailingAlpha, 0f)
            assertEquals(0f, after.closeFraction, 0f); assertFalse(after.closeLabel)
        }
    }

    @Test fun reopeningSupersedesTheOldBackReturnAndDelayedGlyphCompletion() {
        for ((closeAt, reopenAfter) in listOf(850.0 to 100.0, 340.0 to 10.0, 450.0 to 30.0)) {
            val m = NativeChiefHeaderMotion(); m.transition(1, 0.0, 1f)
            m.transition(null, closeAt, 1f)
            val reopenedAt = closeAt + reopenAfter
            m.transition(2, reopenedAt, 1f)
            assertFalse(m.frame(reopenedAt + 100).closeLabel)
            assertFalse(m.frame(reopenedAt + 369).closeLabel)
            assertEquals(0f, m.frame(reopenedAt + 570).backAlpha, 0f)
            assertEquals(1f, m.frame(reopenedAt + 570).trailingAlpha, 0f)
            assertTrue(m.frame(reopenedAt + 570).closeLabel)
            assertFalse(m.frame(reopenedAt + 570).running)
        }
    }

    @Test fun disabledAnimationsSettleAndScaleChangesRetargetFromTheCurrentPose() {
        val m = NativeChiefHeaderMotion()
        m.transition(1, 0.0, 2f)
        assertEquals(.5f, m.frame(60.0).backAlpha, .001f)
        assertFalse(m.frame(739.0).closeLabel)
        assertTrue(m.frame(740.0).closeLabel)
        m.transition(1, 740.0, 0f)
        assertEquals(0f, m.frame(740.0).backAlpha, 0f)
        assertEquals(1f, m.frame(740.0).trailingAlpha, 0f)
        assertFalse(m.frame(740.0).running)
        m.transition(null, 750.0, 0f)
        assertEquals(1f, m.frame(750.0).backAlpha, 0f)
        assertFalse(m.frame(750.0).closeLabel)
    }

    @Test fun changingDurationScaleKeepsThePhaseAndEnablingAtRestDoesNotReplayTheEntry() {
        val m = NativeChiefHeaderMotion(); m.transition(1, 0.0, 1f)
        val before = m.frame(345.0)
        m.transition(1, 345.0, 2f)
        assertEquals(before, m.frame(345.0))
        assertFalse(m.frame(394.0).closeLabel)
        assertTrue(m.frame(395.0).closeLabel)
        m.transition(1, 400.0, 0f)
        val rest = m.frame(400.0)
        m.transition(1, 410.0, 1f)
        assertEquals(rest, m.frame(1000.0))
    }

    @Test fun backPointerHitsAreSuppressedOnlyDuringTheActualReturnFade() {
        for (scale in listOf(1f, 2f)) {
            val m = NativeChiefHeaderMotion(1)
            m.transition(null, 0.0, scale)
            assertTrue(m.frame(0.0).backAcceptsPointer)
            assertTrue(m.frame(299.0 * scale).backAcceptsPointer)
            assertFalse(m.frame(300.0 * scale).backAcceptsPointer)
            assertFalse(m.frame(499.0 * scale).backAcceptsPointer)
            assertTrue(m.frame(500.0 * scale).backAcceptsPointer)
            m.transition(2, 1000.0, scale)
            assertFalse(m.frame(2000.0).backAcceptsPointer)
        }
    }
}
