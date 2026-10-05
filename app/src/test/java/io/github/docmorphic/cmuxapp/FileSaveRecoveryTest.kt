package io.github.docmorphic.cmuxapp

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FileSaveRecoveryTest {
    private val request get() = FileSaveSnapshot(UUID.randomUUID().toString(), "report.txt", "text/plain", FileSavePhase.PREPARING)
    private suspend fun fixture(block: suspend (File, FileSaveFiles, FileSaveSnapshot, File) -> Unit) {
        val root = Files.createTempDirectory("save-recovery").toFile()
        try {
            val source = File(root, "source").also { it.writeBytes(ByteArray(200_123) { (it % 127).toByte() }) }
            block(root, FileSaveFiles(File(root, "saves")), request, source)
        } finally { root.deleteRecursively() }
    }
    @Test fun sealedPreparationRecoversAsReadyAfterProcessStopsBeforePublishingReadyState() = runBlocking { fixture { root, files, value, source ->
        files.prepare(value, source, source.length()); val expected = source.readBytes(); source.delete()
        val restored = FileSaveFiles(File(root, "saves")).restore(value)
        assertEquals(FileSavePhase.READY, restored.phase)
        val output = ByteArrayOutputStream(); files.write(restored) { output }; assertArrayEquals(expected, output.toByteArray())
    } }
    @Test fun writingJournalWinsOverOlderPickerBundleAndPreservesExactDestinationAndGrantOwner() = runBlocking { fixture { root, files, value, source ->
        files.prepare(value, source, source.length())
        val waiting = value.copy(phase = FileSavePhase.WAITING); files.record(waiting)
        val writing = waiting.copy(phase = FileSavePhase.WRITING, destination = "content://fixture/report", ownsGrant = true)
        files.record(writing)
        val restored = FileSaveFiles(File(root, "saves")).restore(waiting)
        assertEquals(writing, restored)
        val output = ByteArrayOutputStream(); files.write(restored) { output }; assertArrayEquals(source.readBytes(), output.toByteArray())
    } }
    @Test fun completedReceiptSurvivesByteCleanupAndCannotBecomeASecondWrite() = runBlocking { fixture { root, files, value, source ->
        files.prepare(value, source, source.length())
        val writing = value.copy(phase = FileSavePhase.WRITING, destination = "content://fixture/completed")
        files.record(writing); files.finish(writing, FileSavePhase.COMPLETED)
        assertFalse(files.file(writing).exists())
        val restored = FileSaveFiles(File(root, "saves")).restore(writing.copy(phase = FileSavePhase.WAITING))
        assertEquals(FileSavePhase.COMPLETED, restored.phase)
        assertTrue(runCatching { files.record(writing) }.isFailure)
        var opened = false
        assertTrue(runCatching { files.write(restored) { opened = true; ByteArrayOutputStream() } }.isFailure)
        assertFalse(opened)
    } }
    @Test fun completionBeforeCleanupRestoresAsFinishedAndCleanupIsRepeatable() = runBlocking { fixture { root, files, value, source ->
        files.prepare(value, source, source.length())
        val completed = value.copy(phase = FileSavePhase.COMPLETED, destination = "content://fixture/finished")
        files.record(completed)
        val restored = FileSaveFiles(File(root, "saves")).restore(value.copy(phase = FileSavePhase.WAITING))
        assertEquals(completed, restored); assertTrue(files.file(value).exists())
        repeat(2) { files.finish(restored, FileSavePhase.COMPLETED) }
        assertFalse(files.file(value).exists()); assertEquals(completed, files.latest(value))
    } }
    @Test fun cancelledReceiptCannotBeResurrectedByLatePickerJournal() = runBlocking { fixture { root, files, value, source ->
        files.prepare(value, source, source.length())
        files.finish(value, FileSavePhase.CANCELLED)
        assertTrue(runCatching { files.record(value.copy(phase = FileSavePhase.WAITING)) }.isFailure)
        assertEquals(FileSavePhase.CANCELLED, FileSaveFiles(File(root, "saves")).restore(value).phase)
        assertFalse(files.file(value).exists())
    } }
    @Test fun incompletePreparationAndSameLengthCorruptionNeverOpenTheDestination() = runBlocking { fixture { _, files, value, source ->
        files.prepare(value, source, source.length())
        val content = files.file(value); content.writeBytes(ByteArray(content.length().toInt()) { 1 })
        var opened = false
        assertTrue(runCatching { files.restore(value) }.isFailure)
        assertTrue(runCatching { files.write(value) { opened = true; ByteArrayOutputStream() } }.isFailure)
        assertFalse(opened)
        File(content.parentFile, "seal.json").delete()
        assertTrue(runCatching { files.restore(value) }.isFailure)
    } }
    @Test fun validLegacyWaitingCopyMigratesOutOfEvictableCache() = runBlocking { fixture { root, files, value, source ->
        val legacy = FileSaveFiles(File(root, "legacy")); val old = legacy.file(value)
        old.parentFile!!.mkdirs(); source.copyTo(old)
        val waiting = value.copy(phase = FileSavePhase.WAITING)
        assertEquals(waiting, files.restore(waiting, File(root, "legacy")))
        assertFalse(old.exists()); assertArrayEquals(source.readBytes(), files.file(value).readBytes())
        assertEquals(waiting, FileSaveFiles(File(root, "saves")).restore(waiting))
    } }
    @Test fun legacyPartialPreparationIsNeverPromotedToACompleteSave() = runBlocking { fixture { root, files, value, source ->
        val old = FileSaveFiles(File(root, "legacy")).file(value); old.parentFile!!.mkdirs(); source.copyTo(old)
        assertTrue(runCatching { files.restore(value, File(root, "legacy")) }.isFailure)
        assertFalse(files.file(value).exists()); assertTrue(old.exists())
    } }
    @Test fun mismatchedJournalIdentityAndStalePartialJournalCannotRedirectExport() = runBlocking { fixture { root, files, value, source ->
        files.prepare(value, source, source.length()); val waiting = value.copy(phase = FileSavePhase.WAITING); files.record(waiting)
        File(files.file(value).parentFile, "state.json.partial").writeText("broken")
        assertEquals(waiting, FileSaveFiles(File(root, "saves")).restore(waiting))
        File(files.file(value).parentFile, "state.json").writeText(waiting.copy(id = UUID.randomUUID().toString()).encode())
        assertTrue(runCatching { files.restore(value) }.isFailure)
    } }
    @Test fun largeWriteReportsBoundedProgressAndCancellationClosesOutputBeforeRetry() = runBlocking { fixture { _, files, value, source ->
        files.prepare(value, source, source.length())
        var closed = false; var emitted = 0L
        val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        val writer = launch { files.write(value, progress = { received, total ->
            assertEquals(source.length(), total); assertTrue(received >= emitted); emitted = received
            if (received > 0) { entered.complete(Unit); released.await() }
        }) { object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } } } }
        withTimeout(3000) { entered.await() }; writer.cancelAndJoin()
        assertTrue(closed); assertTrue(emitted in 1 until source.length())
        assertTrue(files.file(value).isFile)
        val output = ByteArrayOutputStream(); files.write(value) { output }
        assertArrayEquals(source.readBytes(), output.toByteArray())
    } }
}
