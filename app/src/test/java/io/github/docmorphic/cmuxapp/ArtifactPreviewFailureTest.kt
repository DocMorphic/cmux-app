package io.github.docmorphic.cmuxapp

import java.io.EOFException
import java.net.SocketTimeoutException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ArtifactPreviewFailureTest {
    private val panel = ArtifactAuthorization.Panel("w", "s", "/file")
    private val terminal = ArtifactAuthorization.Terminal("w", "s")
    private fun failure(code: String) = ArtifactPreviewFailure.from(MobileRpcException(code, "Do not expose raw host text"), panel)
    private fun copy(code: String, markdown: Boolean = true) = failure(code).presentation(panel, markdown, NativeFeedAvailability.CONNECTED)

    @Test fun markdownFailureVocabularyMatchesTheScopedSwiftReference() {
        val expected = mapOf(
            "file_not_found" to ("File not found" to false),
            "forbidden" to ("Preview unavailable" to false),
            "permission_denied" to ("Preview unavailable" to false),
            "workspace_not_found" to ("Panel closed" to false),
            "terminal_not_found" to ("Panel closed" to false),
            "session_not_found" to ("Panel closed" to false),
            "method_not_found" to ("Update cmux on your Mac" to false),
            "capability_disabled" to ("Update cmux on your Mac" to false),
            "unavailable" to ("Transfer unavailable" to true),
            "file_changed" to ("Transfer unavailable" to true),
            "request_timed_out" to ("Transfer unavailable" to true),
            "connection_recovering" to ("Transfer unavailable" to true),
            "invalid_params" to ("Couldn't load file" to true),
            "future_error" to ("Couldn't load file" to true))
        expected.forEach { (code, value) -> assertEquals(code, value, copy(code).let { it.title to it.retry }) }
        assertTrue(copy("invalid_params").message.endsWith("(invalid_params)"))
        assertFalse(copy("future_error").message.contains("Do not expose"))
    }

    @Test fun filePreviewPreservesMoreSpecificFailuresThanMarkdown() {
        assertEquals("Permission denied", copy("permission_denied", false).title)
        assertFalse(copy("permission_denied", false).retry)
        assertEquals("Invalid file request", copy("invalid_params", false).title)
        assertFalse(copy("invalid_params", false).retry)
        assertEquals("File changed", copy("file_changed", false).title)
        assertTrue(copy("file_changed", false).retry)
        assertEquals("File previews unavailable", copy("method_not_found", false).title)
        assertTrue(copy("forbidden", false).message.contains("panel"))
        assertTrue(failure("forbidden").presentation(terminal, false, NativeFeedAvailability.CONNECTED).message.contains("terminal"))
    }

    @Test fun legacyNotFoundKeepsItsAuthorizationScope() {
        val error = MobileRpcException("not_found", "missing")
        assertEquals(ArtifactPreviewFailure.Kind.TERMINAL_NOT_FOUND, ArtifactPreviewFailure.from(error, terminal).kind)
        assertEquals(ArtifactPreviewFailure.Kind.SESSION_NOT_FOUND, ArtifactPreviewFailure.from(error, panel).kind)
        assertEquals("Panel closed", copy("not_found").title)
    }

    @Test fun transportTimeoutAndMalformedResponseDoNotShareAMessage() {
        assertEquals(ArtifactPreviewFailure.Kind.REQUEST_TIMED_OUT, ArtifactPreviewFailure.from(SocketTimeoutException()).kind)
        val unreachable = ArtifactPreviewFailure.from(EOFException())
        assertEquals("Mac unreachable", unreachable.presentation(panel, true, NativeFeedAvailability.CONNECTED).title)
        assertEquals("Reconnecting…", unreachable.presentation(panel, true, NativeFeedAvailability.CONNECTING).title)
        assertEquals("Not connected", unreachable.presentation(panel, true, NativeFeedAvailability.OFFLINE).title)
        assertEquals("Couldn't load file", ArtifactPreviewFailure.from(JSONObject().let { runCatching { it.getString("missing") }.exceptionOrNull()!! })
            .presentation(panel, true, NativeFeedAvailability.CONNECTED).title)
        assertEquals(ArtifactPreviewFailure.Kind.TRANSFER_INTERRUPTED, ArtifactPreviewFailure.from(ArtifactLaneTransfer.Interrupted()).kind)
    }

    @Test fun metadataAndLimitFailuresRetainTypedReasonAndNeverFetch() = runBlocking {
        val root = Files.createTempDirectory("typed-preview").toFile()
        val job = SupervisorJob(coroutineContext[Job]); val controller = ArtifactPreviewController(CoroutineScope(coroutineContext + job))
        var size = ChangesContentTransfer.PREVIEW_BYTES + 1; var exists = true; var stats = 0
        val rpc = ArtifactRpc(ArtifactCapabilities(false, false, false, false, true)) { method, _ ->
            check(method.endsWith("stat")) { "Should not fetch" }; stats++
            JSONObject().put("exists", exists).put("is_directory", false).put("size", size).put("kind", "text")
        }
        try {
            controller.open(rpc, panel, "/file", root, true)
            val large = withTimeout(3000) { controller.state.first { it.failure != null }.failure!! }
            assertEquals(ArtifactPreviewFailure.Kind.TOO_LARGE, large.kind)
            assertEquals(size, large.actualSize); assertEquals(ChangesContentTransfer.PREVIEW_BYTES, large.limit)
            assertFalse(large.presentation(panel, true, NativeFeedAvailability.CONNECTED).retry)
            assertTrue(root.listFiles().orEmpty().isEmpty())
            exists = false; controller.retry()
            val missing = withTimeout(3000) { controller.state.first { it.failure != null }.failure!! }
            assertEquals(ArtifactPreviewFailure.Kind.FILE_NOT_FOUND, missing.kind); assertEquals(2, stats)
        } finally { controller.close(); job.cancelAndJoin(); root.deleteRecursively() }
    }
}
