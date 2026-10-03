package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class KeyboardTransitionPresentationFreezeTest {
    @Test fun holdsThroughAcknowledgementUntilPostAckOutputAndNewPresentation() {
        val gate = KeyboardTransitionPresentationFreeze()
        gate.reportPublished(7)
        assertFalse(gate.canPresent(10))
        gate.transitionEnded()
        gate.outputApplied(11)
        assertFalse(gate.canPresent(12))
        gate.reportConfirmed(7)
        assertFalse(gate.canPresent(13))
        gate.outputApplied(13)
        assertFalse(gate.canPresent(13))
        assertTrue(gate.canPresent(14))
    }

    @Test fun redrawBeforeAnimationEndStillWaitsForEnd() {
        val gate = KeyboardTransitionPresentationFreeze()
        gate.reportPublished(3); gate.reportConfirmed(3); gate.outputApplied(20)
        assertFalse(gate.canPresent(21))
        gate.transitionEnded()
        assertTrue(gate.canPresent(22))
    }

    @Test fun staleEchoCannotReleaseSupersedingReport() {
        val gate = KeyboardTransitionPresentationFreeze()
        gate.reportPublished(4); gate.reportPublished(5); gate.transitionEnded()
        gate.reportConfirmed(4); gate.outputApplied(30)
        assertFalse(gate.canPresent(31))
    }

    @Test fun unchangedViewportDoesNotWaitForNonexistentRedraw() {
        val gate = KeyboardTransitionPresentationFreeze()
        gate.reportUnneeded(40)
        assertFalse(gate.canPresent(41))
        gate.transitionEnded()
        assertTrue(gate.canPresent(42))
    }

    private val full = TerminalViewport(40, 40)
    private val short = TerminalViewport(40, 20)
    private fun initial() = TerminalKeyboardPresentation().apply {
        transition(true, false, 0, full)
        reportPublished(1, full); reportConfirmed(1); outputApplied(1, 1); assertTrue(present(1))
    }

    @Test fun controllerRequiresExactReportAndRevisionNotJustAnotherFrame() {
        val gate = initial()
        gate.transition(true, true, 300, short)
        gate.reportPublished(2, short)
        gate.transition(true, false, 300, short)
        gate.reportConfirmed(1); gate.outputApplied(1, 2)
        assertFalse(gate.present(2))
        gate.outputApplied(2, 3) // before acknowledgement is not proof of redraw
        gate.reportConfirmed(2)
        assertFalse(gate.present(3))
        gate.outputApplied(2, 4)
        assertFalse(gate.present(3)) // stale composition recorded after the callback
        assertTrue(gate.present(4))
        assertFalse(gate.frozen)
    }

    @Test fun reverseAnimationDiscardsOldTargetAndSurfaceOwnersAreIndependent() {
        val old = initial()
        old.transition(true, true, 300, short); old.reportPublished(2, short)
        old.transition(true, true, 0, full); old.reportPublished(3, full)
        old.reportConfirmed(2); old.outputApplied(2, 7)
        old.transition(true, false, 0, full)
        assertFalse(old.present(7))
        val fresh = initial()
        assertTrue(fresh.present(1))
        old.reportConfirmed(3); old.outputApplied(3, 8)
        assertTrue(old.present(8))
    }

    @Test fun sameRowsReleaseAfterAnimationWithoutNewReportAndFailureUnfreezes() {
        val gate = initial()
        gate.transition(true, true, 1, full)
        assertTrue(gate.frozen)
        assertFalse(gate.present(1))
        gate.transition(true, false, 1, full)
        assertTrue(gate.present(1))
        gate.transition(true, true, 300, short); gate.reportPublished(2, short)
        gate.reportFailed(1)
        assertTrue(gate.frozen)
        gate.reportFailed(2)
        assertFalse(gate.frozen)
    }

    @Test fun enteringPrimaryOrSharedModeCancelsHeldFrame() {
        val gate = initial()
        gate.transition(true, true, 300, short)
        gate.transition(false, true, 300, full)
        assertFalse(gate.frozen)
        assertTrue(gate.present(2))
    }

    @Test fun silenceExpiryOnlyAppliesToCurrentEndedLegAndRealProgressRestartsIt() {
        val gate = initial()
        gate.transition(true, true, 300, short); gate.reportPublished(2, short)
        gate.expireSilence(gate.silenceEpoch)
        assertTrue(gate.frozen)
        gate.transition(true, false, 300, short)
        val beforeAck = gate.silenceEpoch
        gate.reportConfirmed(1); gate.outputApplied(1, 2)
        assertEquals(beforeAck, gate.silenceEpoch)
        gate.reportConfirmed(2)
        val beforeOutput = gate.silenceEpoch
        gate.expireSilence(beforeAck)
        assertTrue(gate.frozen)
        gate.outputApplied(2, 3)
        gate.expireSilence(beforeOutput)
        assertTrue(gate.frozen)
        gate.expireSilence(gate.silenceEpoch)
        assertFalse(gate.frozen)
    }
}
