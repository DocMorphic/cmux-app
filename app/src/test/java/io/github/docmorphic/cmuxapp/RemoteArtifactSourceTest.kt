package io.github.docmorphic.cmuxapp

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteArtifactSourceTest {
    private val capabilities = ArtifactCapabilities(true, true, true, true, true)
    private val terminal = ArtifactAuthorization.Terminal("workspace", "terminal")
    private fun request() = FileSaveSnapshot(UUID.randomUUID().toString(), "report.txt", "text/plain", FileSavePhase.PREPARING)
    private fun stat(text: String, mime: String = "text/plain") = JSONObject().put("exists", true).put("is_directory", false)
        .put("size", text.toByteArray().size).put("kind", "text").put("mime_type", mime)
    private fun chunk(text: String) = JSONObject().put("offset", 0).put("total_size", text.toByteArray().size)
        .put("data_b64", Base64.getEncoder().encodeToString(text.toByteArray())).put("eof", true)

    @Test fun eachExportReadsCurrentMetadataAndBytesWithoutMutatingTheDisplayedPreview() = runBlocking {
        val root = Files.createTempDirectory("remote-export").toFile()
        var content = "old!"; val calls = mutableListOf<String>()
        val source = RemoteArtifactSource(ArtifactRpc(capabilities) { method, _ ->
            calls += method; if (method.endsWith("stat")) stat(content) else chunk(content)
        }, terminal, "/report.txt")
        try {
            val preview = source.materialize(root.resolve("preview"))
            assertEquals("old!", preview.file.readText())
            content = "new!"
            val files = FileSaveFiles(root.resolve("saves")); val request = request()
            source.prepareSave(files, request, source.metadata())
            val output = ByteArrayOutputStream(); files.write(request) { output }
            assertEquals("new!", output.toString("UTF-8")); assertEquals("old!", preview.file.readText())
            content = "latest share"
            assertEquals(content, source.materialize(root.resolve("shares")).file.readText())
            assertEquals(3, calls.count { it.endsWith("stat") }); assertEquals(3, calls.count { it.endsWith("fetch") })
        } finally { root.deleteRecursively() }
    }
    @Test fun terminalSessionAndPanelExportsKeepTheirExactAuthorizationAndPath() = runBlocking {
        val root = Files.createTempDirectory("remote-export-scopes").toFile()
        try {
            for (authorization in listOf(terminal, ArtifactAuthorization.Session("session"),
                ArtifactAuthorization.Panel("workspace", "panel", "/report.txt"))) {
                val calls = mutableListOf<Pair<String, JSONObject>>()
                val source = RemoteArtifactSource(ArtifactRpc(capabilities) { method, params ->
                    calls += method to params; if (method.endsWith("stat")) stat("data") else chunk("data")
                }, authorization, "/report.txt")
                val files = FileSaveFiles(root.resolve("saves")); val request = request()
                source.prepareSave(files, request, source.metadata())
                assertEquals("data", files.file(request).readText()); assertEquals(2, calls.size)
                calls.forEach { (method, params) ->
                    assertEquals("/report.txt", params.getString("path"))
                    when (authorization) {
                        is ArtifactAuthorization.Terminal -> {
                            assertTrue(method.startsWith("mobile.terminal.artifact.")); assertEquals("terminal", params.getString("surface_id"))
                        }
                        is ArtifactAuthorization.Session -> {
                            assertTrue(method.startsWith("mobile.chat.artifact.")); assertEquals("session", params.getString("session_id"))
                        }
                        is ArtifactAuthorization.Panel -> {
                            assertTrue(method.startsWith("mobile.panel.artifact.")); assertEquals("panel", params.getString("surface_id"))
                        }
                    }
                }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun updatedMimeAndFilenameSurviveAnOlderPreparingBundle() = runBlocking {
        val root = Files.createTempDirectory("remote-export-mime").toFile()
        try {
            val source = RemoteArtifactSource(ArtifactRpc(capabilities) { method, _ ->
                if (method.endsWith("stat")) stat("<svg/>", "image/svg+xml") else chunk("<svg/>")
            }, terminal, "/report.txt")
            val metadata = source.metadata(); val original = request()
            val current = original.copy(filename = "report.txt.svg", mime = metadata.mime!!)
            val files = FileSaveFiles(root.resolve("saves"))
            source.prepareSave(files, current, metadata); files.record(current.copy(phase = FileSavePhase.READY))
            val restored = FileSaveFiles(root.resolve("saves")).restore(original)
            assertEquals("report.txt.svg", restored.filename); assertEquals("image/svg+xml", restored.mime)
            assertEquals("<svg/>", files.file(restored).readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun deniedOrMissingCurrentFileDoesNotExportAnOldLocalPreview() = runBlocking {
        val root = Files.createTempDirectory("remote-export-denied").toFile()
        try {
            val stale = root.resolve("preview.txt").also { it.writeText("stale preview") }; var calls = 0
            val source = RemoteArtifactSource(ArtifactRpc(capabilities) { method, _ ->
                calls++; assertTrue(method.endsWith("stat")); JSONObject().put("exists", false)
            }, terminal, "/report.txt")
            val failure = runCatching { source.materialize(root.resolve("exports")) }.exceptionOrNull()
            assertEquals(ArtifactPreviewFailure.Kind.FILE_NOT_FOUND, (failure as ArtifactPreviewException).failure.kind)
            assertEquals(1, calls); assertEquals("stale preview", stale.readText())
            assertTrue(root.resolve("exports").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun savingBeyondThePreviewLimitReachesTheStreamAndCleansFailedPreparation() = runBlocking {
        val root = Files.createTempDirectory("remote-export-large").toFile()
        try {
            var fetched = false
            val source = RemoteArtifactSource(ArtifactRpc(capabilities) { method, _ ->
                if (method.endsWith("stat")) stat("").put("size", ChangesContentTransfer.MEDIA_BYTES + 1)
                else { fetched = true; throw java.io.IOException("offline") }
            }, terminal, "/large.mov")
            val files = FileSaveFiles(root.resolve("saves")); val request = request()
            assertTrue(runCatching { source.prepareSave(files, request, source.metadata()) }.isFailure)
            assertTrue(fetched); assertFalse(files.file(request).parentFile!!.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellingRemotePreparationRejectsLateBytesAndRemovesOnlyItsCopy() = runBlocking {
        val root = Files.createTempDirectory("remote-export-cancel").toFile()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            val source = RemoteArtifactSource(ArtifactRpc(capabilities) { method, _ ->
                if (method.endsWith("stat")) stat("data") else {
                    entered.complete(Unit); withContext(NonCancellable) { release.await() }; chunk("data")
                }
            }, terminal, "/report.txt")
            val files = FileSaveFiles(root.resolve("saves")); val request = request(); var returned = false
            val keep = root.resolve("unrelated").also { it.writeText("keep") }
            val job = launch { source.prepareSave(files, request, source.metadata()); returned = true }
            withTimeout(3000) { entered.await() }; job.cancel(); release.complete(Unit); job.join()
            assertFalse(returned); assertFalse(files.file(request).parentFile!!.exists()); assertEquals("keep", keep.readText())
        } finally { release.complete(Unit); root.deleteRecursively() }
    }
}
