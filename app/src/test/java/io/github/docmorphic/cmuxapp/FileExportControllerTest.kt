package io.github.docmorphic.cmuxapp

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class FileExportControllerTest {
    private suspend fun fixture(block: suspend (File, FileExportController) -> Unit) = coroutineScope {
        val root = Files.createTempDirectory("file-export-owner").toFile()
        val owner = SupervisorJob(coroutineContext[Job])
        val controller = FileExportController(CoroutineScope(coroutineContext + owner))
        try { block(root, controller) }
        finally { controller.close(); owner.cancelAndJoin(); root.deleteRecursively() }
    }
    private fun copy(root: File, text: String = "data"): LocalFilePreview {
        val directory = File(root, UUID.randomUUID().toString()).also { it.mkdirs() }
        val file = File(directory, "report.txt").also { it.writeText(text) }
        return LocalFilePreview(file, file.length(), "text/plain", ChangesPreviewRoute.TEXT)
    }
    private suspend fun ready(controller: FileExportController) = withTimeout(3000) { controller.state.first { it.phase == FileExportPhase.READY } }
    private suspend fun deleted(file: File) = withTimeout(3000) { while (file.exists()) delay(5) }

    @Test fun presentationCanBeClaimedOnlyOnceAndHandedOffBytesOutliveTheOwner() = runBlocking { fixture { root, controller ->
        var count = 0
        assertTrue(controller.begin("key", "report", FileExportAction.SHARE, { "error" }) { count++; copy(root) })
        val prepared = ready(controller)
        assertFalse(controller.begin("other", "other", FileExportAction.OPEN, { "error" }) { error("Duplicate export") })
        val file = controller.claim(prepared)!!.file
        assertNull(controller.claim(prepared)); assertEquals(1, count)
        controller.handedOff(prepared); controller.close(); yield()
        assertEquals("data", file.readText()); assertNull(controller.claim(prepared))
    } }
    @Test fun readyExportRemainsReadableAndProtectedWhileNoActivityClaimsIt() = runBlocking { fixture { root, controller ->
        controller.begin("key", "report", FileExportAction.OPEN, { "error" }) { copy(root) }
        val prepared = ready(controller); val file = prepared.artifact!!.file
        assertTrue(file.parentFile!!.setLastModified(1))
        withContext(Dispatchers.IO) { ArtifactExportCache.prune(root, now = 10_000_000) }
        assertTrue(file.exists()); assertEquals(prepared, controller.state.value)
        controller.clear(); deleted(file); assertNull(controller.claim(prepared))
    } }
    @Test fun cancelledPreparationCannotPublishLateBytesOrOverwriteItsReplacement() = runBlocking { fixture { root, controller ->
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var old: LocalFilePreview? = null
        try {
            controller.begin("old", "old", FileExportAction.SHARE, { "error" }) {
                entered.complete(Unit); withContext(NonCancellable) { release.await(); copy(root, "old").also { old = it } }
            }
            entered.await(); controller.clear()
            controller.begin("new", "new", FileExportAction.OPEN, { "error" }) { copy(root, "new") }
            val current = ready(controller); release.complete(Unit)
            withTimeout(3000) { while (old == null) delay(5) }; deleted(old!!.file)
            assertSame(current.identity, controller.state.value.identity)
            assertEquals("new", current.artifact!!.file.readText())
        } finally { release.complete(Unit) }
    } }
    @Test fun chooserFailureCleansTheOwnedCopyAndKeepsAStableErrorAcrossObservers() = runBlocking { fixture { root, controller ->
        controller.begin("key", "report", FileExportAction.SHARE, { "error" }) { copy(root) }
        val prepared = ready(controller); val artifact = controller.claim(prepared)!!
        controller.presentationFailed(prepared, "No installed app")
        deleted(artifact.file)
        val failed = controller.state.first()
        assertEquals(FileExportPhase.FAILED, failed.phase); assertEquals("No installed app", failed.failure)
        assertNull(failed.artifact); assertNull(controller.claim(prepared))
        controller.clear(); assertEquals(FileExportState(), controller.state.value)
    } }
    @Test fun stalePresentationCompletionCannotConsumeANewerAction() = runBlocking { fixture { root, controller ->
        controller.begin("old", "old", FileExportAction.SHARE, { "error" }) { copy(root, "old") }
        val old = ready(controller); controller.claim(old); controller.clear()
        controller.begin("new", "new", FileExportAction.OPEN, { "error" }) { copy(root, "new") }
        val current = ready(controller)
        controller.handedOff(old); controller.presentationFailed(old, "stale")
        assertSame(current.identity, controller.state.value.identity); assertNotNull(controller.claim(current))
    } }
    @Test fun preparationFailureCanBeDismissedWithoutReplayingTheRequest() = runBlocking { fixture { _, controller ->
        var calls = 0
        controller.begin("key", "report", FileExportAction.SHARE, { "Permission denied" }) { calls++; error("internal provider details") }
        val failed = withTimeout(3000) { controller.state.first { it.phase == FileExportPhase.FAILED } }
        assertEquals("Permission denied", failed.failure); assertEquals(1, calls)
        assertNull(controller.claim(failed)); controller.clear(); assertEquals(1, calls)
    } }
}
