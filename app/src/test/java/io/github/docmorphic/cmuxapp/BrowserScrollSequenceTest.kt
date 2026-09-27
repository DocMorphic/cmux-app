package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BrowserScrollSequenceTest {
    @Test fun dragAndDecelerationPreserveMacPhasesAndLastAnchor() {
        val sent = mutableListOf<BrowserInput.Scroll>()
        val sequence = BrowserScrollSequence({ sent += it; true }, { false })
        sequence.begin(10.0, 20.0)
        sequence.drag(-3.0, 8.0, 12.0, 24.0)
        assertTrue(sequence.end(true))
        sequence.momentum(-1.0, 2.0)
        sequence.finishMomentum()
        assertEquals(listOf("began", "changed", "ended", "momentum_began", "momentum_changed", "momentum_ended"), sent.map { it.phase })
        assertEquals(BrowserInput.Scroll(-1.0, 2.0, 12.0, 24.0, "momentum_changed"), sent[4])
        assertFalse(sequence.momentum(10.0, 10.0))
        sequence.finishMomentum(); assertEquals(6, sent.size)
    }

    @Test fun cancelAndRejectedDeliveryCannotContinueOldMomentum() {
        val sent = mutableListOf<BrowserInput.Scroll>()
        var accept = true
        val sequence = BrowserScrollSequence({ sent += it; accept }, { false })
        sequence.begin(1.0, 2.0); sequence.end(true); sequence.cancel()
        assertFalse(sequence.momentum(8.0, 8.0)); sequence.finishMomentum()
        assertEquals("cancelled", sent.last().phase)
        sequence.begin(3.0, 4.0); sequence.end(true)
        accept = false
        assertFalse(sequence.momentum(5.0, 6.0))
        val count = sent.size
        sequence.momentum(1.0, 1.0); sequence.finishMomentum(); sequence.cancel()
        assertEquals(count, sent.size)
    }

    @Test fun slowDeliveryCoalescesMomentumWithoutCrossingDragBoundary() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<BrowserInput>()
        val queue = BrowserInputQueue(scope) { sent += it; if (sent.size == 1) release.await() }
        val sequence = BrowserScrollSequence({ queue.offer(it) }, queue::discardPendingScroll)
        try {
            sequence.begin(1.0, 2.0)
            sequence.drag(3.0, 4.0, 5.0, 6.0)
            sequence.end(true); sequence.momentum(2.0, 4.0); sequence.momentum(3.0, 5.0); sequence.finishMomentum()
            release.complete(Unit)
            assertEquals(listOf("began", "changed", "ended", "momentum_began", "momentum_ended"), sent.map { (it as BrowserInput.Scroll).phase })
            assertEquals(BrowserInput.Scroll(5.0, 9.0, 5.0, 6.0, "momentum_began"), sent[3])
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun keyboardDiscardsQueuedCompletedGestureBeforeSendingNewInput() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<BrowserInput>()
        val queue = BrowserInputQueue(scope) { sent += it; if (sent.size == 1) release.await() }
        val sequence = BrowserScrollSequence({ queue.offer(it) }, queue::discardPendingScroll)
        queue.onNonScrollInput = sequence::cancel
        try {
            sequence.begin(1.0, 2.0); sequence.drag(3.0, 4.0, 5.0, 6.0)
            sequence.end(true); sequence.momentum(8.0, 9.0); sequence.finishMomentum()
            queue.offer(BrowserInput.Text("new input"))
            release.complete(Unit)
            assertEquals(listOf(BrowserInput.Scroll(0.0, 0.0, 1.0, 2.0, "began"),
                BrowserInput.Scroll(0.0, 0.0, 5.0, 6.0, "cancelled"), BrowserInput.Text("new input")), sent)
            assertNull(queue.error.value)
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun cancellingBacklogPreservesOtherInputAndRestoresQueueCapacity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<BrowserInput>()
        val queue = BrowserInputQueue(scope) { sent += it; if (sent.size == 1) release.await() }
        try {
            queue.offer(BrowserInput.Text("in flight"), BrowserInput.Text("preserved"))
            repeat(100) { queue.offer(BrowserInput.Scroll(0.0, 1.0, 1.0, 2.0, "ended")) }
            assertTrue(queue.discardPendingScroll()); assertFalse(queue.discardPendingScroll())
            assertTrue(queue.offer(BrowserInput.Text("x".repeat(60_000))))
            release.complete(Unit)
            assertEquals(3, sent.size); assertEquals(BrowserInput.Text("preserved"), sent[1])
            assertNull(queue.error.value)
        } finally { queue.close(); scope.cancel() }
    }
}
