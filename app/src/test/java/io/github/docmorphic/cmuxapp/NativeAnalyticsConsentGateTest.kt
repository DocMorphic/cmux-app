package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeAnalyticsConsentGateTest {
    @Test fun revocationAndReenablePermanentlyRetireCapturedEvents() {
        val gate = NativeAnalyticsConsentGate(true)
        val before = gate.snapshot()
        val disabled = gate.synchronize(false, before)
        assertFalse(gate.allows(before))
        assertFalse(gate.allows(disabled))
        val after = gate.synchronize(true, disabled)
        assertTrue(gate.allows(after))
        assertFalse(gate.allows(before))
        assertEquals(before.generation + 2, after.generation)
    }

    @Test fun staleProviderReadCannotUndoInterveningRevocation() {
        val published = mutableListOf<NativeAnalyticsConsentSnapshot>()
        val gate = NativeAnalyticsConsentGate(true, published::add)
        val beforeRead = gate.snapshot()
        val disabled = gate.synchronize(false, beforeRead)
        assertEquals(beforeRead, gate.synchronize(true, beforeRead))
        assertEquals(disabled, gate.snapshot())
        assertEquals(listOf(disabled), published)
        assertFalse(gate.allows(beforeRead))
    }

    @Test fun unchangedConsentPreservesGenerationAndActiveRegistrations() {
        val gate = NativeAnalyticsConsentGate(true) { error("unchanged state must not publish") }
        val snapshot = gate.snapshot()
        val job = Job()
        assertTrue(gate.register(job, snapshot))
        assertEquals(snapshot, gate.synchronize(true, snapshot))
        assertFalse(job.isCancelled)
        job.complete()
    }

    @Test fun revokeCancelsRunningAndLazyJobsBeforeLazyIOStarts() = runTest {
        val gate = NativeAnalyticsConsentGate(true)
        var lazyStarted = false
        val running = launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
        val lazy = launch(start = CoroutineStart.LAZY) { lazyStarted = true }
        assertTrue(gate.register(running, gate.snapshot()))
        assertTrue(gate.register(lazy, gate.snapshot()))
        gate.synchronize(false, gate.snapshot())
        running.join(); lazy.start(); lazy.join()
        assertTrue(running.isCancelled)
        assertTrue(lazy.isCancelled)
        assertFalse(lazyStarted)
    }

    @Test fun disabledAndStaleRegistrationCancelBeforeStart() = runTest {
        val gate = NativeAnalyticsConsentGate(true)
        val old = gate.snapshot()
        gate.synchronize(false, old)
        var starts = 0
        val disabled = launch(start = CoroutineStart.LAZY) { starts++ }
        assertFalse(gate.register(disabled, gate.snapshot()))
        gate.synchronize(true, gate.snapshot())
        val stale = launch(start = CoroutineStart.LAZY) { starts++ }
        assertFalse(gate.register(stale, old))
        disabled.start(); stale.start()
        disabled.join(); stale.join()
        assertEquals(0, starts)
    }

    @Test fun completionOfOldJobDoesNotRemoveNewGenerationRegistration() {
        val gate = NativeAnalyticsConsentGate(true)
        val completed = Job()
        assertTrue(gate.register(completed, gate.snapshot()))
        completed.complete()
        gate.synchronize(false, gate.snapshot())
        gate.synchronize(true, gate.snapshot())
        val current = Job()
        assertTrue(gate.register(current, gate.snapshot()))
        gate.synchronize(false, gate.snapshot())
        assertTrue(current.isCancelled)
        assertFalse(completed.isCancelled)
        assertFalse(gate.register(completed, gate.snapshot()))
    }

    @Test fun closeCancelsRegistrationsAndCannotBeReenabled() {
        val changes = mutableListOf<NativeAnalyticsConsentSnapshot>()
        val gate = NativeAnalyticsConsentGate(true, changes::add)
        val job = Job()
        assertTrue(gate.register(job, gate.snapshot()))
        gate.close()
        val closed = gate.snapshot()
        gate.close()
        assertTrue(job.isCancelled)
        assertEquals(closed, gate.snapshot())
        assertEquals(closed, gate.synchronize(true, closed))
        assertFalse(gate.allows(closed))
        assertEquals(listOf(closed), changes)
        val rejected = Job()
        assertFalse(gate.register(rejected, closed))
        assertTrue(rejected.isCancelled)
    }

    @Test fun publicationFailureStillCancelsRevokedJobs() {
        val gate = NativeAnalyticsConsentGate(true) { error("publisher failed") }
        val job = Job()
        val old = gate.snapshot()
        assertTrue(gate.register(job, old))
        assertTrue(runCatching { gate.synchronize(false, old) }.isFailure)
        assertTrue(job.isCancelled)
        assertFalse(gate.allows(old))
    }

    @Test fun publicationFailureOnCloseStillRetiresOwner() {
        val gate = NativeAnalyticsConsentGate(true) { error("publisher failed") }
        val job = Job()
        assertTrue(gate.register(job, gate.snapshot()))
        assertTrue(runCatching { gate.close() }.isFailure)
        assertTrue(job.isCancelled)
        assertFalse(gate.allows(gate.snapshot()))
        gate.close()
    }
}
