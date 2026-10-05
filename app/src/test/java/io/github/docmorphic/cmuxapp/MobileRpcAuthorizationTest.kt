package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileRpcAuthorizationTest {
    private fun admitted(wire: PoolTestTransport, permits: () -> Boolean = { true }) = object : MobileRpcTransport by wire {
        override val rpcAuthorization = MobileRpcAuthorization.TRANSPORT_ADMISSION
        override suspend fun write(bytes: ByteArray) { check(permits()) { "Admission retired" }; wire.write(bytes) }
        override suspend fun writeWithGeneration(bytes: ByteArray): Long { write(bytes); return 0 }
    }
    private suspend fun exchange(client: MobileRpcClient, wire: PoolTestTransport, method: String): JSONObject = coroutineScope {
        val pending = async { client.request(method, JSONObject().put("workspace_id", "work").put("surface_id", "term")) }
        val request = withTimeout(2000) { wire.sent.receive() }
        wire.answer(request); withTimeout(2000) { pending.await() }; request
    }

    @Test fun nativeFramesOmitBothCredentialsAndNeverLookUpAccountToken() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        MobileRpcClient(admitted(wire), { error("Native RPC must not request a Stack token") },
            MobileAttachTicketContext("work", "term", "synthetic-secret", null)).use { client ->
            client.connect()
            for (method in listOf("mobile.host.status", "mobile.workspace.list", "workspace.create", "mobile.terminal.input", "unknown.future.method"))
                assertFalse(exchange(client, wire, method).has("auth"))
        }
    }

    @Test fun nativeLeasesAndTicketViewsRetainTransportAdmission() = runBlocking<Unit> {
        val wire = PoolTestTransport(); var releases = 0
        MobileRpcClient(admitted(wire), { error("Must not look up token") }).use { owner ->
            owner.connect()
            owner.lease { releases++ }.withAttachTicket(MobileAttachTicketContext("", null, "synthetic-secret", null)) {}.use { scoped ->
                assertFalse(exchange(scoped, wire, "workspace.list").has("auth"))
                val pending = async { scoped.requestWithAttachTicketPolicy("workspace.group.create", JSONObject(), ticketPolicy = MobileAttachTicketPolicy.OMIT) }
                val request = withTimeout(2000) { wire.sent.receive() }
                assertFalse(request.has("auth")); wire.answer(request); withTimeout(2000) { pending.await() }
            }
            assertEquals(1, releases); assertFalse(owner.isClosed)
        }
    }

    @Test fun revokedNativeTransportStillBlocksTheFrameWithoutAccountTokenLookup() = runBlocking<Unit> {
        val wire = PoolTestTransport(); var allowed = true
        MobileRpcClient(admitted(wire) { allowed }, { error("Must not look up token") }).use { client ->
            client.connect(); allowed = false
            assertTrue(runCatching { client.workspaces() }.isFailure)
            assertTrue(wire.sent.tryReceive().isFailure)
        }
    }

    @Test fun defaultBearerTransportStillRequiresAccountAuthAndIncludesCoveredTicket() = runBlocking<Unit> {
        val wire = PoolTestTransport(); var token: String? = "synthetic-stack"
        MobileRpcClient(wire, { token }, MobileAttachTicketContext("work", "term", "synthetic-ticket", null)).use { client ->
            client.connect()
            val auth = exchange(client, wire, "workspace.list").getJSONObject("auth")
            assertEquals("synthetic-stack", auth.getString("stack_access_token"))
            assertEquals("synthetic-ticket", auth.getString("attach_token"))
            token = null
            assertTrue(runCatching { client.workspaces() }.isFailure)
            assertTrue(wire.sent.tryReceive().isFailure)
        }
    }

    @Test fun hostCapabilitiesCannotConvertBearerTransportIntoNativeAdmission() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        MobileRpcClient(wire, { null }).use { client ->
            client.connect()
            val pending = async { client.hostStatus() }
            val request = withTimeout(2000) { wire.sent.receive() }
            wire.incoming.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", true)
                .put("result", JSONObject().put("capabilities", org.json.JSONArray(listOf("iroh", "transport_admission"))))
                .toString().toByteArray()))
            withTimeout(2000) { pending.await() }
            assertTrue(runCatching { client.workspaces() }.isFailure)
            assertTrue(wire.sent.tryReceive().isFailure)
        }
    }

    @Test fun productionTransportTypesDeclareOnlyNativeAdmissionForIrx() {
        IrxMobileRpcTransport({ error("Not dialing") }, { true }).use {
            assertEquals(MobileRpcAuthorization.TRANSPORT_ADMISSION, it.rpcAuthorization)
        }
        TailscaleCandidateTransport(emptyList(), { true }) { _, _ -> error("Not dialing") }.use {
            assertEquals(MobileRpcAuthorization.ACCOUNT_BEARER, it.rpcAuthorization)
        }
    }
}
