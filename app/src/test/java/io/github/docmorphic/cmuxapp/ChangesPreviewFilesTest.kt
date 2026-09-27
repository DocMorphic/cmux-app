package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class ChangesPreviewFilesTest {
    private fun file(kind: ChangeKind) = ChangedFile("new/image.png", "old/image.png", kind, 0, 0, true, false)
    private fun meta(size: Long = 4, kind: String = "image", mime: String? = "image/png") = ChangesFileMetadata(size, kind, mime, "blob:abc:4")
    private fun chunk(data: String = "data", eof: Boolean = true) = JSONObject().put("data_b64", Base64.getEncoder().encodeToString(data.toByteArray()))
        .put("offset", 0).put("total_size", 4).put("eof", eof).put("content_fingerprint", "blob:abc:4")
    @Test fun revisionAvailabilityAndRenamePathsMatchUpstream() {
        assertEquals(ChangesRevision.CURRENT, ChangesPreviewPolicy.forFile(file(ChangeKind.RENAMED)).initial)
        assertEquals(ChangesRevision.entries, ChangesPreviewPolicy.forFile(file(ChangeKind.MODIFIED)).revisions)
        assertEquals(listOf(ChangesRevision.BASE), ChangesPreviewPolicy.forFile(file(ChangeKind.DELETED)).revisions)
        listOf(ChangeKind.ADDED, ChangeKind.UNTRACKED, ChangeKind.UNKNOWN).forEach {
            assertEquals(listOf(ChangesRevision.CURRENT), ChangesPreviewPolicy.forFile(file(it)).revisions)
        }
        assertEquals("old/image.png", ChangesPreviewPolicy.path(file(ChangeKind.RENAMED), ChangesRevision.BASE))
        assertEquals("new/image.png", ChangesPreviewPolicy.path(file(ChangeKind.RENAMED), ChangesRevision.CURRENT))
    }
    @Test fun previewRoutesUseMimeAndFilenameWhilePreservingImagePriority() {
        assertEquals(ChangesPreviewRoute.IMAGE, changesPreviewRoute(meta(), "file.pdf"))
        assertEquals(ChangesPreviewRoute.PDF, changesPreviewRoute(meta(kind = "binary", mime = "Application/PDF; charset=utf-8"), "file"))
        assertEquals(ChangesPreviewRoute.MEDIA, changesPreviewRoute(meta(kind = "binary", mime = "audio/flac"), "file"))
        assertEquals(ChangesPreviewRoute.MEDIA, changesPreviewRoute(meta(kind = "binary", mime = null), "file.MOV"))
        assertEquals(ChangesPreviewRoute.TEXT, changesPreviewRoute(meta(kind = "text", mime = null), "file"))
        assertEquals(ChangesPreviewRoute.EXTERNAL, changesPreviewRoute(meta(kind = "binary", mime = null), "file.bin"))
    }
    @Test fun downloadedRevisionIsExactAndExportSurvivesPreviewCleanup() = runBlocking {
        val root = Files.createTempDirectory("cmux-preview-test").toFile()
        val transfer = ChangesContentTransfer({ _, _ -> error("metadata already supplied") }, { path, revision, offset, length ->
            assertEquals("old/image.png", path); assertEquals(ChangesRevision.BASE, revision); assertEquals(0L, offset); assertEquals(3 * 1024 * 1024, length); chunk()
        })
        val session = ChangesPreviewFiles(root.resolve("private"), transfer)
        try {
            val progress = mutableListOf<Pair<Long, Long>>()
            val artifact = session.download("old/image.png", ChangesRevision.BASE, meta()) { at, total -> progress += at to total }
            assertEquals("data", artifact.file.readText()); assertEquals(listOf(4L to 4L), progress)
            assertFalse(artifact.file.parentFile!!.resolve("download.partial").exists())
            val exported = exportChangesPreview(artifact, root.resolve("exports"))
            session.close(); assertFalse(artifact.file.exists()); assertEquals("data", exported.readText())
            assertEquals("image.png", exported.name)
        } finally { session.close(); root.deleteRecursively() }
    }
    @Test fun failedTransferNeverLeavesACompletedOrPartialFile() = runBlocking {
        val root = Files.createTempDirectory("cmux-preview-test").toFile()
        val session = ChangesPreviewFiles(root, ChangesContentTransfer({ _, _ -> error("unused") }, { _, _, _, _ -> chunk("da", true) }))
        try {
            try { session.download("image.png", ChangesRevision.BASE, meta()) { _, _ -> }; fail() } catch (_: IllegalStateException) { }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { session.close(); root.deleteRecursively() }
    }
    @Test fun nonCooperativeDownloadCancelledDuringFetchCannotLeavePreviewBytes() = runBlocking {
        val root = Files.createTempDirectory("cmux-preview-test").toFile()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val session = ChangesPreviewFiles(root, ChangesContentTransfer({ _, _ -> error("unused") }, { _, _, _, _ ->
            started.complete(Unit); withContext(NonCancellable) { release.await() }; chunk()
        }))
        try {
            val job = launch { session.download("image.png", ChangesRevision.BASE, meta()) { _, _ -> } }
            started.await(); job.cancel(); release.complete(Unit); job.join()
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { session.close(); root.deleteRecursively() }
    }
    @Test fun previewLimitsMatchUpstreamAndStopBeforeRequestingBytes() = runBlocking {
        val root = Files.createTempDirectory("cmux-preview-test").toFile()
        var requested = false
        try {
            for ((size, mime, name) in listOf(Triple(64L * 1024 * 1024 + 1, "image/png", "image.png"), Triple(512L * 1024 * 1024 + 1, "video/mp4", "movie.mp4"))) {
                val session = ChangesPreviewFiles(root, ChangesContentTransfer({ _, _ -> error("unused") }, { _, _, _, _ -> requested = true; chunk() }))
                try { session.download(name, ChangesRevision.CURRENT, meta(size, "binary", mime)) { _, _ -> }; fail() } catch (_: IllegalStateException) { }
                finally { session.close() }
            }
            assertFalse(requested); assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun exportedNamesCannotEscapeTheirPrivateDirectory() {
        assertEquals("file", changesPreviewName("..")); assertEquals("file", changesPreviewName("."))
        assertEquals("image.png", changesPreviewName("../../secret\\image.png"))
        assertEquals("hello.png", changesPreviewName("\u0000hello\n.png"))
        assertEquals(120, changesPreviewName("x".repeat(300)).length)
    }
}
