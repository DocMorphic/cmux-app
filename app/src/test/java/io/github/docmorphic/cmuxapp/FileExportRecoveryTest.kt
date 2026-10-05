package io.github.docmorphic.cmuxapp

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FileExportRecoveryTest {
    private suspend fun fixture(block: suspend (File, FileExportStore) -> Unit) {
        val root = Files.createTempDirectory("export-recovery").toFile()
        try { block(root, FileExportStore(File(root, "records"), File(root, "payloads"))) }
        finally { root.deleteRecursively() }
    }
    private fun source(root: File): LocalFilePreview {
        val directory = File(root, "cache/${UUID.randomUUID()}").also { it.mkdirs() }
        val file = File(directory, "report.txt").also { it.writeText("Exact export 日本語\n") }
        return LocalFilePreview(file, file.length(), "text/plain", ChangesPreviewRoute.TEXT)
    }
    private suspend fun prepared(controller: FileExportController) = withTimeout(3000) {
        controller.state.first { it.phase == FileExportPhase.READY }
    }
    private suspend fun await(test: () -> Boolean) = withTimeout(3000) { while (!test()) delay(5) }

    @Test fun readyPayloadSurvivesOwnerLossAndRequiresExplicitConfirmationAfterRestore() = runBlocking { fixture { root, store ->
        var saved: String? = null
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = FileExportController(firstScope, store) { saved = it }
        val original = source(root)
        first.begin("source", "report.txt", FileExportAction.SHARE, { "error" }) { original }
        val ready = prepared(first); val id = checkNotNull(saved)
        assertFalse(original.file.exists()); assertTrue(ready.artifact!!.file.isFile)
        first.close(); firstScope.coroutineContext[Job]!!.cancelAndJoin()
        assertEquals(FileExportReceiptPhase.READY, store.read(id)?.phase)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val restored = FileExportController(scope, FileExportStore(File(root, "records"), File(root, "payloads")))
        try {
            restored.restore(id); val pending = prepared(restored)
            assertTrue(pending.needsConfirmation); assertNull(restored.claim(pending))
            restored.continueRestored(pending)
            val claimed = restored.claim(restored.state.value)!!
            assertEquals("Exact export 日本語\n", claimed.file.readText())
            assertEquals(FileExportReceiptPhase.PRESENTING, store.read(id)?.phase)
            restored.handedOff(restored.state.value)
            assertEquals(FileExportReceiptPhase.HANDED_OFF, store.read(id)?.phase)
            assertTrue(claimed.file.exists())
        } finally { restored.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
    } }

    @Test fun ambiguousPresentationIsNotReplayedAndItsReceiverBytesAreRetained() = runBlocking { fixture { root, store ->
        val id = UUID.randomUUID().toString(); store.create(id, "report", FileExportAction.OPEN)
        val artifact = store.adopt(id, source(root)); store.presenting(id)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val restored = FileExportController(scope, store)
        try {
            restored.restore(id)
            val failed = withTimeout(3000) { restored.state.first { it.phase == FileExportPhase.FAILED } }
            assertTrue(failed.failure!!.contains("may already")); assertNull(restored.claim(failed))
            restored.clear()
        } finally { restored.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        assertTrue(artifact.file.exists()); assertEquals(FileExportReceiptPhase.PRESENTING, store.read(id)?.phase)
    } }

    @Test fun completedReceiptConsumesOldSavedIdWithoutAnotherChooser() = runBlocking { fixture { root, store ->
        val id = UUID.randomUUID().toString(); store.create(id, "report", FileExportAction.COPY_IMAGE)
        val artifact = store.adopt(id, source(root)); store.presenting(id); store.handedOff(id)
        var saved: String? = id
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val restored = FileExportController(scope, store) { saved = it }
        try {
            restored.restore(id); await { saved == null }
            assertNull(restored.state.value.phase); assertTrue(artifact.file.exists())
        } finally { restored.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
    } }

    @Test fun explicitCancellationRemovesPreparedPayloadAndPreventsSavedBundleReplay() = runBlocking { fixture { root, store ->
        var saved: String? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = FileExportController(scope, store) { saved = it }
        try {
            controller.begin("source", "report", FileExportAction.OPEN, { "error" }) { source(root) }
            val pending = prepared(controller); val id = checkNotNull(saved)
            controller.clear(); assertNull(saved)
            await { !pending.artifact!!.file.exists() }
            assertEquals(FileExportReceiptPhase.CANCELLED, store.restore(id).phase)
        } finally { controller.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
    } }

    @Test fun corruptPreparedBytesCannotBeRestoredOrPresented() = runBlocking { fixture { root, store ->
        val id = UUID.randomUUID().toString(); store.create(id, "report", FileExportAction.SHARE)
        val artifact = store.adopt(id, source(root))
        artifact.file.writeBytes(ByteArray(artifact.size.toInt()))
        assertTrue(runCatching { store.restore(id) }.isFailure)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = FileExportController(scope, store)
        try {
            controller.restore(id)
            withTimeout(3000) { controller.state.first { it.phase == FileExportPhase.FAILED } }
            await { !artifact.file.exists() }
            assertEquals(FileExportReceiptPhase.CANCELLED, store.read(id)?.phase)
        } finally { controller.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
    } }

    @Test fun exclusiveOwnerProtectsPreparedPayloadFromCompetingRestoreAndExpiredCleanup() = runBlocking { fixture { root, store ->
        val id = UUID.randomUUID().toString(); store.create(id, "report", FileExportAction.OPEN)
        val artifact = store.adopt(id, source(root))
        assertTrue(File(root, "records/$id.json").setLastModified(1))
        val another = FileExportStore(File(root, "records"), File(root, "payloads"))
        checkNotNull(store.claim(id)).use {
            assertNull(another.claim(id)); another.prune(8 * FileExportStore.DAY)
            assertTrue(artifact.file.exists())
        }
        another.prune(8 * FileExportStore.DAY)
        assertFalse(artifact.file.exists()); assertNull(another.read(id))
    } }

    @Test fun unsealedPreparationAndTraversalMetadataCannotBecomeAFileGrant() = runBlocking { fixture { root, store ->
        val id = UUID.randomUUID().toString(); store.create(id, "report", FileExportAction.SHARE)
        assertEquals(FileExportReceiptPhase.PREPARING, store.restore(id).phase)
        store.adopt(id, source(root))
        val record = File(root, "records/$id.json")
        record.writeText(JSONObject(record.readText()).put("filename", "../outside").toString())
        assertTrue(runCatching { store.restore(id) }.isFailure)
        assertTrue(runCatching { store.read("../outside") }.isFailure)
    } }
}
