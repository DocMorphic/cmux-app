package io.github.docmorphic.cmuxapp

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ComposerAttachmentPreviewControllerTest {
    private class Fixture(parent: CoroutineScope) {
        val root = Files.createTempDirectory("task-preview").toFile()
        val job = SupervisorJob(parent.coroutineContext[Job])
        val controller = ComposerAttachmentPreviewController(CoroutineScope(parent.coroutineContext + job))
        val bytes = "exact staged bytes\n".toByteArray()
        val identity = ComposerAttachmentPreviewIdentity("presentation", ComposerAttachmentPreviewOwner.Task("session", "draft", "mac"),
            ComposerAttachment(name = "note.txt", size = bytes.size))
        var reads = 0
        var valid = true
        var beforeRead: suspend () -> Unit = {}
        fun open(id: ComposerAttachmentPreviewIdentity = identity) = controller.open(id, root, "text/plain", { valid }) {
            reads++; beforeRead(); bytes
        }
        suspend fun artifact() = withTimeout(3000) { controller.state.first { it.artifact != null }.artifact!! }
        suspend fun error() = withTimeout(3000) { controller.state.first { it.error != null }.error!! }
        suspend fun emptyDisk() = withTimeout(3000) { while (root.listFiles().orEmpty().any { it.isDirectory }) delay(5) }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) = coroutineScope {
        val f = Fixture(this)
        try { f.block() } finally { f.controller.close(); f.job.cancelAndJoin(); f.root.deleteRecursively() }
    }

    @Test fun reattachmentRetainsExactFileWithoutDecryptingAgain() = runBlocking { fixture {
        open(); val first = artifact()
        repeat(3) { open() }; yield()
        assertSame(first, controller.state.value.artifact)
        assertEquals(1, reads); assertArrayEquals(bytes, first.file.readBytes())
        assertEquals(ChangesPreviewRoute.TEXT, first.route)
        controller.clear(identity); emptyDisk(); assertFalse(first.file.exists())
    } }

    @Test fun changingPresentationAccountDraftOriginOrMetadataNeverReusesOldBytes() = runBlocking { fixture {
        open(); var previous = artifact()
        val variants = listOf(identity.copy(presentation = "other"), identity.copy(owner = ComposerAttachmentPreviewOwner.Task("other", "draft", "mac")),
            identity.copy(owner = ComposerAttachmentPreviewOwner.Task("session", "other", "mac")), identity.copy(owner = ComposerAttachmentPreviewOwner.Task("session", "draft", "other")),
            identity.copy(attachment = identity.attachment.copy(name = "other.txt")))
        for (next in variants) {
            open(next); val current = artifact()
            assertNotEquals(previous.file, current.file)
            withTimeout(3000) { while (previous.file.exists()) delay(5) }
            controller.clear(identity) // A retired dialog must not dismiss its replacement.
            assertSame(current, controller.state.value.artifact)
            previous = current
        }
        assertEquals(6, reads)
    } }

    @Test fun taskNativeAndSshComposersCannotSharePreviewEvenForIdenticalAttachmentIds() = runBlocking { fixture {
        open(); var previous = artifact()
        val terminal = TerminalDrafts.Target("mac", "workspace", "surface")
        val owners = listOf(ComposerAttachmentPreviewOwner.Terminal(terminal, 0),
            ComposerAttachmentPreviewOwner.Terminal(terminal.copy(surface = "other"), 0),
            ComposerAttachmentPreviewOwner.Terminal(terminal, 1),
            ComposerAttachmentPreviewOwner.Ssh("binding-A"), ComposerAttachmentPreviewOwner.Ssh("binding-B"))
        for (owner in owners) {
            open(identity.copy(owner = owner)); val next = artifact()
            assertNotEquals(previous.file, next.file)
            withTimeout(3000) { while (previous.file.exists()) delay(5) }
            assertArrayEquals(bytes, next.file.readBytes()); previous = next
        }
    } }

    @Test fun lateCancelledReadCannotPublishOverOrDeleteNewSelection() = runBlocking { fixture {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeRead = { if (reads == 1) { started.complete(Unit); withContext(NonCancellable) { release.await() } } }
        try {
            open(); withTimeout(3000) { started.await() }
            open(identity.copy(presentation = "new")); val current = artifact()
            release.complete(Unit); delay(50)
            assertSame(current, controller.state.value.artifact); assertTrue(current.file.exists())
            assertEquals(1, root.listFiles().orEmpty().count { it.isDirectory })
        } finally { release.complete(Unit) }
    } }

    @Test fun closingDuringReadRejectsLatePublicationAndReopening() = runBlocking { fixture {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeRead = { started.complete(Unit); withContext(NonCancellable) { release.await() } }
        try {
            open(); withTimeout(3000) { started.await() }; controller.close(); release.complete(Unit)
            delay(50); emptyDisk(); open(); controller.retry(identity); yield()
            assertEquals(ComposerAttachmentPreviewState(), controller.state.value); assertEquals(1, reads)
        } finally { release.complete(Unit) }
    } }

    @Test fun removedOrRetargetedDraftDuringReadCannotExposeBytes() = runBlocking { fixture {
        beforeRead = { valid = false }
        open(); assertTrue(error().contains("composer changed")); emptyDisk()
        assertNull(controller.state.value.artifact)
        valid = true; beforeRead = {}; controller.retry(identity)
        assertArrayEquals(bytes, artifact().file.readBytes())
        valid = false; open(); emptyDisk(); assertEquals(ComposerAttachmentPreviewState(), controller.state.value)
    } }

    @Test fun initialInvalidOwnerDoesNotReadAndReadFailureCanRetry() = runBlocking { fixture {
        valid = false; open(); error(); assertEquals(0, reads)
        valid = true; beforeRead = { error("Encrypted file unavailable") }
        controller.retry(identity); assertEquals("Encrypted file unavailable", error()); emptyDisk()
        beforeRead = {}; controller.retry(identity); artifact(); assertEquals(2, reads)
    } }

    @Test fun corruptLengthIsRejectedAndPathCannotEscapePrivateDirectory() = runBlocking { fixture {
        open(identity.copy(attachment = identity.attachment.copy(size = bytes.size + 1)))
        assertTrue(error().contains("incomplete")); emptyDisk()
        open(identity.copy(attachment = identity.attachment.copy(name = "../../evil\\report.txt")))
        val file = artifact().file
        assertEquals("report.txt", file.name); assertEquals(root.canonicalFile, checkNotNull(checkNotNull(file.parentFile).parentFile).canonicalFile)
        assertArrayEquals(bytes, file.readBytes())
    } }

    @Test fun activePreviewSurvivesCachePruningAndOrphanedPreviewIsReclaimed() = runBlocking { fixture {
        open(); val file = artifact().file
        val old = System.currentTimeMillis() - 7_200_000
        assertTrue(checkNotNull(file.parentFile).setLastModified(old))
        val orphan = java.io.File(root, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        java.io.File(orphan, "abandoned.txt").writeText("stale bytes")
        assertTrue(orphan.setLastModified(old))
        withContext(Dispatchers.IO) { ArtifactExportCache.prune(root) }
        assertTrue(file.exists()); assertFalse(orphan.exists())
        controller.clear(identity); emptyDisk()
    } }

    @Test fun emptyTextAndSupportedFormatsUseFullViewerWhileUnknownBinaryHasExternalActions() {
        fun route(name: String, mime: String? = null) = composerAttachmentPreviewRoute(ComposerAttachment(name = name, size = 0), mime)
        assertEquals(ChangesPreviewRoute.TEXT, route("empty.txt"))
        assertEquals(ChangesPreviewRoute.TEXT, route("report.md"))
        assertEquals(ChangesPreviewRoute.TEXT, route("source.kt"))
        assertEquals(ChangesPreviewRoute.IMAGE, route("photo.png", "image/png"))
        assertEquals(ChangesPreviewRoute.PDF, route("report.pdf"))
        assertEquals(ChangesPreviewRoute.MEDIA, route("recording.mp4"))
        assertEquals(ChangesPreviewRoute.EXTERNAL, route("archive.zip", "application/zip"))
    }
}
