package io.github.docmorphic.cmuxapp

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PrivateFileReclamationTest {
    private val now = System.currentTimeMillis() + 1000
    private val old = now - 8 * FileExportStore.DAY
    private suspend fun fixture(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("reclamation").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun age(file: File) { file.walkBottomUp().forEach { assertTrue(it.setLastModified(old)) } }
    private fun orphan(root: File, state: String? = null): File = File(root, UUID.randomUUID().toString()).apply {
        mkdirs(); File(this, "content").writeText("private bytes")
        state?.let { File(this, "state.json").writeText(it) }; age(this)
    }
    @Test fun journalReadFailuresAndCancellationAreNeverTreatedAsDisposableCorruption() {
        assertNull(PrivateFileReclamation.journal<Any> { throw IllegalStateException("invalid journal") })
        val io = java.io.IOException("temporary storage failure")
        assertSame(io, runCatching { PrivateFileReclamation.journal<Any> { throw io } }.exceptionOrNull())
        val cancelled = CancellationException("cancelled")
        assertSame(cancelled, runCatching { PrivateFileReclamation.journal<Any> { throw cancelled } }.exceptionOrNull())
    }
    @Test fun missingMalformedAndOversizedSaveJournalsAreReclaimedAfterGrace() = runBlocking { fixture { root ->
        val files = FileSaveFiles(root)
        val missing = orphan(root); val malformed = orphan(root, "{invalid"); val oversized = orphan(root, "x".repeat(13000))
        val unknown = File(root, "unrelated").apply { mkdirs(); age(this) }
        assertEquals(3, FileSaveMaintenance(files).prune(now))
        listOf(missing, malformed, oversized).forEach { assertFalse(it.exists()) }; assertTrue(unknown.exists())
    } }
    @Test fun liveUiWriterAndRecentlyChangedOrFutureOrphanArePreserved() = runBlocking { fixture { root ->
        val files = FileSaveFiles(root)
        val ui = orphan(root); val writer = orphan(root); val recent = orphan(root); val future = orphan(root)
        File(recent, "content").setLastModified(now - 1000)
        File(future, "content").setLastModified(now + 1000)
        checkNotNull(files.claimUi(ui.name)).use { checkNotNull(files.claimWriter(writer.name)).use {
            assertEquals(0, withTimeout(1000) { FileSaveMaintenance(files).prune(now) })
        } }
        assertEquals(2, FileSaveMaintenance(files).prune(now))
        assertTrue(recent.exists()); assertTrue(future.exists())
    } }
    @Test fun cleanupSkipsBusyValidWriterInsteadOfBlockingOtherMaintenance() = runBlocking { fixture { root ->
        val files = FileSaveFiles(root)
        val request = FileSaveSnapshot(UUID.randomUUID().toString(), "a", "text/plain", FileSavePhase.PREPARING)
        files.prepareStream(request, 1) { it(byteArrayOf(1), 1) }; files.finish(request, FileSavePhase.COMPLETED)
        age(files.file(request).parentFile!!)
        val dead = orphan(root)
        checkNotNull(files.claimWriter(request.id)).use {
            assertEquals(1, withTimeout(1000) { FileSaveMaintenance(files).prune(now) })
            assertNotNull(files.load(request.id)); assertFalse(dead.exists())
        }
    } }
    @Test fun validRetryableSaveJournalIsNeverPrunedAsAnOrphan() = runBlocking { fixture { root ->
        val files = FileSaveFiles(root)
        val request = FileSaveSnapshot(UUID.randomUUID().toString(), "a", "text/plain", FileSavePhase.FAILED)
        files.prepareStream(request, 1) { it(byteArrayOf(1), 1) }; files.record(request); age(files.file(request).parentFile!!)
        assertEquals(0, FileSaveMaintenance(files).prune(now)); assertEquals(request, files.load(request.id))
    } }
    @Test fun exportCorruptReceiptOrphanPayloadAndInterruptedPartialAreReclaimedTogether() = runBlocking { fixture { root ->
        val records = File(root, "records").apply { mkdirs() }; val payloads = File(root, "payloads").apply { mkdirs() }
        val corrupt = orphan(payloads); val missing = orphan(payloads); val partial = UUID.randomUUID().toString()
        File(records, "${corrupt.name}.json").apply { writeText("{broken"); setLastModified(old) }
        File(records, "$partial.partial").apply { writeText("{unfinished"); setLastModified(old) }
        FileExportStore(records, payloads).prune(now)
        assertFalse(corrupt.exists()); assertFalse(missing.exists())
        assertFalse(File(records, "${corrupt.name}.json").exists()); assertFalse(File(records, "$partial.partial").exists())
    } }
    @Test fun activeExportAndRecentPayloadAreProtectedEvenWithBrokenReceipt() = runBlocking { fixture { root ->
        val records = File(root, "records").apply { mkdirs() }; val payloads = File(root, "payloads").apply { mkdirs() }
        val active = orphan(payloads); val recent = orphan(payloads)
        File(recent, "content").setLastModified(now - 1000)
        val store = FileExportStore(records, payloads)
        checkNotNull(store.claim(active.name)).use { store.prune(now); assertTrue(active.exists()) }
        store.prune(now); assertFalse(active.exists()); assertTrue(recent.exists())
    } }
    @Test fun symbolicLinksAreUnlinkedWithoutDeletingOutsideBytes() = runBlocking { fixture { root ->
        val outside = File(root, "outside").apply { mkdirs() }; val keep = File(outside, "keep").apply { writeText("user data") }
        val payloads = File(root, "payloads").apply { mkdirs() }; val orphan = orphan(payloads)
        val link = File(orphan, "link").toPath(); Files.createSymbolicLink(link, outside.toPath())
        Files.setAttribute(link, "basic:lastModifiedTime", java.nio.file.attribute.FileTime.fromMillis(old), java.nio.file.LinkOption.NOFOLLOW_LINKS)
        orphan.setLastModified(old)
        FileExportStore(File(root, "records"), payloads).prune(now)
        assertFalse(orphan.exists()); assertEquals("user data", keep.readText())
    } }
}
