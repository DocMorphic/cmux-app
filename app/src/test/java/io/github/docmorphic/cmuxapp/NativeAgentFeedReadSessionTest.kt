package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeAgentFeedReadSessionTest {
    @Test fun cachedRowWaitsForTheFreshAuthorizedHandle() = runTest {
        var current: String? = null; var checks = 0
        val result = async { awaitNativeAgentFeedReadSession({ checks++ }, { current }) }
        runCurrent(); assertFalse(result.isCompleted)
        current = "fresh-owned-handle"; advanceUntilIdle()
        assertEquals("fresh-owned-handle", result.await()); assertTrue(checks >= 2)
    }
    @Test fun changedOwnerOrReusedRowCannotCompleteAnEarlierRead() = runTest {
        var live = true; var current: String? = null
        val result = async { runCatching { awaitNativeAgentFeedReadSession({ check(live) { "Retired row" } }, { current }) } }
        runCurrent(); live = false; current = "replacement-owner"; advanceUntilIdle()
        assertEquals("Retired row", result.await().exceptionOrNull()!!.message)
    }
    @Test fun closingTheReaderCancelsPendingLookup() = runTest {
        var lookups = 0
        val result = async { awaitNativeAgentFeedReadSession<String>({}, { lookups++; null }) }
        runCurrent(); result.cancelAndJoin(); val stopped = lookups
        advanceTimeBy(60_000); runCurrent()
        assertTrue(result.isCancelled); assertEquals(stopped, lookups)
    }
    @Test fun unavailableHandleEndsInActionableRetryRatherThanAnEndlessSpinner() = runTest {
        val result = async { runCatching { awaitNativeAgentFeedReadSession<String>({}, { null }) } }
        advanceUntilIdle()
        assertEquals("Connect to this computer to use Feed", result.await().exceptionOrNull()!!.message)
    }
}
