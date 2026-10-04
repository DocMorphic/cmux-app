package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ComposerAttachmentReadTest {
    @Test fun unreadableItemDoesNotDropLaterItemsOrReorderTheDraft() = runBlocking {
        val staged = mutableListOf("existing")
        val errors = mutableListOf<String>()
        for (name in listOf("first", "missing", "last")) {
            val item = readComposerAttachment({}, errors::add) {
                if (name == "missing") throw IOException("File permission expired")
                name
            } ?: continue
            staged += item
        }
        assertEquals(listOf("existing", "first", "last"), staged)
        assertEquals(listOf("File permission expired"), errors)
    }

    @Test fun retiredOwnerRejectsBothLateSuccessAndLateProviderFailure() = runBlocking {
        for (providerFails in listOf(false, true)) {
            var current = true
            val reports = mutableListOf<String>()
            val result = runCatching {
                readComposerAttachment({ check(current) { "Owner retired" } }, reports::add) {
                    current = false
                    if (providerFails) throw IOException("Unavailable")
                    "late file"
                }
            }
            assertEquals("Owner retired", result.exceptionOrNull()?.message)
            assertTrue(reports.isEmpty())
        }
    }

    @Test fun cancellationIsNotReportedAsAnUnreadableFile() = runBlocking {
        val reports = mutableListOf<String>()
        val cancellation = CancellationException("User left composer")
        val result = runCatching {
            readComposerAttachment({}, reports::add) { throw cancellation }
        }
        assertSame(cancellation, result.exceptionOrNull())
        assertTrue(reports.isEmpty())
    }

    @Test fun nonCooperativeProviderCannotStageAfterCoroutineCancellation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val reports = mutableListOf<String>()
        var staged = false
        val job = launch {
            val item = readComposerAttachment({}, reports::add) {
                withContext(NonCancellable) { started.complete(Unit); finish.await(); "late file" }
            }
            staged = item != null
        }
        started.await(); job.cancel(); finish.complete(Unit); job.join()
        assertFalse(staged)
        assertTrue(reports.isEmpty())
    }

    @Test fun draftWriteFailureStopsTheBatchWithoutReadingLaterProviders() = runBlocking {
        val reads = mutableListOf<String>()
        val reports = mutableListOf<String>()
        val diskFull = IOException("Draft storage full")
        val result = runCatching {
            for (name in listOf("first", "later")) {
                readComposerAttachment({}, reports::add) { reads += name; name } ?: continue
                throw diskFull // Persistence is deliberately outside recoverable provider preparation.
            }
        }
        assertSame(diskFull, result.exceptionOrNull())
        assertEquals(listOf("first"), reads)
        assertTrue(reports.isEmpty())
    }
}
