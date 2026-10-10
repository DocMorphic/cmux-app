package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TaskAttachmentStagingTest {
    @Test fun selectionUsesRemainingPrefixIncludingUnreadableSlots() = runBlocking {
        val read = mutableListOf<String>()
        val appended = mutableListOf<String>()
        TaskAttachments.stage(listOf("first", "unreadable", "outside selection"), 2, {},
            read = { read += it; it.takeUnless { it == "unreadable" } },
            append = { appended += it }, rejected = { fail(it) })
        assertEquals(listOf("first", "unreadable"), read)
        assertEquals(listOf("first"), appended)
    }

    @Test fun fullDraftNeverOpensAnotherProvider() = runBlocking {
        for (remaining in listOf(0, -1)) TaskAttachments.stage(listOf("provider"), remaining, {},
            read = { fail("Full draft opened a provider"); it },
            append = { fail("Full draft changed") }, rejected = { fail(it) })
    }

    @Test fun aggregateRejectionPreservesOrderAndAllowsLaterSmallerFile() = runBlocking {
        val first = ComposerAttachment(name = "first", size = ComposerAttachment.FILE_LIMIT)
        val second = ComposerAttachment(name = "second", size = ComposerAttachment.FILE_LIMIT - 3)
        val draft = mutableListOf(first, second)
        val tooLarge = ComposerAttachment(name = "too large", size = 4)
        val fits = ComposerAttachment(name = "fits", size = 3)
        val errors = mutableListOf<String>()
        TaskAttachments.stage(listOf(tooLarge, fits), 8, {}, read = { it },
            append = { TaskAttachments.validate(draft + it); draft += it }, rejected = errors::add)
        assertEquals(listOf(first, second, fits), draft)
        assertEquals(listOf(TaskAttachments.TOTAL_MESSAGE), errors)
        TaskAttachments.validate(draft)
    }

    @Test fun draftWriteFailureStopsBeforeLaterProvidersAndIsNotReportedAsLimit() = runBlocking {
        val reads = mutableListOf<String>()
        val disk = IOException("Draft storage unavailable")
        val result = runCatching {
            TaskAttachments.stage(listOf("first", "later"), 10, {}, read = { reads += it; it },
                append = { throw disk }, rejected = { fail("Disk error became limit: $it") })
        }
        assertSame(disk, result.exceptionOrNull())
        assertEquals(listOf("first"), reads)
    }

    @Test fun cancellationAndOwnerRetirementCannotContinueAfterLimitRejection() = runBlocking {
        for (retire in listOf(false, true)) {
            val reads = mutableListOf<String>()
            var current = true
            var failure: Exception? = null
            val job = launch {
                try { TaskAttachments.stage(listOf("first", "later"), 10,
                    guard = { check(current) { "Owner retired" } },
                    read = { reads += it; it }, append = {
                        if (retire) current = false else currentCoroutineContext().cancel()
                        throw TaskAttachmentLimitException(TaskAttachments.TOTAL_MESSAGE)
                    }, rejected = { fail("Retired owner received a limit callback") }) }
                catch (caught: Exception) { failure = caught }
            }
            job.join()
            assertEquals(listOf("first"), reads)
            if (retire) assertEquals("Owner retired", failure?.message)
            else assertTrue(failure is CancellationException)
        }
    }

    @Test fun invalidAttachmentDoesNotBecomeRecoverableCountOrSizeFailure() = runBlocking {
        val reads = mutableListOf<Int>()
        val invalid = ComposerAttachment(name = "invalid", size = -1)
        val result = runCatching {
            TaskAttachments.stage(listOf(1, 2), 10, {}, read = { reads += it; invalid },
                append = { TaskAttachments.validate(listOf(it)) }, rejected = { fail(it) })
        }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertFalse(result.exceptionOrNull() is TaskAttachmentLimitException)
        assertEquals(listOf(1), reads)
    }
}
