package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtifactFolderControllerTest {
    private val caps = ArtifactCapabilities(true, true, true, true)
    private val terminal = ArtifactAuthorization.Terminal("w", "t")
    private val destination = ArtifactDestination.Folder(ArtifactItem("/folder", ArtifactKind.DIRECTORY), terminal)
    private fun listing(name: String) = JSONObject("""{"path":"/folder","entries":[{"name":"$name","kind":"text"}],"is_truncated":false}""")

    @Test fun recreatedFolderKeepsListingAndVerifiedReconnectOnlyChangesFutureRequests() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = ArtifactFolderController(scope); var calls = 0
        val rpc = ArtifactRpc(caps) { _, _ -> calls++; listing("first") }
        try {
            controller.open(rpc, destination)
            val loaded = withTimeout(3000) { controller.state.first { it.listing != null } }
            repeat(3) { controller.open(rpc, destination) }
            controller.connectionLost()
            val next = ArtifactRpc(caps) { _, _ -> calls++; listing("second") }
            controller.replaceConnection(next); controller.open(next, destination)
            assertSame(loaded.identity, controller.state.value.identity)
            assertSame(loaded.listing, controller.state.value.listing); assertEquals(1, calls)
            controller.retry()
            val refreshed = withTimeout(3000) { controller.state.first { it.listing !== loaded.listing && !it.loading } }
            assertEquals("/folder/second", refreshed.listing!!.entries.single().path); assertEquals(2, calls)
            controller.close(); controller.retry(); controller.open(next, destination)
            assertEquals(ArtifactFolderState(), controller.state.value); assertEquals(2, calls)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun interruptedRefreshKeepsRowsAndLateReplyCannotOverwriteExplicitRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = ArtifactFolderController(scope)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val rpc = ArtifactRpc(caps) { _, _ ->
            if (++calls == 1) listing("original") else {
                entered.complete(Unit); withContext(NonCancellable) { release.await() }; listing("late")
            }
        }
        try {
            controller.open(rpc, destination)
            val original = withTimeout(3000) { controller.state.first { it.listing != null }.listing }
            controller.retry(); entered.await(); controller.connectionLost()
            val next = ArtifactRpc(caps) { _, _ -> listing("replacement") }
            controller.replaceConnection(next); controller.open(next, destination)
            assertSame(original, controller.state.value.listing)
            assertEquals(ArtifactPreviewFailure.Kind.MAC_UNREACHABLE, controller.state.value.failure?.kind)
            controller.retry(); release.complete(Unit); yield()
            val loaded = withTimeout(3000) { controller.state.first { !it.loading && it.failure == null } }
            assertEquals("/folder/replacement", loaded.listing!!.entries.single().path)
            assertTrue(controller.matches(loaded, next, destination))
        } finally { release.complete(Unit); controller.close(); scope.cancel() }
    }
}
