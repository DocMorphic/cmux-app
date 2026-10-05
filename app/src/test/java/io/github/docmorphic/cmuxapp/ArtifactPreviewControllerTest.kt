package io.github.docmorphic.cmuxapp

import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtifactPreviewControllerTest {
    private class Fixture(parent: CoroutineScope) {
        val root = Files.createTempDirectory("cmux-artifact-owner").toFile()
        val owner = SupervisorJob(parent.coroutineContext[Job])
        val controller = ArtifactPreviewController(CoroutineScope(parent.coroutineContext + owner))
        val terminal = ArtifactAuthorization.Terminal("workspace", "surface")
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        var beforeFetch: suspend (JSONObject) -> Unit = {}
        var fail = false
        fun rpc() = ArtifactRpc(ArtifactCapabilities(true, true, true, true, true)) { method, params ->
            calls += method to params
            if (method.endsWith("stat")) JSONObject().put("exists", true).put("is_directory", false)
                .put("size", 4).put("kind", "text").put("mime_type", "text/plain")
            else {
                beforeFetch(params)
                if (fail) error("Transfer interrupted")
                JSONObject().put("offset", 0).put("total_size", 4).put("eof", true)
                    .put("data_b64", Base64.getEncoder().encodeToString("data".toByteArray()))
            }
        }
        val rpc = rpc()
        fun open(rpc: ArtifactRpc = this.rpc, authorization: ArtifactAuthorization = terminal,
                 path: String = "/report.txt", markdown: Boolean = false) = controller.open(rpc, authorization, path, root, markdown)
        suspend fun artifact() = withTimeout(3000) { controller.state.first { it.artifact != null }.artifact!! }
        suspend fun emptyDisk() = withTimeout(3000) { while (root.listFiles().orEmpty().isNotEmpty()) delay(5) }
        fun fetches() = calls.count { it.first.endsWith("fetch") }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) = coroutineScope {
        val f = Fixture(this)
        try { f.block() } finally { f.controller.close(); f.owner.cancelAndJoin(); f.root.deleteRecursively() }
    }

    @Test fun reattachingWhileLoadingAndAfterCompletionKeepsOneTransferAndOneFile() = runBlocking { fixture {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeFetch = { started.complete(Unit); release.await() }
        open(); withTimeout(3000) { started.await() }
        val identity = controller.state.value.identity
        repeat(4) { open() }; yield()
        assertSame(identity, controller.state.value.identity); assertEquals(1, fetches())
        assertEquals(4L, controller.state.value.total); assertNull(controller.state.value.artifact)
        release.complete(Unit); val original = artifact()
        repeat(4) { open() }; yield()
        assertSame(original, artifact()); assertEquals("data", original.file.readText()); assertEquals(1, fetches())
        assertEquals(4L, controller.state.value.received)
        controller.close(); emptyDisk(); assertFalse(original.file.exists())
    } }

    @Test fun pathAuthorizationAndConnectionEachInvalidateEqualLookingContent() = runBlocking { fixture {
        open(); var previous = artifact()
        suspend fun changed(action: () -> Unit) {
            action(); assertNull(controller.state.value.artifact)
            val next = artifact(); assertNotEquals(previous.file, next.file)
            withTimeout(3000) { while (previous.file.exists()) delay(5) }; previous = next
        }
        changed { open(path = "/other.txt") }
        changed { open(authorization = ArtifactAuthorization.Session("one"), path = "/other.txt") }
        changed { open(authorization = ArtifactAuthorization.Session("two"), path = "/other.txt") }
        changed { open(rpc = rpc(), authorization = ArtifactAuthorization.Session("two"), path = "/other.txt") }
        assertEquals(5, fetches())
        assertEquals(listOf(null, null, "one", "two", "two"), calls.filter { it.first.endsWith("fetch") }
            .map { it.second.opt("session_id") as? String })
    } }

    @Test fun lateCancelledTransferCannotPublishOverOrDeleteItsReplacement() = runBlocking { fixture {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeFetch = { params -> if (params.getString("path") == "/old.txt") {
            started.complete(Unit); withContext(NonCancellable) { release.await() }
        } }
        try {
            open(path = "/old.txt"); withTimeout(3000) { started.await() }
            open(path = "/new.txt"); val replacement = artifact()
            release.complete(Unit)
            withTimeout(3000) { while (root.listFiles().orEmpty().size != 1) delay(5) }
            assertSame(replacement, controller.state.value.artifact); assertTrue(replacement.file.exists())
            assertEquals("data", replacement.file.readText())
        } finally { release.complete(Unit) }
    } }

    @Test fun closingDuringTransferClearsStateAndRejectsLateBytesAndRetry() = runBlocking { fixture {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeFetch = { started.complete(Unit); withContext(NonCancellable) { release.await() } }
        try {
            open(); withTimeout(3000) { started.await() }; controller.close()
            assertEquals(ArtifactPreviewState(), controller.state.value)
            release.complete(Unit); emptyDisk(); open(); controller.retry(); yield()
            assertEquals(ArtifactPreviewState(), controller.state.value); assertEquals(1, fetches())
        } finally { release.complete(Unit) }
    } }

    @Test fun retryReplacesFailureAndClearAllowsAnotherSelection() = runBlocking { fixture {
        fail = true; open()
        withTimeout(3000) { controller.state.first { it.error != null } }
        assertNull(controller.state.value.artifact); assertEquals("Transfer interrupted", controller.state.value.error)
        fail = false; controller.retry(); val recovered = artifact()
        controller.clear(); emptyDisk(); assertFalse(recovered.file.exists())
        open(); assertNotEquals(recovered.file, artifact().file); assertEquals(3, fetches())
    } }

    @Test fun unavailableRetryRetainsSelectionWithoutCallingTheRetiredRpc() = runBlocking { fixture {
        fail = true; open()
        val failed = withTimeout(3000) { controller.state.first { it.error != null } }
        val count = calls.size
        controller.retryUnavailable(); yield()
        assertSame(failed.identity, controller.state.value.identity)
        assertTrue(controller.matches(controller.state.value, rpc, terminal, "/report.txt", false))
        assertEquals(ArtifactPreviewFailure.Kind.MAC_UNREACHABLE, controller.state.value.failure?.kind)
        assertEquals(count, calls.size)
        fail = false; controller.retry(); val loaded = artifact()
        controller.retryUnavailable()
        assertSame(loaded, controller.state.value.artifact); assertNull(controller.state.value.error)
        controller.close(); controller.retryUnavailable(); assertEquals(ArtifactPreviewState(), controller.state.value)
    } }

    @Test fun connectionLossCancelsPendingWorkAndLateChunksCannotReplaceItsFailure() = runBlocking { fixture {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        beforeFetch = { started.complete(Unit); withContext(NonCancellable) { release.await() } }
        try {
            open(); withTimeout(3000) { started.await() }; val selection = controller.state.value.identity
            controller.connectionLost()
            assertSame(selection, controller.state.value.identity)
            assertEquals(ArtifactPreviewFailure.Kind.MAC_UNREACHABLE, controller.state.value.failure?.kind)
            release.complete(Unit); emptyDisk(); open(); yield()
            assertNull(controller.state.value.artifact); assertEquals(1, fetches())
            assertEquals(ArtifactPreviewFailure.Kind.MAC_UNREACHABLE, controller.state.value.failure?.kind)
            beforeFetch = {}; open(rpc = rpc()); val replacement = artifact()
            controller.connectionLost(); assertSame(replacement, controller.state.value.artifact)
        } finally { release.complete(Unit) }
    } }

    @Test fun markdownPanelModeAndExactDisplayedPathRemainPartOfAdmission() = runBlocking { fixture {
        val panel = ArtifactAuthorization.Panel("workspace", "panel", "/report.txt")
        open(authorization = panel); val plain = artifact(); assertEquals("text/plain", plain.mime)
        open(authorization = panel, markdown = true); val markdown = artifact()
        assertEquals("text/markdown", markdown.mime); assertNotEquals(plain.file, markdown.file)
        open(authorization = panel, path = "/other.txt", markdown = true)
        withTimeout(3000) { controller.state.first { it.error != null } }
        assertNull(controller.state.value.artifact); assertEquals(2, fetches())
        assertTrue(calls.all { it.first.startsWith("mobile.panel.artifact.") })
    } }
}
