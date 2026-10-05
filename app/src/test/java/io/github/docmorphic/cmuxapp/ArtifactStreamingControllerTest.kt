package io.github.docmorphic.cmuxapp

import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtifactStreamingControllerTest {
    private suspend fun fixture(chunks: List<ByteArray>, before: suspend (Int) -> Unit = {},
        test: suspend (ArtifactPreviewController) -> Unit) = coroutineScope {
        val root = Files.createTempDirectory("streaming-text").toFile()
        val owner = SupervisorJob(coroutineContext[Job])
        val controller = ArtifactPreviewController(CoroutineScope(coroutineContext + owner))
        val size = chunks.sumOf { it.size }
        var index = 0
        val rpc = ArtifactRpc(ArtifactCapabilities(true, true, true, true, true)) { method, params ->
            if (method.endsWith("stat")) JSONObject().put("exists", true).put("is_directory", false)
                .put("size", size).put("kind", "text").put("mime_type", "text/plain")
            else {
                before(index)
                val bytes = chunks[index++]
                JSONObject().put("offset", params.getLong("offset")).put("total_size", size)
                    .put("eof", index == chunks.size).put("data_b64", Base64.getEncoder().encodeToString(bytes))
            }
        }
        try {
            controller.open(rpc, ArtifactAuthorization.Terminal("workspace", "surface"), "/日本語/report.txt", root)
            withTimeout(5000) { test(controller) }
        } finally { controller.close(); owner.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun publishesReadablePrefixBeforeEofAndCommitsOneStablePath() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val bytes = "first\n日本語🌍last".toByteArray()
        try { fixture(listOf(bytes.copyOfRange(0, 8), bytes.copyOfRange(8, bytes.size)), { if (it == 1) release.await() }) { controller ->
            val early = controller.state.first { it.text != null }
            assertEquals("first\n", early.text!!.document.text)
            assertFalse(early.text.complete); assertNull(early.artifact)
            assertFalse(early.text.artifact.file.exists())
            release.complete(Unit)
            val done = controller.state.first { it.artifact != null }
            assertTrue(done.text!!.complete); assertEquals("first\n日本語🌍last", done.text.document.text)
            assertEquals(early.text.artifact.file, done.artifact!!.file)
            assertArrayEquals(bytes, done.artifact.file.readBytes())
        } } finally { release.complete(Unit) }
    }
    @Test fun disconnectRetiresProgressiveTextAndRejectsLatePublication() = runBlocking {
        val release = CompletableDeferred<Unit>()
        try { fixture(listOf("first".toByteArray(), "second".toByteArray()), { if (it == 1) withContext(NonCancellable) { release.await() } }) { controller ->
            controller.state.first { it.text != null }
            controller.connectionLost(); release.complete(Unit)
            delay(40)
            assertNull(controller.state.value.text); assertNull(controller.state.value.artifact)
            assertEquals(ArtifactPreviewFailure.Kind.MAC_UNREACHABLE, controller.state.value.failure?.kind)
        } } finally { release.complete(Unit) }
    }
    @Test fun invalidTextFallsBackWithoutChangingExportBytes() = runBlocking {
        val bytes = byteArrayOf(0xFF.toByte(), 1, 2, 3)
        fixture(listOf(bytes)) { controller ->
            val done = controller.state.first { it.artifact != null }
            assertNull(done.text); assertNull(done.error)
            assertEquals(ChangesPreviewRoute.EXTERNAL, done.artifact!!.route)
            assertArrayEquals(bytes, done.artifact.file.readBytes())
        }
    }
}
