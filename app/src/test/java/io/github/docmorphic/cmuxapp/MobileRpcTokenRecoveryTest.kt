package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobileRpcTokenRecoveryTest {
    private suspend fun PoolTestTransport.reject(request: JSONObject, code: String = "unauthorized") {
        incoming.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", "Fixture rejection")).toString().toByteArray()))
    }
    private suspend fun PoolTestTransport.next() = withTimeout(2000) { sent.receive() }

    @Test fun refreshPreservesRequestIdentityParametersAndBorrowedTicketScope() = runBlocking<Unit> {
        val wire = PoolTestTransport(); var token = "old"; var refreshed = 0
        MobileRpcClient(wire, { token }).use { owner ->
            owner.configureAccountTokenRefresh { refreshed++; token = "fresh"; token }; owner.connect()
            owner.lease {}.withAttachTicket(MobileAttachTicketContext("work", "term", "attach", null)) {}.use { client ->
                val params = JSONObject().put("workspace_id", "work").put("action", "rename").put("title", "Original")
                val pending = async { client.request("workspace.action", params) }
                val first = wire.next(); params.put("title", "Changed externally")
                assertEquals("old", first.getJSONObject("auth").getString("stack_access_token")); wire.reject(first)
                val retry = wire.next()
                assertEquals(first.getString("id"), retry.getString("id"))
                assertEquals(first.getJSONObject("params").toString(), retry.getJSONObject("params").toString())
                assertEquals("fresh", retry.getJSONObject("auth").getString("stack_access_token"))
                assertEquals("attach", retry.getJSONObject("auth").getString("attach_token"))
                wire.answer(retry); pending.await(); assertEquals(1, refreshed)
            }
        }
    }

    @Test fun aSecondHostRejectionIsFinalAndOtherCodesNeverRefresh() = runBlocking<Unit> {
        for (code in listOf("unauthorized", "account_mismatch", "forbidden", "invalid_attach_token", "UNAUTHORIZED")) {
            val wire = PoolTestTransport(); var refreshed = 0
            MobileRpcClient(wire, { "old" }).use { client ->
                client.configureAccountTokenRefresh { refreshed++; "fresh" }; client.connect()
                val pending = async { runCatching { client.request("workspace.list") } }
                wire.reject(wire.next(), code)
                if (code == "unauthorized") wire.reject(wire.next(), code)
                assertEquals(code, (withTimeout(2000) { pending.await() }.exceptionOrNull() as MobileRpcException).code)
                assertEquals(if (code == "unauthorized") 1 else 0, refreshed)
                assertTrue(wire.sent.tryReceive().isFailure)
            }
        }
    }

    @Test fun nativeTransportCannotInstallBearerRecoveryOrReadTokens() = runBlocking<Unit> {
        val wire = PoolTestTransport(); var lookups = 0
        val native = object : MobileRpcTransport by wire {
            override val rpcAuthorization = MobileRpcAuthorization.TRANSPORT_ADMISSION
        }
        MobileRpcClient(native, { lookups++; "unused" }).use { client ->
            assertTrue(runCatching { client.configureAccountTokenRefresh { "unused" } }.isFailure)
            client.connect()
            val pending = async { runCatching { client.request("workspace.list") } }
            val sent = wire.next(); assertFalse(sent.has("auth")); wire.reject(sent)
            assertTrue(pending.await().isFailure); assertEquals(0, lookups)
            assertTrue(wire.sent.tryReceive().isFailure)
        }
    }

    @Test fun simultaneousLeasesReuseTheAlreadyRefreshedToken() = runBlocking<Unit> {
        val wire = PoolTestTransport(); var token = "old"; var refreshed = 0
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        MobileRpcClient(wire, { token }).use { owner ->
            owner.configureAccountTokenRefresh { refreshed++; entered.complete(Unit); release.await(); token = "fresh"; token }
            owner.connect()
            owner.lease {}.use { a -> owner.lease {}.use { b ->
                val one = async { a.request("workspace.list") }; val two = async { b.request("mobile.task.models.list") }
                val first = wire.next(); val second = wire.next(); wire.reject(first); wire.reject(second)
                withTimeout(2000) { entered.await() }; release.complete(Unit)
                val retryA = wire.next(); val retryB = wire.next()
                for (retry in listOf(retryA, retryB)) {
                    assertEquals("fresh", retry.getJSONObject("auth").getString("stack_access_token")); wire.answer(retry)
                }
                one.await(); two.await(); assertEquals(1, refreshed)
            } }
        }
    }

    @Test fun accountRetirementDuringRefreshPreventsRetryWithoutClosingSiblingWire() = runBlocking<Unit> {
        val wire = PoolTestTransport(); var admitted = true
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        MobileRpcClient(wire, { "old" }).use { owner ->
            owner.configureAccountTokenRefresh { entered.complete(Unit); release.await(); "fresh" }; owner.connect()
            owner.lease {}.withAttachTicket(null) { check(admitted) }.use { client ->
                val pending = async { runCatching { client.workspaces() } }; wire.reject(wire.next())
                withTimeout(2000) { entered.await() }; admitted = false; release.complete(Unit)
                assertTrue(withTimeout(2000) { pending.await() }.isFailure)
                assertTrue(wire.sent.tryReceive().isFailure); assertFalse(owner.isClosed)
            }
        }
    }

    @Test fun ticketExpiryDuringRefreshCannotSendAnAccountOnlyMacMutation() = runBlocking<Unit> {
        val wire = PoolTestTransport()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        MobileRpcClient(wire, { "old" }).use { owner ->
            owner.configureAccountTokenRefresh { entered.complete(Unit); release.await(); "fresh" }; owner.connect()
            val expiry = System.currentTimeMillis() + 350
            owner.withAttachTicket(MobileAttachTicketContext("", null, "attach", expiry)) {}.use { client ->
                val pending = async { runCatching { client.createGroup("") } }; wire.reject(wire.next())
                withTimeout(2000) { entered.await() }
                delay((expiry - System.currentTimeMillis()).coerceAtLeast(0) + 5); release.complete(Unit)
                assertTrue(pending.await().isFailure); assertTrue(wire.sent.tryReceive().isFailure)
            }
        }
    }

    @Test fun tokenLookupAndForcedRefreshShareTheRequestDeadline() = runBlocking<Unit> {
        val first = PoolTestTransport()
        MobileRpcClient(first, { awaitCancellation() }).use { client ->
            client.connect()
            assertTrue(runCatching { client.request("workspace.list", timeoutMillis = 60) }.exceptionOrNull() is TimeoutCancellationException)
            assertTrue(first.sent.tryReceive().isFailure)
        }
        val wire = PoolTestTransport(); val started = System.nanoTime()
        MobileRpcClient(wire, { "old" }).use { client ->
            client.configureAccountTokenRefresh { delay(250); "fresh" }; client.connect()
            val pending = async { runCatching { client.request("workspace.list", timeoutMillis = 350) } }
            val firstFrame = wire.next(); delay(200); wire.reject(firstFrame)
            assertTrue(pending.await().exceptionOrNull() is TimeoutCancellationException)
            assertTrue(wire.sent.tryReceive().isFailure)
            assertTrue("Deadline was restarted", (System.nanoTime() - started) / 1_000_000 < 650)
        }
    }

    @Test fun providerErrorsAndRefreshFailuresDoNotSendAnotherRequest() = runBlocking<Unit> {
        val first = PoolTestTransport(); var refreshed = 0
        MobileRpcClient(first, { throw MobileRpcException("unauthorized", "Local provider failure") }).use { client ->
            client.configureAccountTokenRefresh { refreshed++; "fresh" }; client.connect()
            assertTrue(runCatching { client.workspaces() }.isFailure)
            assertEquals(0, refreshed); assertTrue(first.sent.tryReceive().isFailure)
        }
        for (failure in listOf(java.io.IOException("Offline"), CancellationException("Cancelled"))) {
            val wire = PoolTestTransport()
            MobileRpcClient(wire, { "old" }).use { client ->
                client.configureAccountTokenRefresh { throw failure }; client.connect()
                val pending = async { runCatching { client.workspaces() } }; wire.reject(wire.next())
                val actual = pending.await().exceptionOrNull()
                assertEquals(failure.javaClass, actual?.javaClass); assertEquals(failure.message, actual?.message)
                assertTrue(wire.sent.tryReceive().isFailure)
            }
        }
    }
}
