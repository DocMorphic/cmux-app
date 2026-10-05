package io.github.docmorphic.cmuxapp

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FileSaveMaintenanceTest {
    private val now = 2_000_000_000_000L
    private suspend fun fixture(block: suspend (File, FileSaveFiles) -> Unit) {
        val root = Files.createTempDirectory("save-maintenance").toFile()
        try { block(root, FileSaveFiles(root)) } finally { root.deleteRecursively() }
    }
    private suspend fun make(files: FileSaveFiles, phase: FileSavePhase, sealed: Boolean = true,
        ownsGrant: Boolean = false, age: Long = FileSaveMaintenance.RECEIPT_AGE + 1): FileSaveSnapshot {
        val request = FileSaveSnapshot(UUID.randomUUID().toString(), "report.txt", "text/plain", phase,
            destination = if (phase == FileSavePhase.WRITING || ownsGrant) "content://fixture/document" else null,
            ownsGrant = ownsGrant)
        files.prepareStream(request, 8) { it("contents".toByteArray(), 8) }
        files.record(request)
        val directory = files.file(request).parentFile!!
        if (!sealed) File(directory, "seal.json").delete()
        assertTrue(File(directory, "state.json").setLastModified(now - age))
        return request
    }

    @Test fun uiLeaseExcludesOtherActivityStoresAndClosingItAllowsRecovery() = runBlocking { fixture { root, files ->
        val request = make(files, FileSavePhase.FAILED)
        val other = FileSaveFiles(root)
        val owner = checkNotNull(files.claimUi(request.id))
        assertNull(other.claimUi(request.id))
        assertEquals(listOf(request), other.recoveryCandidates())
        owner.close(); owner.close()
        checkNotNull(other.claimUi(request.id)).use { assertEquals(request.id, it.id) }
    } }

    @Test fun independentSavesHaveIndependentUiOwnership() = runBlocking { fixture { _, files ->
        val first = make(files, FileSavePhase.FAILED); val second = make(files, FileSavePhase.FAILED)
        checkNotNull(files.claimUi(first.id)).use {
            checkNotNull(files.claimUi(second.id)).use { assertEquals(second.id, it.id) }
        }
    } }

    @Test fun discoveryIncludesUnfinishedPickerAndWritesButExcludesTerminalReceipts() = runBlocking { fixture { _, files ->
        val pending = listOf(FileSavePhase.PREPARING, FileSavePhase.READY, FileSavePhase.WAITING,
            FileSavePhase.WRITING, FileSavePhase.FAILED).map { make(files, it) }
        make(files, FileSavePhase.COMPLETED); make(files, FileSavePhase.CANCELLED)
        val recovered = files.recoveryCandidates()
        assertEquals(pending.map { it.id }.toSet(), recovered.map { it.id }.toSet())
        assertEquals(FileSavePhase.FAILED, recovered.first().phase)
    } }

    @Test fun oldInterruptedPreparationReleasesBytesButKeepsCancellationReceipt() = runBlocking { fixture { _, files ->
        val interrupted = make(files, FileSavePhase.PREPARING, sealed = false)
        assertEquals(1, FileSaveMaintenance(files).prune(now))
        assertFalse(files.file(interrupted).exists())
        assertEquals(FileSavePhase.CANCELLED, files.load(interrupted.id)?.phase)
        assertTrue(runCatching { files.record(interrupted.copy(phase = FileSavePhase.READY)) }.isFailure)
    } }

    @Test fun liveOwnerAndCompletePreparationAreNeverEvictedByAge() = runBlocking { fixture { _, files ->
        val live = make(files, FileSavePhase.PREPARING, sealed = false)
        val complete = make(files, FileSavePhase.PREPARING)
        checkNotNull(files.claimUi(live.id)).use {
            assertEquals(0, FileSaveMaintenance(files).prune(now))
            assertTrue(files.file(live).exists()); assertTrue(files.file(complete).exists())
        }
        assertEquals(FileSavePhase.READY, files.restore(complete).phase)
    } }

    @Test fun failedReadyWaitingAndWritingCopiesRemainUntilUserDecision() = runBlocking { fixture { _, files ->
        val pending = listOf(FileSavePhase.READY, FileSavePhase.WAITING, FileSavePhase.WRITING, FileSavePhase.FAILED)
            .map { make(files, it) }
        assertEquals(0, FileSaveMaintenance(files).prune(now))
        pending.forEach { assertTrue(files.file(it).exists()); assertEquals(it, files.load(it.id)) }
    } }

    @Test fun terminalCleanupPreservesOwnedGrantsRecentReceiptsAndLiveOwner() = runBlocking { fixture { _, files ->
        val old = make(files, FileSavePhase.COMPLETED)
        val cancelled = make(files, FileSavePhase.CANCELLED)
        val grant = make(files, FileSavePhase.COMPLETED, ownsGrant = true)
        val recent = make(files, FileSavePhase.COMPLETED, age = 1000)
        val live = make(files, FileSavePhase.COMPLETED)
        checkNotNull(files.claimUi(live.id)).use {
            assertEquals(2, FileSaveMaintenance(files).prune(now))
            assertNull(files.load(old.id)); assertNull(files.load(cancelled.id))
            listOf(grant, recent, live).forEach { assertEquals(it, files.load(it.id)) }
        }
    } }

    @Test fun futureTimestampsAndYoungUnsealedCopiesArePreserved() = runBlocking { fixture { _, files ->
        val young = make(files, FileSavePhase.PREPARING, sealed = false, age = 100)
        val future = make(files, FileSavePhase.COMPLETED, age = -100)
        assertEquals(0, FileSaveMaintenance(files).prune(now))
        assertTrue(files.file(young).exists()); assertEquals(future, files.load(future.id))
    } }
}
