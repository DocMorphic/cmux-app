package io.github.docmorphic.cmuxapp

import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChangesPreviewControllerTest {
    private class Fixture(parent: CoroutineScope) {
        val root = Files.createTempDirectory("cmux-retained-preview").toFile()
        val owner = SupervisorJob(parent.coroutineContext[Job])
        val controller = ChangesPreviewController(CoroutineScope(parent.coroutineContext + owner))
        val file = ChangedFile("new/image.png", "old/image.png", ChangeKind.RENAMED, 0, 0, true, false)
        val generation = Any()
        val reads = CopyOnWriteArrayList<Pair<String, ChangesRevision>>()
        var beforeFetch: suspend (ChangesRevision) -> Unit = {}
        var fail = false
        val transfer = ChangesContentTransfer({ _, revision ->
            JSONObject().put("exists", true).put("is_directory", false).put("size", revision.wire.length)
                .put("kind", "image").put("mime_type", "image/png").put("content_fingerprint", "blob:fixture:${revision.wire}")
        }, { path, revision, _, _ ->
            reads += path to revision; beforeFetch(revision)
            if (fail) error("Offline fixture")
            val data = revision.wire.toByteArray()
            JSONObject().put("offset", 0).put("total_size", data.size).put("eof", true)
                .put("data_b64", Base64.getEncoder().encodeToString(data)).put("content_fingerprint", "blob:fixture:${revision.wire}")
        })
        fun open(generation: Any = this.generation, file: ChangedFile = this.file) = controller.open(file, generation, transfer, root)
        suspend fun artifact() = withTimeout(3000) { controller.state.first { it.artifact != null }.artifact!! }
        suspend fun emptyDisk() = withTimeout(3000) { while (root.listFiles().orEmpty().isNotEmpty()) delay(5) }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) = coroutineScope {
        val f = Fixture(this)
        try { f.block() } finally { f.controller.close(); f.owner.cancelAndJoin(); f.root.deleteRecursively() }
    }

    @Test fun samePresentationReattachesWithoutDownloadingOrResettingBeforeRevision() = runBlocking { fixture {
        controller.choose(file, ChangesRevision.BASE); open()
        val first = artifact(); assertEquals("base", first.file.readText())
        repeat(3) { controller.retainPath(file.path); open() }
        assertSame(first, artifact()); assertEquals(ChangesRevision.BASE, controller.revision(file))
        assertEquals(listOf("old/image.png" to ChangesRevision.BASE), reads.toList())
        assertTrue(controller.matches(controller.state.value, file, generation, transfer, ChangesRevision.BASE))
    } }

    @Test fun revisionSwitchRetiresOldDirectoryAndUsesTheRenamePath() = runBlocking { fixture {
        open(); val after = artifact()
        controller.choose(file, ChangesRevision.BASE); open(); val before = artifact()
        assertEquals("old/image.png", before.path); assertEquals("base", before.file.readText())
        assertNotEquals(after.file, before.file)
        withTimeout(3000) { while (after.file.exists()) delay(5) }
        assertTrue(before.file.exists())
        controller.retainPath("other.png"); assertNull(controller.state.value.artifact); emptyDisk()
    } }

    @Test fun cancelledOldTransferCannotPublishOrDeleteItsReplacement() = runBlocking { fixture {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeFetch = { revision -> if (revision == ChangesRevision.CURRENT) {
            entered.complete(Unit); withContext(NonCancellable) { release.await() }
        } }
        try {
            open(); withTimeout(3000) { entered.await() }
            controller.choose(file, ChangesRevision.BASE); open(); val before = artifact()
            release.complete(Unit)
            withTimeout(3000) { while (root.listFiles().orEmpty().size != 1) delay(5) }
            assertSame(before, controller.state.value.artifact); assertEquals("base", before.file.readText())
        } finally { release.complete(Unit) }
    } }

    @Test fun repositoryGenerationChangeRefetchesEvenWhenDiffValuesAreEqual() = runBlocking { fixture {
        open(); val first = artifact()
        controller.invalidate(file.path); assertNull(controller.state.value.artifact)
        open(Any()); val refreshed = artifact()
        assertNotEquals(first.file, refreshed.file); assertEquals(2, reads.size)
        controller.invalidate("unrelated"); assertSame(refreshed, controller.state.value.artifact)
    } }

    @Test fun retryReplacesFailureAndOwnerCloseClearsArtifactsAndSelections() = runBlocking { fixture {
        fail = true; open()
        withTimeout(3000) { controller.state.first { it.error != null } }
        assertNull(controller.state.value.artifact)
        fail = false; controller.retry(); val result = artifact()
        controller.choose(file, ChangesRevision.BASE)
        controller.close(); emptyDisk(); assertFalse(result.file.exists()); assertTrue(controller.revisions.value.isEmpty())
        open(); controller.retry(); assertNull(controller.state.value.artifact); assertEquals(2, reads.size)
    } }

    @Test fun storedRevisionIsBoundedAndRevalidatedAgainstChangedFilePolicy() = runBlocking { fixture {
        controller.choose(file, ChangesRevision.BASE)
        assertEquals(ChangesRevision.CURRENT, controller.revision(file.copy(kind = ChangeKind.ADDED)))
        repeat(40) { controller.choose(file.copy(path = "file-$it"), ChangesRevision.BASE) }
        assertEquals(32, controller.revisions.value.size); assertFalse(controller.revisions.value.containsKey(file.path))
    } }
}
