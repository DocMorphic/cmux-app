package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeFeedRefreshTest {
    @Test fun eventAndMutationFloorsRequireACompleteNewList() {
        val revision = NativeFeedRevision()
        assertTrue(revision.accept(3))
        assertTrue(revision.observe(7))
        assertFalse(revision.observe(5))
        assertFalse(revision.accept(6))
        assertFalse(revision.accept(-1))
        assertTrue(revision.acknowledge(8))
        assertEquals(3, revision.snapshot)
        assertFalse(revision.accept(7))
        assertTrue(revision.accept(8))
        assertFalse(revision.acknowledge(4))
        assertEquals(8, revision.snapshot)
    }

    @Test fun eventDuringInflightListMakesProgressAndGetsImmediateTrailingRefresh() = runBlocking {
        val revision = NativeFeedRevision()
        val refresh = NativeFeedRefresh(20)
        val firstRequest = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val received = CompletableDeferred<Long>()
        var calls = 0
        val published = mutableListOf<Long>()
        val worker = launch {
            refresh.run {
                val required = revision.required()
                val value = if (++calls == 1) { firstRequest.complete(Unit); release.await(); 1L } else 2L
                val accepted = revision.accept(value, required)
                if (accepted) { published += value; if (value == 2L) received.complete(value) }
                accepted && !revision.needsRefresh()
            }
        }
        try {
            withTimeout(2_000) { firstRequest.await() }
            assertTrue(revision.observe(2)); refresh.request(); release.complete(Unit)
            assertEquals(2L, withTimeout(2_000) { received.await() })
            assertEquals(2, calls)
            assertEquals(listOf(1L, 2L), published)
        } finally { worker.cancelAndJoin(); refresh.close() }
    }

    @Test fun staleHostGetsBoundedAttemptsAndNewDemandRearmsRetry() = runBlocking {
        val refresh = NativeFeedRefresh(20)
        val four = CompletableDeferred<Unit>()
        val five = CompletableDeferred<Unit>()
        var calls = 0
        val worker = launch {
            refresh.run {
                calls++
                if (calls == 4) four.complete(Unit)
                if (calls == 5) five.complete(Unit)
                false
            }
        }
        try {
            withTimeout(2_000) { four.await() }
            assertNull(withTimeoutOrNull(100) { five.await() })
            assertEquals(4, calls)
            refresh.request()
            withTimeout(2_000) { five.await() }
        } finally { worker.cancelAndJoin(); refresh.close() }
    }

    @Test fun closingWhileWaitingCancelsOnlyTheRefreshWorker() = runBlocking {
        val refresh = NativeFeedRefresh()
        val waiting = CompletableDeferred<Unit>()
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            waiting.complete(Unit)
            refresh.awaitRequest(30_000)
            fail("A closed refresh signal must not resume work")
        }
        waiting.await()
        refresh.close()
        withTimeout(2_000) { worker.join() }
        assertTrue(worker.isCancelled)
        assertTrue(currentCoroutineContext().isActive)
        // The same rule applies when teardown wins before awaitRequest begins.
        val late = launch { refresh.awaitRequest(30_000) }
        withTimeout(2_000) { late.join() }
        assertTrue(late.isCancelled)
    }

    @Test fun cancellingDelayedRetryDoesNotRunAnotherRequest() = runBlocking {
        val refresh = NativeFeedRefresh(500)
        val two = CompletableDeferred<Unit>()
        var calls = 0
        val worker = launch { refresh.run { if (++calls == 2) two.complete(Unit); false } }
        withTimeout(2_000) { two.await() }
        worker.cancelAndJoin(); refresh.close()
        assertEquals(2, calls)
    }
}
