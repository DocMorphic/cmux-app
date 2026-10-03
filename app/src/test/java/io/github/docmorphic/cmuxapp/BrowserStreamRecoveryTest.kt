package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BrowserStreamRecoveryTest {
    @Test fun idlePagesNeverRestartAndInputNeedsTheFullSilenceWindow() {
        val policy = BrowserRecoveryPolicy()
        assertFalse(policy.shouldRestart(1_000_000))
        policy.noteInput(1_000)
        assertFalse(policy.shouldRestart(3_499))
        assertTrue(policy.shouldRestart(3_500))
    }
    @Test fun onlyFramesAfterInputAnswerItIncludingSameMillisecondOrdering() {
        val policy = BrowserRecoveryPolicy()
        policy.noteFrame(1_000); policy.noteInput(1_000)
        assertTrue(policy.shouldRestart(4_000))
        policy.noteFrame(1_000)
        assertFalse(policy.shouldRestart(4_000))
        policy.noteInput(2_000)
        assertTrue(policy.shouldRestart(5_000))
    }
    @Test fun restartBackoffAndNewSubscriptionResetFollowUpstream() {
        val policy = BrowserRecoveryPolicy()
        policy.noteInput(0); policy.noteRestart(3_000)
        assertFalse(policy.shouldRestart(6_999))
        assertTrue(policy.shouldRestart(7_000))
        policy.reset()
        assertFalse(policy.shouldRestart(100_000))
    }
    @Test fun unansweredInputArmsOneCheckAndDoesNotPollAnIdleStream() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val clock = Clock(); var restarts = 0
        val recovery = BrowserStreamRecovery(scope, clock) { restarts++ }
        try {
            recovery.started(); clock.advance(60_000); assertEquals(0, restarts)
            recovery.noteInput(); assertEquals(1, clock.waiting)
            clock.advance(2_549); assertEquals(0, restarts)
            clock.advance(1); assertEquals(1, restarts)
            clock.advance(60_000); assertEquals(1, restarts)
            recovery.started(); clock.advance(60_000); assertEquals(1, restarts)
        } finally { recovery.close(); scope.cancel() }
    }
    @Test fun displayedFrameCancelsCheckAndEveryInputReplacesItsDeadline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val clock = Clock(); var restarts = 0
        val recovery = BrowserStreamRecovery(scope, clock) { restarts++ }
        try {
            val generation = recovery.started()
            recovery.noteInput(); clock.advance(2_000); recovery.noteInput()
            clock.advance(550); assertEquals(0, restarts)
            recovery.noteDisplayedFrame(generation)
            clock.advance(10_000); assertEquals(0, restarts)
            recovery.noteInput(); clock.advance(2_550); assertEquals(1, restarts)
        } finally { recovery.close(); scope.cancel() }
    }
    @Test fun backgroundAndDisposalCancelRecoveryAndOldFramesCannotAnswerNewInput() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val clock = Clock(); var restarts = 0
        val recovery = BrowserStreamRecovery(scope, clock) { restarts++ }
        try {
            val old = recovery.started(); recovery.noteInput(); recovery.stopped()
            clock.advance(10_000); assertEquals(0, restarts)
            recovery.noteInput(); assertEquals(0, clock.waiting)
            val fresh = recovery.started(); assertNotEquals(old, fresh)
            recovery.noteInput(); recovery.noteDisplayedFrame(old)
            clock.advance(2_550); assertEquals(1, restarts)
            recovery.noteInput(); recovery.close(); clock.advance(10_000)
            assertEquals(1, restarts); assertEquals(0, clock.waiting)
        } finally { recovery.close(); scope.cancel() }
    }
    @Test fun backgroundLeavesIdleInputReadyButDiscardsOutstandingInput() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sent = mutableListOf<BrowserInput>()
        val release = CompletableDeferred<Unit>()
        val queue = BrowserInputQueue(scope) { sent += it; release.await() }
        try {
            queue.pauseIfPending(); assertNull(queue.error.value)
            queue.offer(BrowserInput.Text("sent"), BrowserInput.Text("pending"))
            queue.pauseIfPending(); assertNotNull(queue.error.value)
            assertFalse(queue.resume()); release.complete(Unit)
            assertEquals(listOf(BrowserInput.Text("sent")), sent)
            assertTrue(queue.resume()); queue.pauseIfPending(); assertNull(queue.error.value)
        } finally { queue.close(); scope.cancel() }
    }
    @Test fun rpcTimeoutIsReportableButPanelDisposalStillCancels() = runBlocking {
        var timeoutReported = false
        try { withTimeout(10) { awaitCancellation() } }
        catch (failure: Exception) { rethrowBrowserCancellation(failure); timeoutReported = true }
        assertTrue(timeoutReported)
        var disposalReported = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            catch (failure: Exception) { rethrowBrowserCancellation(failure); disposalReported = true }
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled); assertFalse(disposalReported)
    }

    private class Clock : BrowserRecoveryClock {
        private var now = 0L
        private val deadlines = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
        val waiting get() = deadlines.count { it.second.isActive }
        override fun nowMillis() = now
        override suspend fun sleep(millis: Long) {
            val signal = CompletableDeferred<Unit>()
            val entry = now + millis to signal
            deadlines += entry
            try { signal.await() } finally { deadlines.remove(entry); signal.cancel() }
        }
        fun advance(millis: Long) {
            now += millis
            deadlines.filter { it.first <= now }.toList().forEach { it.second.complete(Unit) }
        }
    }
}
