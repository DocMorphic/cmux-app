package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.util.concurrent.atomic.AtomicInteger

class MobileControlRepairTest {
    private class Transport(override val supportsControlRepair: Boolean = true) : MobileRpcTransport {
        val input = Channel<ByteArray>(16)
        val sent = Channel<Pair<Long, JSONObject>>(32)
        override val disconnections = MutableSharedFlow<Throwable>(replay = 1)
        val repairs = AtomicInteger()
        val closes = AtomicInteger()
        var generation = 0L
        var repairResult: MobileControlRepair = MobileControlRepair.Repaired(1)
        var stallResend = false
        override suspend fun connect() { }
        override suspend fun read() = input.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) { writeWithGeneration(bytes) }
        override suspend fun writeWithGeneration(bytes: ByteArray): Long {
            val json = JSONObject(MobileFrameDecoder().feed(bytes).single().toString(Charsets.UTF_8))
            sent.send(generation to json)
            if (stallResend && generation > 0) awaitCancellation()
            return generation
        }
        override suspend fun repairControl(silentSinceNanos: Long): MobileControlRepair {
            repairs.incrementAndGet()
            if (repairResult is MobileControlRepair.Repaired) generation = (repairResult as MobileControlRepair.Repaired).generation
            return repairResult
        }
        suspend fun answer(request: JSONObject, error: Boolean = false) {
            val response = JSONObject().put("id", request.getString("id")).put("ok", !error)
            if (error) response.put("error", JSONObject().put("code", "unsupported").put("message", "fixture"))
            else response.put("result", JSONObject().put("answered", true))
            input.send(MobileFrameCodec.encode(response.toString().toByteArray()))
        }
        override fun close() { closes.incrementAndGet(); input.close() }
    }

    @Test fun replacementResendsOnlyStrandedReadsAndVerifiesEvenAnRpcError() = runBlocking<Unit> {
        val wire = Transport()
        MobileRpcClient(wire, { "fixture-token" }).use { client ->
            client.connect()
            val mutation = async { runCatching { client.request("terminal.input", JSONObject().put("text", "once"), 2000) } }
            val originalInput = wire.sent.receive().second
            val read = async { client.request("mobile.workspace.list", timeoutMillis = 2000) }
            val originalRead = wire.sent.receive().second
            val trigger = async { runCatching { client.request("notification.feed.list", timeoutMillis = 100) } }
            wire.sent.receive()
            assertTrue(trigger.await().exceptionOrNull() is TimeoutCancellationException)
            val resent = withTimeout(2000) { wire.sent.receive() }
            assertEquals(1L, resent.first)
            assertEquals(originalRead.getString("id"), resent.second.getString("id"))
            assertTrue(withTimeout(1000) { mutation.await() }.exceptionOrNull() is MobileRpcOutcomeUnknown)
            wire.answer(resent.second)
            assertTrue(read.await().getBoolean("answered"))
            val probe = withTimeout(2000) { wire.sent.receive() }.second
            assertEquals(MobileControlResendPolicy.PROBE, probe.getString("method"))
            assertNotEquals(originalInput.getString("id"), probe.getString("id"))
            wire.answer(probe, error = true)
            val next = async { client.hostStatus() }
            wire.answer(wire.sent.receive().second)
            assertTrue(next.await().getBoolean("answered"))
            assertEquals(0, wire.closes.get())
            assertEquals(1, wire.repairs.get())
            assertTrue(wire.sent.tryReceive().isFailure)
        }
    }

    @Test fun paramsAreFrozenBeforeAuthenticationAndDetermineResendSafety() = runBlocking<Unit> {
        val wire = Transport()
        val authenticating = CompletableDeferred<Unit>()
        val tokenReady = CompletableDeferred<Unit>()
        MobileRpcClient(wire, { authenticating.complete(Unit); tokenReady.await(); "fixture-token" }).use { client ->
            client.connect()
            val params = JSONObject().put("viewport_columns", 80).put("nested", JSONObject().put("value", "original"))
            val mutation = async { runCatching { client.request("terminal.replay", params, 2000) } }
            withTimeout(1000) { authenticating.await() }
            params.remove("viewport_columns")
            params.getJSONObject("nested").put("value", "changed")
            tokenReady.complete(Unit)
            val sent = withTimeout(1000) { wire.sent.receive() }.second.getJSONObject("params")
            assertEquals(80, sent.getInt("viewport_columns"))
            assertEquals("original", sent.getJSONObject("nested").getString("value"))
            val trigger = async { runCatching { client.request("mobile.workspace.list", timeoutMillis = 100) } }
            wire.sent.receive()
            assertTrue(trigger.await().isFailure)
            assertTrue(withTimeout(1000) { mutation.await() }.exceptionOrNull() is MobileRpcOutcomeUnknown)
            val probe = withTimeout(1000) { wire.sent.receive() }.second
            assertEquals(MobileControlResendPolicy.PROBE, probe.getString("method"))
            wire.answer(probe)
            assertTrue(wire.sent.tryReceive().isFailure)
        }
    }

    @Test fun simultaneousTimeoutsCountAsOneSilentWindow() = runBlocking<Unit> {
        val wire = Transport(supportsControlRepair = false)
        MobileRpcClient(wire, { "fixture-token" }).use { client ->
            client.connect()
            val concurrent = (1..4).map { async { runCatching { client.request("mobile.workspace.list", timeoutMillis = 100) } } }
            repeat(4) { wire.sent.receive() }
            concurrent.forEach { assertTrue(it.await().isFailure) }
            assertFalse(client.isClosed)
            assertTrue(runCatching { client.request("mobile.workspace.list", timeoutMillis = 100) }.isFailure)
            assertTrue(client.isClosed)
            assertEquals(1, wire.closes.get())
        }
    }

    @Test fun otherInboundTrafficPreventsCondemningASlowRequest() = runBlocking<Unit> {
        val wire = Transport()
        MobileRpcClient(wire, { "fixture-token" }).use { client ->
            client.connect()
            val timeout = async { runCatching { client.request("mobile.workspace.list", timeoutMillis = 100) } }
            wire.sent.receive()
            val event = async(start = CoroutineStart.UNDISPATCHED) { client.events.first() }
            wire.input.send(MobileFrameCodec.encode("""{"kind":"event","topic":"notification.feed.changed","payload":{}}""".toByteArray()))
            withTimeout(1000) { event.await() }
            assertTrue(timeout.await().isFailure)
            assertEquals(0, wire.repairs.get())
            assertFalse(client.isClosed)
        }
    }

    @Test fun unavailableRepairFallsBackToTwoSilentTimeouts() = runBlocking<Unit> {
        val wire = Transport().apply { repairResult = MobileControlRepair.Unavailable }
        MobileRpcClient(wire, { "fixture-token" }).use { client ->
            client.connect()
            assertTrue(runCatching { client.request("terminal.input", timeoutMillis = 50) }.isFailure)
            withTimeout(1000) { while (wire.repairs.get() != 1) delay(1) }
            assertFalse(client.isClosed)
            assertTrue(runCatching { client.request("mobile.workspace.list", timeoutMillis = 100) }.isFailure)
            assertTrue(client.isClosed)
            assertEquals(1, wire.repairs.get())
        }
    }

    @Test fun replacementWithoutProbeAnswerRetiresOnceWithoutReplayingInput() = runBlocking<Unit> {
        val wire = Transport()
        MobileRpcClient(wire, { "fixture-token" }).use { client ->
            client.connect()
            assertTrue(runCatching { client.request("terminal.input", timeoutMillis = 50) }.isFailure)
            assertEquals("terminal.input", wire.sent.receive().second.getString("method"))
            assertEquals(MobileControlResendPolicy.PROBE, withTimeout(1000) { wire.sent.receive() }.second.getString("method"))
            withTimeout(1000) { client.disconnected.first() }
            assertEquals(1, wire.repairs.get())
            assertTrue(wire.sent.tryReceive().isFailure)
        }
    }

    @Test fun nativeConnectionClosureWakesPendingRequestsDespiteParkedControlRead() = runBlocking<Unit> {
        val wire = Transport()
        MobileRpcClient(wire, { "fixture-token" }).use { client ->
            client.connect()
            val waiting = async { runCatching { client.workspaces() } }
            wire.sent.receive()
            wire.disconnections.emit(EOFException("native closed"))
            assertTrue(withTimeout(1000) { waiting.await() }.isFailure)
            assertTrue(client.isClosed)
            assertEquals(0, wire.repairs.get())
        }
    }

    @Test fun stalledReadResendHasDeadlineAndCannotWedgeFutureWrites() = runBlocking<Unit> {
        val wire = Transport().apply { stallResend = true }
        MobileRpcClient(wire, { "fixture-token" }).use { client ->
            client.connect()
            val oldRead = async { runCatching { client.request("mobile.workspace.list", timeoutMillis = 250) } }
            wire.sent.receive()
            assertTrue(runCatching { client.request("terminal.input", timeoutMillis = 50) }.isFailure)
            withTimeout(1000) { client.disconnected.first() }
            assertTrue(oldRead.await().isFailure)
            assertEquals(1, wire.closes.get())
        }
    }

    @Test fun resendAllowlistExcludesMutationsAndViewportReportingReplays() {
        assertTrue(MobileControlResendPolicy.allows("mobile.workspace.list", JSONObject()))
        assertTrue(MobileControlResendPolicy.allows("mobile.terminal.replay", JSONObject()))
        for (field in listOf("client_id", "viewport_columns", "viewport_rows"))
            assertFalse(MobileControlResendPolicy.allows("mobile.terminal.replay", JSONObject().put(field, 1)))
        for (method in listOf("terminal.input", "terminal.paste", "mobile.terminal.viewport", "mobile.events.subscribe",
            "mobile.events.unsubscribe", "mobile.terminal.artifact.fetch", "mobile.terminal.artifact.scan", "new.unknown.method"))
            assertFalse(MobileControlResendPolicy.allows(method, JSONObject()))
    }
}
