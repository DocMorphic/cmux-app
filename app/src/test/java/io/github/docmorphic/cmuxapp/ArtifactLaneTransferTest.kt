package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.nio.file.Files

class ArtifactLaneTransferTest {
    private val terminal = ArtifactAuthorization.Terminal("workspace", "surface")
    private val caps = ArtifactCapabilities(true, true, true, true)
    private fun metadata(size: Long) = ArtifactMetadata(size, ArtifactKind.TEXT, "text/plain")
    private fun descriptor(size: Long) = JSONObject().put("resource_id", "artifact:fixture-capability")
        .put("total_size", size).put("expires_at", "2030-01-01T00:00:00Z")
    private fun rpcChunk(text: String) = JSONObject().put("offset", 0).put("total_size", text.toByteArray().size)
        .put("data_b64", Base64.getEncoder().encodeToString(text.toByteArray())).put("eof", true)
    private class Lane : ArtifactLane {
        val chunks = Channel<ByteArray?>(16)
        var closed = false
        var reads = 0
        override suspend fun read(maximumBytes: Int): ByteArray? {
            assertEquals(64 * 1024, maximumBytes); reads++
            return chunks.receive()
        }
        override fun close() { closed = true; chunks.close() }
    }
    private fun rpc(lane: Lane, request: suspend (String, JSONObject) -> JSONObject) = ArtifactRpc(caps,
        { resource, use -> assertEquals("artifact:fixture-capability", resource); try { use(lane); true } finally { lane.close() } }, request)

    @Test fun terminalAndChatMintOnlyTheirCapturedAuthorizationAndStreamRawBytes() = runBlocking<Unit> {
        for (scope in listOf(terminal, ArtifactAuthorization.Session("session"))) {
            val lane = Lane(); lane.chunks.send("data".toByteArray()); lane.chunks.send(null)
            var calls = 0
            val rpc = rpc(lane) { method, params ->
                calls++
                assertEquals("mobile.${if (scope is ArtifactAuthorization.Terminal) "terminal" else "chat"}.artifact.fetch", method)
                assertEquals("iroh_artifact_v1", params.getString("transport")); assertEquals("/file", params.getString("path"))
                assertFalse(params.has("offset")); assertFalse(params.has("length"))
                if (scope is ArtifactAuthorization.Terminal) {
                    assertEquals("workspace", params.getString("workspace_id")); assertEquals("surface", params.getString("surface_id"))
                    assertFalse(params.has("session_id"))
                } else { assertEquals("session", params.getString("session_id")); assertFalse(params.has("surface_id")) }
                descriptor(4)
            }
            var result = ""
            ArtifactContentTransfer(rpc, scope).stream("/file", metadata(4), 10) { bytes, end -> result += bytes.decodeToString(); assertEquals(4L, end) }
            assertEquals("data", result); assertEquals(1, calls); assertTrue(lane.closed)
        }
    }

    @Test fun firstReadFailureFallsBackOnceToSameScopeAtZero() = runBlocking<Unit> {
        val lane = Lane(); lane.chunks.close(IOException("fixture reset"))
        var calls = 0
        val rpc = rpc(lane) { _, params ->
            calls++; assertEquals("surface", params.getString("surface_id"))
            if (params.has("transport")) descriptor(4)
            else { assertEquals(0L, params.getLong("offset")); rpcChunk("data") }
        }
        var result = ""
        ArtifactContentTransfer(rpc, terminal).stream("/file", metadata(4), 10) { bytes, _ -> result += bytes.decodeToString() }
        assertEquals("data", result); assertEquals(2, calls); assertTrue(lane.closed)
    }

    @Test fun descriptorOrOpenFailureFallsBackWithoutPublishingData() = runBlocking<Unit> {
        for (badDescriptor in listOf(true, false)) {
            var calls = 0
            val rpc = ArtifactRpc(caps, { _, _ -> throw IOException("open failed") }) { _, params ->
                calls++
                if (params.has("transport")) descriptor(if (badDescriptor) 99 else 4) else rpcChunk("data")
            }
            var result = ""
            ArtifactContentTransfer(rpc, terminal).stream("/file", metadata(4), 10) { bytes, _ -> result += bytes.decodeToString() }
            assertEquals("data", result); assertEquals(2, calls)
        }
    }

    @Test fun midstreamFailureNeverMixesRpcBytesAndDeletesPartialPreview() = runBlocking<Unit> {
        val lane = Lane(); lane.chunks.send("old".toByteArray()); lane.chunks.close(IOException("reset"))
        var calls = 0
        val rpc = rpc(lane) { _, params -> calls++; assertTrue(params.has("transport")); descriptor(6) }
        val root = Files.createTempDirectory("cmux-lane-preview").toFile()
        try {
            ArtifactPreviewFiles(root, ArtifactContentTransfer(rpc, terminal)).use { files ->
                val failure = runCatching { files.download("/file", metadata(6)) { _, _ -> } }.exceptionOrNull()
                assertTrue(failure is ArtifactLaneTransfer.Interrupted)
                assertEquals(1, calls); assertTrue(lane.closed); assertTrue(root.listFiles()!!.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun finalChunkWaitsForEofAndExtraBytesNeverCompleteTheDownload() = runBlocking<Unit> {
        val lane = Lane(); lane.chunks.send("data".toByteArray())
        var deliveries = 0
        val job = async { runCatching { ArtifactLaneTransfer.stream(lane, 4) { _, _ -> deliveries++ } } }
        withTimeout(2000) { while (lane.reads < 2) yield() }
        assertEquals(0, deliveries)
        lane.chunks.send(byteArrayOf(1))
        assertTrue(job.await().exceptionOrNull() is ArtifactLaneTransfer.Interrupted)
        assertEquals(0, deliveries); lane.close()
        val empty = Lane(); empty.chunks.send(null)
        ArtifactLaneTransfer.stream(empty, 0) { bytes, end -> assertTrue(bytes.isEmpty()); assertEquals(0L, end); deliveries++ }
        assertEquals(1, deliveries); empty.close()
    }

    @Test fun consumerBackpressurePreventsFurtherReadAndConsumerErrorsNeverFallback() = runBlocking<Unit> {
        val lane = Lane(); lane.chunks.send("a".toByteArray()); lane.chunks.send("b".toByteArray()); lane.chunks.send(null)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val rpc = rpc(lane) { _, _ -> calls++; descriptor(2) }
        val transfer = async { runCatching {
            ArtifactContentTransfer(rpc, terminal).stream("/file", metadata(2), 10) { _, _ ->
                entered.complete(Unit); release.await(); throw IOException("disk full")
            }
        } }
        withTimeout(2000) { entered.await() }; assertEquals(1, lane.reads)
        release.complete(Unit)
        assertEquals("disk full", transfer.await().exceptionOrNull()?.message)
        assertEquals(1, calls); assertTrue(lane.closed)
    }

    @Test fun callerCancellationDuringFirstReadClosesLaneWithoutRpcRestart() = runBlocking<Unit> {
        val lane = Lane(); var calls = 0
        val rpc = rpc(lane) { _, _ -> calls++; descriptor(4) }
        val transfer = launch { ArtifactContentTransfer(rpc, terminal).stream("/file", metadata(4), 10) { _, _ -> fail("No data") } }
        withTimeout(2000) { while (lane.reads == 0) yield() }
        transfer.cancelAndJoin()
        assertEquals(1, calls); assertTrue(lane.closed)
    }
}
