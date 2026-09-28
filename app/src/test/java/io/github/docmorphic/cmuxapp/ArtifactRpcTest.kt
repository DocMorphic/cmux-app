package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtifactRpcTest {
    private val terminal = ArtifactAuthorization.Terminal("workspace", "surface")
    private val session = ArtifactAuthorization.Session("session")
    private val all = ArtifactCapabilities(true, true, true, true)

    @Test fun terminalScanUsesCapabilityFlagsAndBindsSessionFromResponse() = runBlocking {
        val rpc = ArtifactRpc(all) { method, params ->
            assertEquals("mobile.terminal.artifact.scan", method)
            assertEquals("workspace", params.getString("workspace_id")); assertEquals("surface", params.getString("surface_id"))
            assertTrue(params.getBoolean("visible_only")); assertTrue(params.getBoolean("include_directories"))
            assertFalse(params.getBoolean("include_missing")); assertFalse(params.has("count_only"))
            assertFalse(params.has("session_id")); assertTrue(params.getString("trace_id").isNotEmpty())
            JSONObject().put("session_id", " session ").put("session_artifact_total", 71)
        }
        val scan = rpc.scan(terminal, includeMissing = false)
        assertEquals("session", scan.sessionId); assertEquals(71, scan.sessionTotal)
    }
    @Test fun unsupportedFolderFlagsAreOmittedAndListingIsRejectedBeforeSending() = runBlocking {
        var calls = 0
        val rpc = ArtifactRpc(all.copy(terminalFolders = false, sessionFolders = false)) { _, params ->
            calls++; assertFalse(params.has("include_directories")); JSONObject()
        }
        rpc.scan(terminal); rpc.gallery(session)
        for (scope in listOf(terminal, session)) {
            val failure = runCatching { rpc.list(scope, "/folder") }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        }
        assertEquals(2, calls)
    }
    @Test fun sessionGallerySearchPreservesOpaqueCursorAndDoesNotSendTerminalIdentity() = runBlocking {
        val rpc = ArtifactRpc(all) { method, params ->
            assertEquals("mobile.chat.artifact.gallery", method); assertEquals("session", params.getString("session_id"))
            assertEquals("opaque+/=", params.getString("cursor")); assertEquals("report", params.getString("query"))
            assertEquals(60, params.getInt("page_size")); assertTrue(params.getBoolean("include_directories"))
            assertFalse(params.has("workspace_id")); assertFalse(params.has("surface_id"))
            JSONObject().put("session_id", "session")
        }
        rpc.gallery(session, "opaque+/=", "  report  ")
        Unit
    }
    @Test fun previewRequestsKeepTheirCapturedScopeForEveryOperation() = runBlocking {
        val observed = mutableListOf<String>()
        val rpc = ArtifactRpc(all) { method, params ->
            observed += method
            assertEquals("/résumé.png", params.getString("path"))
            if (method.startsWith("mobile.terminal")) {
                assertEquals("surface", params.getString("surface_id")); assertFalse(params.has("session_id"))
            } else {
                assertEquals("session", params.getString("session_id")); assertFalse(params.has("workspace_id"))
            }
            if (method.endsWith(".fetch")) { assertEquals(123L, params.getLong("offset")); assertEquals(1024, params.getInt("length")) }
            if (method.endsWith(".thumbnail")) assertEquals(256, params.getInt("max_dimension"))
            JSONObject()
        }
        for (scope in listOf(terminal, session)) {
            rpc.stat(scope, "/résumé.png"); rpc.fetch(scope, "/résumé.png", 123, 1024)
            rpc.thumbnail(scope, "/résumé.png", 256); rpc.list(scope, "/résumé.png")
        }
        assertEquals(listOf("terminal", "chat").flatMap { type -> listOf("stat", "fetch", "thumbnail", "list").map { "mobile.$type.artifact.$it" } }, observed)
    }
    @Test fun invalidFetchAndPathsAreRejectedLocallyWithoutFallbackRequests() = runBlocking {
        val rpc = ArtifactRpc(all) { _, _ -> fail("No request expected"); JSONObject() }
        assertTrue(runCatching { rpc.fetch(terminal, "/file", -1, 10) }.isFailure)
        assertTrue(runCatching { rpc.fetch(session, "/file", 0, Int.MAX_VALUE) }.isFailure)
        assertTrue(runCatching { rpc.stat(session, "relative") }.isFailure)
        assertTrue(runCatching { rpc.thumbnail(terminal, "/file", 0) }.isFailure)
    }
}
