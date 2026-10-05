package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileRpcAttachTicketTest {
    private fun ticket(expiry: Long? = null) = MobileAttachTicketContext("work", "term", "fixture-attach", expiry)

    private suspend fun exchange(client: MobileRpcClient, wire: PoolTestTransport, method: String,
        params: JSONObject = JSONObject()): JSONObject = coroutineScope {
        val reply = async { client.request(method, params) }
        val sent = withTimeout(2_000) { wire.sent.receive() }
        wire.answer(sent)
        assertEquals(method, withTimeout(2_000) { reply.await() }.getString("method"))
        sent
    }

    @Test fun callerTicketsStayIsolatedOnSharedWireAndClosingOneReleasesOnlyItsHandle() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        var releases = 0
        MobileRpcClient(wire, { "fixture-stack" }).use { owner ->
            owner.connect()
            val first = owner.lease { releases++ }.withAttachTicket(ticket()) {}
            val second = owner.lease { releases++ }.withAttachTicket(MobileAttachTicketContext("", null, "second-ticket", null)) {}
            try {
                assertEquals("fixture-attach", exchange(first, wire, "workspace.list").getJSONObject("auth").getString("attach_token"))
                assertEquals("second-ticket", exchange(second, wire, "workspace.list").getJSONObject("auth").getString("attach_token"))
                assertFalse(exchange(owner, wire, "workspace.list").getJSONObject("auth").has("attach_token"))
                first.close(); first.close()
                assertEquals(1, releases)
                assertFalse(owner.isClosed); assertFalse(second.isClosed)
                assertEquals("second-ticket", exchange(second, wire, "workspace.list").getJSONObject("auth").getString("attach_token"))
            } finally { first.close(); second.close() }
            assertEquals(2, releases)
        }
    }

    @Test fun ticketReplacementDuringAccountTokenLookupPreventsAnyFrame() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        MobileRpcClient(wire, { entered.complete(Unit); resume.await(); "fixture-stack" }).use { owner ->
            owner.connect()
            val scoped = owner.lease {}.withAttachTicket(ticket()) { check(current.get()) { "Pairing changed" } }
            scoped.use {
                val result = async { runCatching { scoped.request("workspace.list") } }
                withTimeout(2000) { entered.await() }
                current.set(false); resume.complete(Unit)
                assertTrue(withTimeout(2000) { result.await() }.isFailure)
                assertTrue(wire.sent.tryReceive().isFailure)
                assertFalse(owner.isClosed)
            }
        }
    }

    @Test fun requestLocalOmissionAlsoWorksOnTicketScopedViews() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        MobileRpcClient(wire, { "fixture-stack" }).use { owner ->
            owner.connect()
            owner.lease {}.withAttachTicket(ticket()) {}.use { scoped ->
                val result = async { scoped.requestWithAttachTicketPolicy("workspace.group.create", JSONObject(),
                    ticketPolicy = MobileAttachTicketPolicy.OMIT) }
                val frame = withTimeout(2000) { wire.sent.receive() }
                assertFalse(frame.getJSONObject("auth").has("attach_token")); wire.answer(frame)
                withTimeout(2000) { result.await() }
                assertEquals("fixture-attach", exchange(scoped, wire, "workspace.list").getJSONObject("auth").getString("attach_token"))
            }
        }
    }

    @Test fun framedRequestsAttachOnlyCoveredContextAndKeepAccountAuthForFallback() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        MobileRpcClient(wire, { "fixture-stack" }, ticket()).use { client ->
            client.connect()
            val matched = exchange(client, wire, "terminal.input", JSONObject().put("surface_id", "term"))
            assertEquals("fixture-attach", matched.getJSONObject("auth").getString("attach_token"))
            for (request in listOf(
                exchange(client, wire, "terminal.input", JSONObject().put("surface_id", "other")),
                exchange(client, wire, "feed.permission.reply", JSONObject().put("request_id", "prompt")),
                exchange(client, wire, "mobile.host.status"))) {
                assertFalse(request.getJSONObject("auth").has("attach_token"))
                assertEquals("fixture-stack", request.getJSONObject("auth").getString("stack_access_token"))
            }
            assertEquals("fixture-stack", matched.getJSONObject("auth").getString("stack_access_token"))
        }
    }

    @Test fun leasesShareTicketCoverageAndClosingOneDoesNotRetireTheOther() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        var released = 0
        MobileRpcClient(wire, { "fixture-stack" }, ticket()).use { owner ->
            owner.connect()
            val first = owner.lease { released++ }
            val second = owner.lease { released++ }
            try {
                assertEquals("fixture-attach", exchange(first, wire, "workspace.list").getJSONObject("auth").getString("attach_token"))
                first.close(); assertEquals(1, released)
                assertFalse(exchange(second, wire, "notification.feed.list").getJSONObject("auth").has("attach_token"))
                assertEquals(0, wire.closes.get())
            } finally { first.close(); second.close() }
            assertEquals(2, released)
        }
    }

    @Test fun expiredContextIsOmittedButMissingAccountTokenCannotSend() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        var token: String? = "fixture-stack"
        MobileRpcClient(wire, { token }, ticket(expiry = 0)).use { client ->
            client.connect()
            val sent = exchange(client, wire, "workspace.list")
            assertFalse(sent.getJSONObject("auth").has("attach_token"))
            token = null
            assertTrue(runCatching { client.workspaces() }.isFailure)
            assertTrue(wire.sent.tryReceive().isFailure)
        }
        val validWire = PoolTestTransport()
        MobileRpcClient(validWire, { null }, ticket()).use { client ->
            client.connect()
            assertTrue(runCatching { client.workspaces() }.isFailure)
            assertTrue(validWire.sent.tryReceive().isFailure)
        }
    }

    @Test fun explicitAccountOmissionIsRequestLocalAcrossBorrowedConnection() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        MobileRpcClient(wire, { "fixture-stack" }, ticket()).use { owner ->
            owner.connect()
            owner.lease {}.use { lease ->
                val pending = async { lease.requestWithAttachTicketPolicy("workspace.group.create", JSONObject(),
                    ticketPolicy = MobileAttachTicketPolicy.OMIT) }
                val sent = withTimeout(2_000) { wire.sent.receive() }
                assertFalse(sent.getJSONObject("auth").has("attach_token"))
                assertEquals("fixture-stack", sent.getJSONObject("auth").getString("stack_access_token"))
                wire.answer(sent); withTimeout(2_000) { pending.await() }
                assertEquals("fixture-attach", exchange(lease, wire, "workspace.group.create")
                    .getJSONObject("auth").getString("attach_token"))
            }
        }
    }
}
