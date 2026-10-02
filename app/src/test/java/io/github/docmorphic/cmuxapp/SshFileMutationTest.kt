package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SshFileMutationTest {
    @Test fun lostMutationReplySurvivesBothSuccessfulAndFailedRefreshWithoutReplay() = runBlocking {
        for (refreshFails in listOf(false, true)) {
            var writes = 0; var reads = 0
            val primary = SshUploadUnconfirmed(IOException("reply lost"))
            val secondary = IOException("listing unavailable")
            val failure = runCatching { sshFileMutationAndRefresh(
                action = { writes++; throw primary },
                refresh = { reads++; if (refreshFails) throw secondary }) }.exceptionOrNull()
            assertSame(primary, failure)
            assertEquals(1, writes); assertEquals(1, reads)
            assertTrue(failure!!.message!!.contains("may already be saved"))
            assertEquals(if (refreshFails) listOf(secondary) else emptyList(), failure.suppressed.toList())
        }
    }
    @Test fun acknowledgedMutationAndFailedRefreshDoNotSuggestRepeatingTheChange() = runBlocking {
        var writes = 0
        val failure = runCatching { sshFileMutationAndRefresh(
            action = { writes++; "saved.txt" }, refresh = { throw IOException("offline") }) }.exceptionOrNull()
        assertEquals(1, writes)
        assertTrue(failure!!.message!!.startsWith("The change completed"))
        assertEquals("offline", failure.cause!!.message)
    }
    @Test fun successfulMutationReturnsItsActualResultAfterRefresh() = runBlocking {
        val events = mutableListOf<String>()
        assertEquals("renamed.txt", sshFileMutationAndRefresh(
            action = { events += "rename"; "renamed.txt" }, refresh = { events += "list" }))
        assertEquals(listOf("rename", "list"), events)
    }
    @Test fun cancellationDoesNotOpenARefreshOrBecomeAnOperationError() = runBlocking {
        val entered = CompletableDeferred<Unit>(); var refreshes = 0
        val job = launch {
            sshFileMutationAndRefresh(action = { entered.complete(Unit); awaitCancellation() }, refresh = { refreshes++ })
        }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled); assertEquals(0, refreshes)
    }
    @Test fun cancellationDuringRefreshRemainsCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>(); var writes = 0
        val job = launch {
            sshFileMutationAndRefresh(action = { writes++; throw IOException("mutation") },
                refresh = { entered.complete(Unit); awaitCancellation() })
        }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled); assertEquals(1, writes)
    }
}
