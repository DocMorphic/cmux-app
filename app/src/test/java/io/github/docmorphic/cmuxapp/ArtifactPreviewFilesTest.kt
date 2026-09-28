package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class ArtifactPreviewFilesTest {
    private val authorization = ArtifactAuthorization.Terminal("workspace", "surface")
    private fun rpc(reply: suspend (String, JSONObject) -> JSONObject) = ArtifactRpc(ArtifactCapabilities(true, true, true, true), reply)
    private fun chunk(data: String, offset: Long, total: Long, eof: Boolean) = JSONObject().put("offset", offset).put("total_size", total)
        .put("data_b64", Base64.getEncoder().encodeToString(data.toByteArray())).put("eof", eof)
    private fun metadata(size: Long = 4) = ArtifactMetadata(size, ArtifactKind.TEXT, "text/plain")

    @Test fun ordinaryArtifactsDoNotRequireOrInventChangesFingerprints() = runBlocking {
        val transfer = ArtifactContentTransfer(rpc { method, params ->
            assertEquals("surface", params.getString("surface_id")); assertFalse(params.has("session_id"))
            if (method.endsWith("stat")) JSONObject().put("exists", true).put("is_directory", false).put("size", 4).put("kind", "text")
            else chunk("data", 0, 4, true)
        }, authorization)
        val metadata = transfer.metadata("/file")
        var result = ""
        transfer.stream("/file", metadata, 4) { bytes, _ -> result += bytes.toString(Charsets.UTF_8) }
        assertEquals("data", result)
    }
    @Test fun orderedChunksAndEmptyFileHaveExactProgress() = runBlocking {
        val offsets = mutableListOf<Long>()
        val transfer = ArtifactContentTransfer(rpc { _, params ->
            val offset = params.getLong("offset"); offsets += offset
            if (offset == 0L) chunk("da", 0, 4, false) else chunk("ta", 2, 4, true)
        }, authorization)
        val progress = mutableListOf<Long>()
        transfer.stream("/file", metadata(), 4) { _, at -> progress += at }
        assertEquals(listOf(0L, 2L), offsets); assertEquals(listOf(2L, 4L), progress)
        ArtifactContentTransfer(rpc { _, _ -> chunk("", 0, 0, true) }, authorization).stream("/empty", metadata(0), 0) { bytes, at ->
            assertEquals(0, bytes.size); assertEquals(0L, at)
        }
    }
    @Test fun corruptOrChangedChunksAreRejectedBeforePublishingBytes() = runBlocking {
        val invalid = listOf(chunk("data", 1, 4, true), chunk("data", 0, 8, true), chunk("da", 0, 4, true),
            chunk("", 0, 4, false), chunk("data", 0, 4, false), chunk("extra", 0, 4, true),
            chunk("data", 0, 4, true).put("data_b64", "not%base64"))
        for (value in invalid) {
            var published = false
            val failure = runCatching { ArtifactContentTransfer(rpc { _, _ -> value }, authorization)
                .stream("/file", metadata(), 4) { _, _ -> published = true } }.exceptionOrNull()
            assertNotNull(failure); assertFalse(published)
        }
    }
    @Test fun completedDownloadExportsIndependentlyAndPartialNameCannotAliasDestination() = runBlocking {
        val root = Files.createTempDirectory("artifact-preview").toFile()
        val files = ArtifactPreviewFiles(root.resolve("private"), ArtifactContentTransfer(rpc { _, _ -> chunk("data", 0, 4, true) }, authorization))
        try {
            val artifact = files.download("/download.partial", metadata()) { _, _ -> }
            assertEquals("data", artifact.file.readText()); assertEquals("file-download.partial", artifact.file.name)
            val exported = exportFilePreview(artifact, root.resolve("exports"))
            files.close(); assertFalse(artifact.file.exists()); assertEquals("data", exported.readText())
        } finally { files.close(); root.deleteRecursively() }
    }
    @Test fun failedTransferRemovesPrivatePartialDirectory() = runBlocking {
        val root = Files.createTempDirectory("artifact-preview").toFile()
        val files = ArtifactPreviewFiles(root, ArtifactContentTransfer(rpc { _, _ -> chunk("da", 0, 4, true) }, authorization))
        try {
            assertTrue(runCatching { files.download("/file", metadata()) { _, _ -> } }.isFailure)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { files.close(); root.deleteRecursively() }
    }
    @Test fun cancelledNonCooperativeFetchCannotLeaveAFileOrPublishProgress() = runBlocking {
        val root = Files.createTempDirectory("artifact-preview").toFile()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var progress = false
        val files = ArtifactPreviewFiles(root, ArtifactContentTransfer(rpc { _, _ ->
            entered.complete(Unit); withContext(NonCancellable) { release.await() }; chunk("data", 0, 4, true)
        }, authorization))
        try {
            val job = launch { files.download("/file", metadata()) { _, _ -> progress = true } }
            entered.await(); job.cancel(); release.complete(Unit); job.join()
            assertFalse(progress); assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { release.complete(Unit); files.close(); root.deleteRecursively() }
    }
    @Test fun previewLimitsStopBeforeAnyFetchOrPrivateFileIsCreated() = runBlocking {
        val root = Files.createTempDirectory("artifact-preview").toFile()
        try {
            for ((size, mime) in listOf(ChangesContentTransfer.PREVIEW_BYTES + 1 to "image/png", ChangesContentTransfer.MEDIA_BYTES + 1 to "video/mp4")) {
                val files = ArtifactPreviewFiles(root, ArtifactContentTransfer(rpc { _, _ -> fail("No bytes should be requested"); JSONObject() }, authorization))
                try { assertTrue(runCatching { files.download("/file", ArtifactMetadata(size, ArtifactKind.BINARY, mime)) { _, _ -> } }.isFailure) }
                finally { files.close() }
            }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
