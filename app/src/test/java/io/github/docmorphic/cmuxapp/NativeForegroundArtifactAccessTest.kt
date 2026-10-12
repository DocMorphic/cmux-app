package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import org.json.JSONObject

class NativeForegroundArtifactAccessTest {
    private val target = NativePanelTarget("workspace", "panel", "/fixture/file", "markdown", "Original")
    private suspend fun fixture(block: suspend (PoolTestTransport, MobileRpcClient, CoroutineScope) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val wire = PoolTestTransport()
        MobileRpcClient(wire, { "fixture" }).use { client ->
            client.connect()
            try { block(wire, client, scope) } finally { scope.cancel() }
        }
    }

    @Test fun panelRequestsUseExactDisplayedPathAndRejectWrongWorkspaceOrFileBeforeWrite() = runBlocking<Unit> {
        fixture { wire, client, scope ->
            val access = checkNotNull(NativeForegroundArtifactAccess(scope, client, { true }, { true }).panel(target) { true })
            val request = async { access.rpc.fetch(target.authorization, target.path, 0, 10) }
            val sent = withTimeout(2000) { wire.sent.receive() }
            assertEquals("mobile.panel.artifact.fetch", sent.getString("method"))
            assertEquals(target.workspace, sent.getJSONObject("params").getString("workspace_id"))
            wire.answer(sent); withTimeout(2000) { request.await() }
            for (other in listOf(target.copy(workspace = "other"), target.copy(path = "/fixture/other"))) {
                assertTrue(runCatching { access.rpc.fetch(other.authorization, other.path, 0, 10) }.isFailure)
                assertTrue(wire.sent.tryReceive().isFailure)
            }
        }
    }

    @Test fun descriptorChangeDuringFetchCannotPublishItsOldReply() = runBlocking<Unit> {
        fixture { wire, client, scope ->
            var descriptor = target
            val access = checkNotNull(NativeForegroundArtifactAccess(scope, client, { true }, { true }).panel(target) { descriptor == target })
            val pending = async { runCatching { access.rpc.fetch(target.authorization, target.path, 0, 10) } }
            val sent = withTimeout(2000) { wire.sent.receive() }
            descriptor = target.copy(title = "Changed", kind = "filePreview")
            wire.answer(sent)
            assertTrue(withTimeout(2000) { pending.await() }.isFailure)
            assertFalse(access.current()); assertFalse(access.cachedCurrent())
        }
    }

    @Test fun rejectedArtifactRpcRetiresItsClientAndInvalidatesCachedBytes() = runBlocking<Unit> {
        fixture { wire, client, scope ->
            val access = checkNotNull(NativeForegroundArtifactAccess(scope, client, { true }, { true }).panel(target) { true })
            val pending = async { runCatching { access.rpc.fetch(target.authorization, target.path, 0, 10) } }
            val sent = withTimeout(2000) { wire.sent.receive() }
            wire.incoming.send(MobileFrameCodec.encode(JSONObject().put("id", sent.getString("id")).put("ok", false)
                .put("error", JSONObject().put("code", "unauthorized").put("message", "Fixture rejection")).toString().toByteArray()))
            assertTrue(withTimeout(2000) { pending.await() }.isFailure)
            assertTrue(client.isClosed); assertFalse(access.current()); assertFalse(access.cachedCurrent())
        }
    }

    @Test fun networkLossRetainsCompletedCacheButNativeAuthorizationRejectionInvalidatesIt() = runBlocking<Unit> {
        for (reason in listOf(EOFException(), MobileRpcException("unauthorized", "Rejected", true))) fixture { wire, client, scope ->
            val access = checkNotNull(NativeForegroundArtifactAccess(scope, client, { true }, { true }).panel(target) { true })
            client.retireForOwnerFailure(reason)
            assertFalse(access.current())
            assertEquals(reason is EOFException, access.cachedCurrent())
            assertTrue(runCatching { access.rpc.fetch(target.authorization, target.path, 0, 10) }.isFailure)
            assertTrue(wire.sent.tryReceive().isFailure)
        }
    }

    @Test fun terminalAccessRejectsAnotherSurfaceAndLossOfLiveOrSavedAdmissionWithoutNewDial() = runBlocking<Unit> {
        fixture { wire, client, scope ->
            var live = true; var saved = true
            val terminal = ArtifactAuthorization.Terminal("workspace", "terminal")
            val access = checkNotNull(NativeForegroundArtifactAccess(scope, client, { live }, { saved })
                .terminal(terminal, ArtifactCapabilities(true, false, false, false)) { true })
            assertTrue(runCatching { access.rpc.fetch(terminal.copy(surfaceId = "other"), "/fixture/file", 0, 10) }.isFailure)
            live = false
            assertFalse(access.current()); assertTrue(access.cachedCurrent())
            assertTrue(runCatching { access.rpc.fetch(terminal, "/fixture/file", 0, 10) }.isFailure)
            saved = false; assertFalse(access.cachedCurrent())
            assertEquals(1, wire.connects.get()); assertTrue(wire.sent.tryReceive().isFailure)
        }
    }
}
