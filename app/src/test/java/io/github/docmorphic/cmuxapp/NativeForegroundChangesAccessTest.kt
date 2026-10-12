package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException

class NativeForegroundChangesAccessTest {
    private suspend fun fixture(block: suspend (PoolTestTransport, MobileRpcClient, CoroutineScope) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val wire = PoolTestTransport()
        MobileRpcClient(wire, { "fixture" }).use { client ->
            client.connect()
            try { withTimeout(5_000) { block(wire, client, scope) } } finally { scope.cancel() }
        }
    }
    @Test fun everyChangesReadUsesTheCapturedWorkspaceAndOneAdmittedClient() = runBlocking<Unit> {
        fixture { wire, client, scope ->
            val access = checkNotNull(NativeForegroundChangesAccess(scope, client, { true }, { true })
                .workspace("workspace", "Fixture", { true }))
            for (request in listOf(WorkspaceChangesRead.Files, WorkspaceChangesRead.Diff("src/file", 200),
                WorkspaceChangesRead.Stat("src/file", ChangesRevision.CURRENT), WorkspaceChangesRead.Fetch("src/file", ChangesRevision.CURRENT, 0, 10))) {
                val pending = async { access.read(request) }
                val sent = wire.sent.receive()
                assertEquals("workspace", sent.getJSONObject("params").getString("workspace_id"))
                assertEquals(when (request) {
                    WorkspaceChangesRead.Files -> "mobile.workspace.changes.files"
                    is WorkspaceChangesRead.Diff -> "mobile.workspace.changes.file_diff"
                    is WorkspaceChangesRead.Stat -> "mobile.workspace.changes.file_stat"
                    is WorkspaceChangesRead.Fetch -> "mobile.workspace.changes.file_fetch"
                }, sent.getString("method"))
                wire.answer(sent); pending.await()
            }
            assertEquals(1, wire.connects.get())
        }
    }
    @Test fun changedWorkspaceDescriptorOrLiveAuthorityRejectsTheOldResult() = runBlocking<Unit> {
        for (change in listOf("descriptor", "live")) fixture { wire, client, scope ->
            var descriptor = true; var live = true
            val access = checkNotNull(NativeForegroundChangesAccess(scope, client, { live }, { true })
                .workspace("workspace", "Fixture", { descriptor }))
            val pending = async { runCatching { access.read(WorkspaceChangesRead.Files) } }
            val sent = wire.sent.receive()
            if (change == "descriptor") descriptor = false else live = false
            wire.answer(sent); assertTrue(pending.await().isFailure)
            assertTrue(runCatching { access.read(WorkspaceChangesRead.Files) }.isFailure)
            assertTrue(wire.sent.tryReceive().isFailure)
        }
    }
    @Test fun ordinaryDisconnectRetainsPresentationButAuthorizationRejectionInvalidatesIt() = runBlocking<Unit> {
        for (failure in listOf(EOFException(), MobileRpcException("unauthorized", "Fixture", true))) fixture { _, client, scope ->
            val access = checkNotNull(NativeForegroundChangesAccess(scope, client, { true }, { true })
                .workspace("workspace", "Fixture", { true }))
            client.retireForOwnerFailure(failure)
            assertEquals(failure is EOFException, access.current())
            assertTrue(runCatching { access.read(WorkspaceChangesRead.Files) }.isFailure)
        }
    }
    @Test fun rejectedChangesRpcRetiresTheClientAndItsCachedPresentation() = runBlocking<Unit> {
        fixture { wire, client, scope ->
            val access = checkNotNull(NativeForegroundChangesAccess(scope, client, { true }, { true })
                .workspace("workspace", "Fixture", { true }))
            val pending = async { runCatching { access.read(WorkspaceChangesRead.Files) } }
            val sent = wire.sent.receive()
            wire.incoming.send(MobileFrameCodec.encode(JSONObject().put("id", sent.getString("id")).put("ok", false)
                .put("error", JSONObject().put("code", "unauthorized").put("message", "Fixture rejection")).toString().toByteArray()))
            assertTrue(pending.await().isFailure); assertTrue(client.isClosed); assertFalse(access.current())
        }
    }
    @Test fun missingAdmissionOrDescriptorCannotCreateAnAccess() = runBlocking<Unit> {
        fixture { wire, client, scope ->
            assertNull(NativeForegroundChangesAccess(scope, client, { false }, { true }).workspace("workspace", "Fixture", { true }))
            assertNull(NativeForegroundChangesAccess(scope, client, { true }, { false }).workspace("workspace", "Fixture", { true }))
            assertNull(NativeForegroundChangesAccess(scope, client, { true }, { true }).workspace("workspace", "Fixture", { false }))
            assertTrue(wire.sent.tryReceive().isFailure)
        }
    }
}
