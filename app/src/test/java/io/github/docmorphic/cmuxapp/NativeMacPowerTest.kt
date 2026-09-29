package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeMacPowerTest {
    private val target = NativeComputerTarget("mac", "default", "Test Mac")
    private class Wire : MobileRpcTransport {
        val input = Channel<ByteArray>(32)
        val sent = Channel<JSONObject>(32)
        var stream = ""
        override suspend fun connect() { }
        override suspend fun read() = input.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(MobileFrameDecoder().feed(bytes).single().decodeToString())
            if (request.getString("method") == "mobile.events.unsubscribe") answer(request, JSONObject())
            else sent.send(request)
        }
        suspend fun next(method: String): JSONObject = withTimeout(2000) { sent.receive().also {
            assertEquals(method, it.getString("method"))
        } }
        suspend fun answer(request: JSONObject, result: JSONObject) {
            input.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", true)
                .put("result", result).toString().toByteArray()))
        }
        suspend fun event(enabled: Any, streamId: String = stream) {
            input.send(MobileFrameCodec.encode(JSONObject().put("kind", "event").put("stream_id", streamId)
                .put("topic", "caffeine.status.changed").put("payload", JSONObject().put("enabled", enabled)).toString().toByteArray()))
        }
        override fun close() { input.close() }
    }
    private fun host(capability: Boolean = true, device: String = "mac", build: String = "default") = JSONObject()
        .put("mac_device_id", device).put("mac_instance_tag", build)
        .put("capabilities", JSONArray(if (capability) listOf("caffeine.control.v1") else emptyList<String>()))
    private fun status(enabled: Any) = JSONObject().put("enabled", enabled)
    private suspend fun ready(wire: Wire, session: NativeMacPowerSession) {
        wire.answer(wire.next("mobile.host.status"), host())
        val subscription = wire.next("mobile.events.subscribe")
        wire.stream = subscription.getJSONObject("params").getString("stream_id")
        assertEquals("caffeine.status.changed", subscription.getJSONObject("params").getJSONArray("topics").getString(0))
        wire.answer(subscription, JSONObject())
        wire.answer(wire.next("caffeine.status"), status(false))
        withTimeout(2000) { session.state.first { it.enabled == false && !it.busy } }
    }
    private fun CoroutineScope.start(client: MobileRpcClient, gate: Mutex = Mutex(), permits: () -> Boolean = { true },
                                     timeout: Long = 1000): Pair<NativeMacPowerSession, Job> {
        val session = NativeMacPowerSession(client.lease {}, target, permits, gate, timeout, 60_000)
        return session to launch { session.run() }
    }

    @Test fun feedObserverUnsubscribesWithoutClosingItsEnclosingClient() = runBlocking {
        val wire = Wire()
        MobileRpcClient(wire, { "token" }).use { client ->
            client.connect()
            val session = NativeMacPowerSession(client, target, { true }, Mutex(), closeClientOnExit = false)
            val job = launch { session.run() }
            ready(wire, session)
            job.cancelAndJoin()
            assertFalse(client.isClosed)
            assertEquals(NativeMacPowerState(), session.state.value)
            val read = async { client.hostStatus() }
            wire.answer(wire.next("mobile.host.status"), host())
            assertEquals("mac", read.await().getString("mac_device_id"))
        }
    }

    @Test fun gatesUnsupportedAndWrongIdentityBeforeAnyPowerRpc() = runBlocking {
        for (host in listOf(host(false), host(device = "other"), host(build = "other"))) {
            val wire = Wire()
            MobileRpcClient(wire, { "token" }).use { client ->
                client.connect(); val (session, job) = start(client)
                wire.answer(wire.next("mobile.host.status"), host)
                withTimeout(2000) { session.state.first { !it.busy } }
                session.setEnabled(true); delay(30)
                assertTrue(wire.sent.isEmpty)
                assertNull(session.state.value.enabled)
                job.cancelAndJoin(); assertFalse(client.isClosed)
            }
        }
    }

    @Test fun authoritativeReplyOverridesOptimisticSwitchAndBlocksDuplicateTap() = runBlocking {
        val wire = Wire()
        MobileRpcClient(wire, { "token" }).use { client ->
            client.connect(); val (session, job) = start(client); ready(wire, session)
            session.setEnabled(true)
            val request = wire.next("caffeine.set")
            assertTrue(request.getJSONObject("params").getBoolean("enabled"))
            assertTrue(session.state.value.busy); assertEquals(true, session.state.value.enabled)
            session.setEnabled(false); delay(30); assertTrue(wire.sent.isEmpty)
            wire.answer(request, status(false))
            withTimeout(2000) { session.state.first { !it.busy } }
            assertEquals(false, session.state.value.enabled)
            job.cancelAndJoin()
        }
    }

    @Test fun malformedOrLostMutationReplyReconcilesWithoutResending() = runBlocking {
        for (malformed in listOf(true, false)) {
            val wire = Wire()
            MobileRpcClient(wire, { "token" }).use { client ->
                client.connect(); val (session, job) = start(client, timeout = 200); ready(wire, session)
                session.setEnabled(true)
                val request = wire.next("caffeine.set")
                if (malformed) wire.answer(request, status("true"))
                val reconcile = wire.next("caffeine.status")
                assertNull(session.state.value.enabled)
                wire.answer(reconcile, status(true))
                withTimeout(2000) { session.state.first { !it.busy } }
                assertEquals(true, session.state.value.enabled); assertNull(session.state.value.error)
                assertTrue(wire.sent.isEmpty)
                job.cancelAndJoin()
            }
        }
    }

    @Test fun failedReconciliationKeepsUnknownAndRetryOnlyReads() = runBlocking {
        val wire = Wire()
        MobileRpcClient(wire, { "token" }).use { client ->
            client.connect(); val (session, job) = start(client); ready(wire, session)
            session.setEnabled(true)
            wire.answer(wire.next("caffeine.set"), JSONObject())
            wire.answer(wire.next("caffeine.status"), status(1))
            withTimeout(2000) { session.state.first { !it.busy } }
            assertNull(session.state.value.enabled); assertEquals(NativeMacPowerState.SET_ERROR, session.state.value.error)
            session.setEnabled(false); delay(20); assertTrue(wire.sent.isEmpty)
            session.refresh()
            wire.answer(wire.next("caffeine.status"), status(true))
            withTimeout(2000) { session.state.first { !it.busy } }
            assertEquals(true, session.state.value.enabled); assertNull(session.state.value.error)
            job.cancelAndJoin()
        }
    }

    @Test fun reconciledOppositeStateIsAuthoritativeButExplainsUnconfirmedChange() = runBlocking {
        val wire = Wire()
        MobileRpcClient(wire, { "token" }).use { client ->
            client.connect(); val (session, job) = start(client); ready(wire, session)
            session.setEnabled(true)
            wire.answer(wire.next("caffeine.set"), JSONObject())
            wire.answer(wire.next("caffeine.status"), status(false))
            withTimeout(2000) { session.state.first { !it.busy } }
            assertEquals(false, session.state.value.enabled)
            assertEquals(NativeMacPowerState.SET_ERROR, session.state.value.error)
            assertTrue(wire.sent.isEmpty)
            job.cancelAndJoin()
        }
    }

    @Test fun eventsSupersedeOlderReadAndMutationRepliesAndIgnoreOtherStreams() = runBlocking {
        val wire = Wire()
        MobileRpcClient(wire, { "token" }).use { client ->
            client.connect(); val (session, job) = start(client); ready(wire, session)
            session.refresh(); val read = wire.next("caffeine.status")
            wire.event(true)
            withTimeout(2000) { session.state.first { it.enabled == true } }
            wire.answer(read, status(false))
            withTimeout(2000) { session.state.first { !it.busy } }
            assertEquals(true, session.state.value.enabled)
            session.setEnabled(false); val mutation = wire.next("caffeine.set")
            wire.event(true)
            withTimeout(2000) { session.state.first { it.enabled == true } }
            wire.answer(mutation, status(false))
            withTimeout(2000) { session.state.first { !it.busy } }
            wire.event(false, "other-session"); wire.event("false"); delay(30)
            assertEquals(true, session.state.value.enabled)
            job.cancelAndJoin(); assertEquals(NativeMacPowerState(), session.state.value)
        }
    }

    @Test fun scopeRevocationDisablesStateAndLateResponseCannotRestoreIt() = runBlocking {
        val wire = Wire(); var allowed = true
        MobileRpcClient(wire, { "token" }).use { client ->
            client.connect(); val (session, job) = start(client, permits = { allowed }); ready(wire, session)
            session.setEnabled(true); val pending = wire.next("caffeine.set")
            allowed = false
            wire.answer(pending, status(true))
            withTimeout(2500) { job.join() }
            assertEquals(NativeMacPowerState(), session.state.value)
            session.setEnabled(true); delay(30); assertTrue(wire.sent.isEmpty)
        }
    }

    @Test fun sameMacMutationGatePreventsAnotherPageFromSending() = runBlocking {
        val wire = Wire(); val gate = Mutex()
        MobileRpcClient(wire, { "token" }).use { client ->
            client.connect(); val (session, job) = start(client, gate); ready(wire, session)
            gate.lock(); session.setEnabled(true); delay(30)
            assertTrue(wire.sent.isEmpty); assertEquals(false, session.state.value.enabled)
            gate.unlock(); session.setEnabled(true)
            wire.answer(wire.next("caffeine.set"), status(true))
            withTimeout(2000) { session.state.first { it.enabled == true && !it.busy } }
            job.cancelAndJoin(); assertFalse(gate.isLocked)
        }
    }

    @Test fun lostSubscriptionReplyCanBeRetriedAndDisconnectClearsState() = runBlocking {
        val wire = Wire()
        MobileRpcClient(wire, { "token" }).use { client ->
            client.connect(); val (session, job) = start(client, timeout = 200)
            wire.answer(wire.next("mobile.host.status"), host())
            val first = wire.next("mobile.events.subscribe")
            withTimeout(2000) { session.state.first { !it.busy } }
            assertEquals(NativeMacPowerState.READ_ERROR, session.state.value.error)
            session.refresh(); val second = wire.next("mobile.events.subscribe")
            assertEquals(first.getJSONObject("params").getString("stream_id"), second.getJSONObject("params").getString("stream_id"))
            wire.answer(second, JSONObject()); wire.answer(wire.next("caffeine.status"), status(false))
            withTimeout(2000) { session.state.first { !it.busy } }
            client.retire(); withTimeout(2000) { job.join() }
            assertEquals(NativeMacPowerState(), session.state.value)
        }
    }
}
