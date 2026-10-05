package io.github.docmorphic.cmuxapp

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FileSaveTransferTest {
    private suspend fun fixture(block: suspend (File, FileSaveFiles, FileSaveSnapshot, ByteArray) -> Unit) {
        val root = Files.createTempDirectory("save-worker").toFile()
        try {
            val bytes = ByteArray(200_123) { (it % 113).toByte() }
            val source = File(root, "source").also { it.writeBytes(bytes) }
            val files = FileSaveFiles(File(root, "saves"))
            val preparing = FileSaveSnapshot(UUID.randomUUID().toString(), "result.txt", "text/plain", FileSavePhase.PREPARING)
            files.prepare(preparing, source, source.length()); source.delete()
            val writing = preparing.copy(phase = FileSavePhase.WRITING, destination = "content://fixture/result", ownsGrant = true)
            files.record(writing)
            block(root, files, writing, bytes)
        } finally { root.deleteRecursively() }
    }

    @Test fun durableJobRunsWithoutActivityBundleAndRepeatedDeliveryDoesNotWriteTwice() = runBlocking { fixture { root, files, writing, bytes ->
        val fresh = FileSaveFiles(File(root, "saves"))
        val output = ByteArrayOutputStream(); var opened = 0; var releases = 0
        val transfer = FileSaveTransfer(fresh, { opened++; output }, { releases++; true })
        assertEquals(listOf(writing), fresh.recoverable())
        repeat(2) { assertEquals(FileSavePhase.COMPLETED, transfer.run(writing.id)?.phase) }
        assertArrayEquals(bytes, output.toByteArray()); assertEquals(1, opened); assertEquals(1, releases)
        assertFalse(files.file(writing).exists()); assertFalse(files.latest(writing).ownsGrant)
        assertTrue(fresh.recoverable().isEmpty())
    } }

    @Test fun independentStoreInstancesCannotOpenTheDestinationConcurrently() = runBlocking { fixture { root, files, writing, _ ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val opened = AtomicInteger()
        fun transfer(store: FileSaveFiles) = FileSaveTransfer(store, { opened.incrementAndGet(); ByteArrayOutputStream() }, { true })
        val first = async { transfer(files).run(writing.id) { received, _ -> if (received == 0L) { entered.complete(Unit); release.await() } } }
        withTimeout(3000) { entered.await() }
        val second = async { transfer(FileSaveFiles(File(root, "saves"))).run(writing.id) }
        delay(100); assertEquals(1, opened.get()); assertFalse(second.isCompleted)
        release.complete(Unit)
        withTimeout(3000) { first.await(); second.await() }
        assertEquals(1, opened.get())
    } }

    @Test fun schedulerInterruptionClosesPartialOutputAndNewWorkerRestartsFromSealedBytes() = runBlocking { fixture { root, files, writing, bytes ->
        val entered = CompletableDeferred<Unit>(); var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        val first = launch { FileSaveTransfer(files, { output }, { fail("Interrupted worker released grant"); true })
            .run(writing.id) { received, _ -> if (received > 0) { entered.complete(Unit); awaitCancellation() } } }
        withTimeout(3000) { entered.await() }; first.cancelAndJoin()
        assertTrue(closed); assertEquals(FileSavePhase.WRITING, files.latest(writing).phase)
        assertTrue(files.file(writing).isFile)
        val recovered = ByteArrayOutputStream()
        FileSaveTransfer(FileSaveFiles(File(root, "saves")), { recovered }, { true }).run(writing.id)
        assertArrayEquals(bytes, recovered.toByteArray())
    } }

    @Test fun queuedCancellationNeverOpensDestinationAndSurvivesNewStore() = runBlocking { fixture { root, files, writing, _ ->
        files.requestCancellation(writing)
        val restored = FileSaveFiles(File(root, "saves")); var released = false
        val final = FileSaveTransfer(restored, { error("Cancelled save opened destination") }, { released = true; true }).run(writing.id)
        assertEquals(FileSavePhase.CANCELLED, final?.phase); assertTrue(released)
        assertFalse(files.file(writing).exists())
    } }

    @Test fun cancellationWaitsForOutputToCloseBeforeCleaningBytesOrGrant() = runBlocking { fixture { root, files, writing, _ ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var closed = false
        val store = FileSaveFiles(File(root, "saves"))
        val transfer = FileSaveTransfer(files, { object : ByteArrayOutputStream() {
            override fun close() { closed = true; super.close() }
        } }, { assertTrue(closed); true })
        val writer = async { transfer.run(writing.id) { received, _ -> if (received > 0) { entered.complete(Unit); release.await() } } }
        withTimeout(3000) { entered.await() }
        val cleanup = async { FileSaveTransfer(store, { error("cleanup cannot write") }, { assertTrue(closed); true }).cancel(writing) }
        withTimeout(3000) { while (!store.isCancellationRequested(writing)) delay(10) }
        assertTrue(files.file(writing).exists()); assertFalse(cleanup.isCompleted)
        release.complete(Unit)
        withTimeout(3000) { writer.await(); cleanup.await() }
        assertTrue(closed); assertFalse(files.file(writing).exists())
        assertEquals(FileSavePhase.CANCELLED, files.latest(writing).phase)
    } }

    @Test fun revokedDestinationKeepsCopyForUserRetryAndDoesNotRetryPermissionBlindly() = runBlocking { fixture { _, files, writing, _ ->
        var opens = 0
        val transfer = FileSaveTransfer(files, { opens++; throw SecurityException("denied") }, { true })
        repeat(2) { assertEquals(FileSavePhase.FAILED, transfer.run(writing.id)?.phase) }
        assertEquals(1, opens); assertTrue(files.file(writing).isFile)
    } }

    @Test fun completionReceiptRetainsFailedGrantReleaseForLaterRecovery() = runBlocking { fixture { root, files, writing, _ ->
        val transfer = FileSaveTransfer(files, { ByteArrayOutputStream() }, { false })
        assertTrue(checkNotNull(transfer.run(writing.id)).ownsGrant)
        val fresh = FileSaveFiles(File(root, "saves"))
        assertEquals(FileSavePhase.COMPLETED, fresh.recoverable().single().phase)
        var released = false
        FileSaveTransfer(fresh, { error("Completed save cannot repeat") }, { released = true; true }).run(writing.id)
        assertTrue(released); assertFalse(files.latest(writing).ownsGrant); assertTrue(fresh.recoverable().isEmpty())
    } }

    @Test fun progressIsReadableByAnotherStoreAndRemovedAfterCompletion() = runBlocking { fixture { root, files, writing, bytes ->
        val other = FileSaveFiles(File(root, "saves"))
        val transfer = FileSaveTransfer(files, { ByteArrayOutputStream() }, { true })
        transfer.run(writing.id) { received, total ->
            files.reportProgress(writing, received, total)
            assertEquals(received to bytes.size.toLong(), other.progress(writing))
        }
        assertNull(other.progress(writing))
    } }

    @Test fun differentExportsToSameDestinationSerializeAndCancelledWaiterNeverOpensIt() = runBlocking { fixture { root, files, writing, bytes ->
        val second = writing.copy(id = UUID.randomUUID().toString())
        files.prepareStream(second.copy(phase = FileSavePhase.PREPARING), bytes.size.toLong()) { it(bytes, bytes.size) }
        files.record(second)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val opens = AtomicInteger()
        val first = async { FileSaveTransfer(files, { opens.incrementAndGet(); ByteArrayOutputStream() }, { true })
            .run(writing.id) { received, _ -> if (received == 0L) { entered.complete(Unit); release.await() } } }
        withTimeout(3000) { entered.await() }
        val other = FileSaveFiles(File(root, "saves"))
        val waiting = async { FileSaveTransfer(other, { opens.incrementAndGet(); ByteArrayOutputStream() }, { true }).run(second.id) }
        delay(100); assertEquals(1, opens.get()); assertFalse(waiting.isCompleted)
        other.requestCancellation(second); release.complete(Unit)
        withTimeout(3000) { first.await(); assertEquals(FileSavePhase.CANCELLED, waiting.await()?.phase) }
        assertEquals(1, opens.get())
    } }

    @Test fun grantDecisionsSerializeAcrossIndependentStores() = runBlocking { fixture { root, files, _, _ ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = async { files.withGrantLock { entered.complete(Unit); release.await() } }
        withTimeout(3000) { entered.await() }
        val other = async { FileSaveFiles(File(root, "saves")).withGrantLock { true } }
        delay(100); assertFalse(other.isCompleted)
        release.complete(Unit); withTimeout(3000) { first.await(); assertTrue(other.await()) }
    } }
}
