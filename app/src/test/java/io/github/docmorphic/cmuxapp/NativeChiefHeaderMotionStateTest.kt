package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeChiefHeaderMotionStateTest {
    private fun NativeChiefHeaderMotionState.at(millis: Long) = frame(millis * 1_000_000)

    @Test fun admittedCloseAndReopenSurviveAConflatedFrame() {
        val s = NativeChiefHeaderMotionState()
        s.admit(1); s.at(1000); s.at(2000)
        assertTrue(s.pose.closeLabel)
        s.admit(null)
        assertFalse(s.pose.closeLabel)
        s.admit(2)
        assertEquals(2L, s.generation)
        assertFalse(s.pose.closeLabel)
        s.at(2016); s.at(2116)
        assertFalse(s.pose.closeLabel)
        s.at(2616)
        assertTrue(s.pose.closeLabel)
        assertEquals(0f, s.pose.backAlpha, 0f)
    }

    @Test fun aLongIdleIntervalDoesNotSkipTheNextAnimation() {
        val s = NativeChiefHeaderMotionState()
        s.admit(1); s.at(10_000_000)
        assertEquals(1f, s.pose.backAlpha, 0f)
        assertFalse(s.pose.closeLabel)
        s.at(10_000_030)
        assertEquals(.5f, s.pose.backAlpha, .001f)
        s.at(10_001_000); s.admit(null)
        s.at(20_000_000)
        assertEquals(0f, s.pose.backAlpha, 0f)
        s.at(20_000_400)
        assertEquals(.5f, s.pose.backAlpha, .001f)
    }

    @Test fun anAwaitingOldFrameCannotAdvanceEntryAfterAnAdmittedClose() {
        val s = NativeChiefHeaderMotionState()
        s.admit(1); s.at(1000); s.at(1030)
        val before = s.pose
        s.admit(null)
        assertEquals(before.backAlpha, s.pose.backAlpha, 0f)
        s.at(1046)
        assertEquals(before.backAlpha, s.pose.backAlpha, 0f)
        assertFalse(s.pose.closeLabel)
        assertEquals(0f, s.pose.closeFraction, 0f)
    }

    @Test fun duplicateOwnerReconciliationDoesNotRestartTheTimeline() {
        val s = NativeChiefHeaderMotionState()
        s.admit(1); s.at(1000); s.at(1345)
        val before = s.pose
        s.admit(1); s.at(1345)
        assertEquals(before, s.pose)
        s.at(1370)
        assertTrue(s.pose.closeLabel)
    }

    @Test fun durationChangesKeepTheCurrentPhaseAndDisabledMotionSettlesSynchronously() {
        val s = NativeChiefHeaderMotionState()
        s.admit(1); s.at(1000); s.at(1345)
        val before = s.pose
        s.durationScale(2f)
        assertEquals(before, s.pose)
        s.at(1394); assertFalse(s.pose.closeLabel)
        s.at(1395); assertTrue(s.pose.closeLabel)
        s.durationScale(0f)
        assertFalse(s.pose.running); assertEquals(1f, s.pose.trailingAlpha, 0f)
        s.admit(null)
        assertEquals(1f, s.pose.backAlpha, 0f)
        assertTrue(s.pose.backAcceptsPointer)
    }
}
