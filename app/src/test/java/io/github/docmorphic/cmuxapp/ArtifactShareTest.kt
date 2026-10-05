package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Base64

class ArtifactShareTest {
    private fun stat(size: Long = 4) = JSONObject().put("exists", true).put("is_directory", false).put("size", size).put("kind", "text").put("mime_type", "text/plain")
    private fun bytes() = JSONObject().put("offset", 0).put("total_size", 4).put("data_b64", Base64.getEncoder().encodeToString("data".toByteArray())).put("eof", true)
    private fun rpc(reply: suspend (String, JSONObject) -> JSONObject) = ArtifactRpc(ArtifactCapabilities(true, true, true, true), reply)
    @Test fun rowShareKeepsScopeAndOriginalBytesAfterMaterializationReturns() = runBlocking {
        val root = Files.createTempDirectory("artifact-share").toFile()
        try {
            for (scope in listOf(ArtifactAuthorization.Terminal("workspace", "surface"), ArtifactAuthorization.Session("session"))) {
                val artifact = materializeArtifactShare(rpc { method, params ->
                    assertEquals("/résumé.txt", params.getString("path"))
                    if (scope is ArtifactAuthorization.Terminal) {
                        assertTrue(method.startsWith("mobile.terminal.artifact.")); assertFalse(params.has("session_id"))
                    } else { assertTrue(method.startsWith("mobile.chat.artifact.")); assertEquals("session", params.getString("session_id")) }
                    if (method.endsWith("stat")) stat() else bytes()
                }, scope, "/résumé.txt", root)
                assertEquals("résumé.txt", artifact.file.name); assertEquals("data", artifact.file.readText())
                assertFalse(artifact.file.parentFile!!.resolve("download.partial").exists())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun sharingDoesNotApplyInlinePreviewSizeLimit() = runBlocking {
        val root = Files.createTempDirectory("artifact-share").toFile()
        var fetched = false
        try {
            val failure = runCatching { materializeArtifactShare(rpc { method, _ ->
                if (method.endsWith("stat")) stat(ChangesContentTransfer.MEDIA_BYTES + 1)
                else { fetched = true; throw java.io.IOException("Expected fetch reached") }
            }, ArtifactAuthorization.Terminal("workspace", "surface"), "/large.txt", root) }.exceptionOrNull()
            assertTrue(fetched); assertEquals("Expected fetch reached", failure?.message)
            assertEquals(listOf(".operation-locks"), root.list()!!.toList()); assertEquals(0L, root.resolve(".operation-locks").length())
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationRemovesUnpublishedShareAndDoesNotReturnLateBytes() = runBlocking {
        val root = Files.createTempDirectory("artifact-share").toFile()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var returned = false
        try {
            val job = launch { materializeArtifactShare(rpc { method, _ ->
                if (method.endsWith("stat")) stat() else { entered.complete(Unit); withContext(NonCancellable) { release.await() }; bytes() }
            }, ArtifactAuthorization.Terminal("workspace", "surface"), "./file.txt", root); returned = true }
            entered.await(); job.cancel(); release.complete(Unit); job.join()
            assertFalse(returned); assertEquals(listOf(".operation-locks"), root.list()!!.toList()); assertEquals(0L, root.resolve(".operation-locks").length())
        } finally { release.complete(Unit); root.deleteRecursively() }
    }
}
