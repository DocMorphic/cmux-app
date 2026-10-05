package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManualAttachTicketRequestTest {
    private val owner = NativeTeamScope("login", "user", "team", 1)
    private val route = PairingCode.Route("100.99.1.2", 58465)
    private val host = JSONObject().put("mac_device_id", "mac").put("mac_display_name", "Chosen Mac")
    private fun ticket() = JSONObject("""{"version":1,"workspaceID":"","macDeviceID":"mac","macUserID":"user",
        "macUserEmail":"user@example.test","auth_token":"synthetic-only","expiresAt":"2099-01-01T00:00:00Z",
        "macPairingCompatibilityVersion":3,"macAppVersion":"1.2.3","macAppBuild":"4",
        "routes":[{"id":"other","kind":"tailscale","endpoint":{"type":"host_port","host":"100.99.1.3","port":12345}}]}""")

    private suspend fun exchange(result: JSONObject? = null, error: JSONObject? = null): Result<MobileAttachTicket?> = coroutineScope {
        val wire = PoolTestTransport()
        MobileRpcClient(wire, { "synthetic-stack" }, MobileAttachTicketContext("", null, "must-not-send", null)).use { client ->
            client.connect()
            val pending = async { runCatching { ManualAttachTicketRequest.request(client, route, host, owner, "USER@example.test") } }
            val request = withTimeout(2000) { wire.sent.receive() }
            assertEquals("mobile.attach_ticket.create", request.getString("method"))
            val params = request.getJSONObject("params")
            assertEquals(3, params.length()); assertEquals(3600, params.getInt("ttl_seconds"))
            assertEquals("mac", params.getString("scope")); assertEquals("ticket_only", params.getString("target"))
            assertEquals("synthetic-stack", request.getJSONObject("auth").getString("stack_access_token"))
            assertFalse(request.getJSONObject("auth").has("attach_token"))
            val reply = JSONObject().put("id", request.getString("id")).put("ok", error == null)
            if (error == null) reply.put("result", result) else reply.put("error", error)
            wire.incoming.send(MobileFrameCodec.encode(reply.toString().toByteArray()))
            withTimeout(2000) { pending.await() }
        }
    }

    @Test fun requestsMacTicketAndReplacesAdvertisedRoutesWithExactAdmittedPeer() = runBlocking<Unit> {
        val result = checkNotNull(exchange(JSONObject().put("ticket", ticket())).getOrThrow())
        assertEquals(listOf(MobileAttachRoute("tailscale", "tailscale", 0, MobileAttachEndpoint.HostPort(route.host, route.port))), result.routes)
        assertEquals("Chosen Mac", result.displayName); assertEquals("mac", result.deviceId)
        assertEquals(3L, result.compatibilityVersion); assertEquals("1.2.3", result.appVersion); assertEquals("4", result.appBuild)
        assertEquals("user", result.userId); assertEquals("user@example.test", result.userEmail)
        assertEquals("", result.workspaceId); assertNotNull(result.expiresAtMillis)
        assertFalse(result.toString().contains("synthetic-only"))
    }

    @Test fun preservesSelectionNameAndExpiryWhenRestrictingRoute() = runBlocking<Unit> {
        val original = ticket().put("workspaceID", "work").put("terminalID", "term").put("macDisplayName", "Returned Name")
        val result = checkNotNull(exchange(JSONObject().put("ticket", original)).getOrThrow())
        assertEquals("work", result.workspaceId); assertEquals("term", result.terminalId); assertEquals("Returned Name", result.displayName)
        assertEquals(MobileAttachTicketCodec.decodeJson(original.toString()).getOrThrow().expiresAtMillis, result.expiresAtMillis)
    }

    @Test fun onlySpecifiedRpcFailuresAllowFallback() {
        for (code in listOf("METHOD_NOT_FOUND", " not_found ", "unknown_method", "unsupported_method"))
            assertTrue(ManualAttachTicketRequest.allowsFallback(MobileRpcException(code, "unavailable")))
        for (message in listOf("Unknown method", "method not found", "unsupported method", "Ticket unavailable", "ticket not available"))
            assertTrue(ManualAttachTicketRequest.allowsFallback(MobileRpcException(null, message)))
        for (failure in listOf(MobileRpcException("unauthorized", "Sign in"), MobileRpcException("timeout", "timed out"),
            IllegalArgumentException("unknown method"), java.io.IOException("ticket unavailable"), CancellationException("method not found")))
            assertFalse(ManualAttachTicketRequest.allowsFallback(failure))
    }

    @Test fun unsupportedRpcReturnsAccountFallbackButAuthenticationErrorPropagates() = runBlocking<Unit> {
        assertNull(exchange(error = JSONObject().put("code", "method_not_found").put("message", "fixture")).getOrThrow())
        val failure = exchange(error = JSONObject().put("code", "unauthorized").put("message", "fixture denial")).exceptionOrNull()
        assertTrue(failure is MobileRpcException); assertEquals("unauthorized", (failure as MobileRpcException).code)
    }

    @Test fun malformedSuccessOrWrongIdentityCannotBecomeFallback() = runBlocking<Unit> {
        val invalid = listOf(JSONObject(), JSONObject().put("ticket", "ticket unavailable"),
            JSONObject().put("ticket", ticket().put("version", 99)),
            JSONObject().put("ticket", ticket().put("macDeviceID", "other")),
            JSONObject().put("ticket", ticket().put("macUserID", "other")),
            JSONObject().put("ticket", ticket().put("macUserEmail", "other@example.test")))
        for (result in invalid) {
            val failure = exchange(result).exceptionOrNull()
            assertTrue(failure is InvalidManualAttachTicket)
            assertFalse(failure.toString().contains("synthetic-only"))
        }
    }

    @Test fun cancellationPropagatesAndDoesNotSendAFallbackRequest() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        MobileRpcClient(wire, { "synthetic-stack" }).use { client ->
            client.connect()
            val pending = async { ManualAttachTicketRequest.request(client, route, host, owner, null) }
            withTimeout(2000) { wire.sent.receive() }
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled); assertTrue(wire.sent.tryReceive().isFailure)
        }
    }

    @Test fun inadmissibleAddressFailsBeforeSendingAnything() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        MobileRpcClient(wire, { "synthetic-stack" }).use { client ->
            client.connect()
            for (address in listOf("127.0.0.1", "192.168.1.2", "example.com", "100.100.100.100"))
                assertTrue(runCatching { ManualAttachTicketRequest.request(client, route.copy(host = address), host, owner, null) }.isFailure)
            assertTrue(wire.sent.tryReceive().isFailure)
        }
    }
}
