package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeTerminalInputSessionTest {
    private val owner = TerminalInputSender.Owner("login", "user", "team", "mac", "stable")
    private val surface = "11111111-2222-3333-4444-555555555555"
    private val target = NativeTerminalInputSession.Target("workspace", surface)
    private val targets = setOf(target)
    private val capabilities = setOf(TerminalInputDelivery.CAPABILITY)
    private val key = TerminalInputSender.Key(owner, UUID.fromString(surface))
    private class Wire : MobileRpcTransport {
        val requests = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        private val replies = Channel<ByteArray>(Channel.UNLIMITED)
        var identifiedReply = true
        override suspend fun connect() {}
        override suspend fun read() = replies.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(String(bytes.copyOfRange(4, bytes.size), Charsets.UTF_8)); requests += request
            val params = request.getJSONObject("params")
            val result = JSONObject().put("fixture_field", "preserved")
            if (request.getString("method") == "mobile.terminal.scroll") result.put("render_grid", JSONObject().put("rows", 17))
            if (identifiedReply && params.has("input_stream_id")) result.put("input_ack", JSONObject().put("status", "applied")
                .put("stream_id", params.getString("input_stream_id")).put("sequence", params.getString("input_stream_seq")).put("expected", "0"))
            replies.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", true)
                .put("result", result).toString().toByteArray()))
        }
        override fun close() { replies.close() }
    }
    private fun answer(delivery: TerminalInputDelivery) = TerminalInputAcknowledgement(
        TerminalInputAcknowledgement.Status.APPLIED, delivery.stream, delivery.sequence)
    private suspend fun idle(session: NativeTerminalInputSession) = withTimeout(3000) {
        session.status.first { it[key]?.pendingUnits == 0 }
    }

    @Test fun allFivePublicMethodsUseOneOrderedIdentityStreamAndPreserveRpcFields() = runBlocking<Unit> {
        val wire = Wire(); val client = MobileRpcClient(wire, { "fixture" })
        NativeTerminalInputSession(this).use { session ->
            try {
                client.connect(); session.attach(owner, client, capabilities, targets) { true }
                client.input("workspace", surface, "keys")
                client.paste("workspace", surface, "paste", true)
                client.pasteImage("workspace", surface, byteArrayOf(1, 2), "png")
                client.terminalClick("workspace", surface, TerminalGeometry.Cell(2, 3))
                val response = client.terminalScroll("workspace", surface, TerminalScroll(1.0, 2, 3, 100))
                assertEquals("preserved", response.getString("fixture_field")); assertEquals(17, response.getJSONObject("render_grid").getInt("rows"))
                val params = wire.requests.map { it.getJSONObject("params") }
                assertEquals(listOf("1", "2", "3", "4", "5"), params.map { it.getString("input_stream_seq") })
                assertEquals(1, params.map { it.getString("input_stream_id") }.toSet().size)
                assertEquals(listOf("terminal.input", "terminal.paste", "terminal.paste_image", "mobile.terminal.mouse", "mobile.terminal.scroll"),
                    wire.requests.map { it.getString("method") })
            } finally { client.close() }
        }
    }

    @Test fun imageBytesAreSnapshottedBeforeWaitingBehindLaneAcknowledgement() = runBlocking<Unit> {
        val wire = Wire(); val client = MobileRpcClient(wire, { "fixture" })
        NativeTerminalInputSession(this).use { session ->
            try {
                client.connect(); session.attach(owner, client, capabilities, targets) { true }
                val sent = Channel<TerminalInputDelivery>(4)
                session.registerLane(client, "workspace", surface, MutableStateFlow(true)) { _, d -> sent.send(d); true }.use {
                    val queue = session.orderedQueue(client, "workspace", surface)!!
                    queue.offer("before"); val first = withTimeout(3000) { sent.receive() }
                    val image = byteArrayOf(1, 2)
                    val pending = async(start = CoroutineStart.UNDISPATCHED) { client.pasteImage("workspace", surface, image, "png") }
                    image.fill(9); yield(); assertTrue(wire.requests.isEmpty())
                    session.receive(client, surface, answer(first)); withTimeout(3000) { pending.await() }
                    assertEquals("AQI=", wire.requests.single().getJSONObject("params").getString("image_base64"))
                }
            } finally { client.close() }
        }
    }

    @Test fun retainedQueueRebindsOriginalTargetAfterActivityClientCloses() = runBlocking<Unit> {
        val first = MobileRpcClient(Wire(), { "fixture" }); val wire = Wire(); val next = MobileRpcClient(wire, { "fixture" })
        NativeTerminalInputSession(this).use { session ->
            try {
                first.connect(); session.attach(owner, first, capabilities, targets) { true }
                val sent = Channel<TerminalInputDelivery>(4)
                val lane = session.registerLane(first, "workspace", surface, MutableStateFlow(true)) { _, d -> sent.send(d); true }
                val queue = session.orderedQueue(first, "workspace", surface)!!
                queue.offer("first"); val original = withTimeout(3000) { sent.receive() }
                session.detach(first); first.close()
                queue.offer("second"); yield()
                next.connect(); session.attach(owner, next, capabilities, targets) { true }
                assertSame(queue, session.orderedQueue(next, "workspace", surface))
                lane.close() // Late Activity cleanup must not retire the new binding.
                withTimeout(3000) { queue.awaitIdle(); idle(session) }
                assertEquals(listOf("first", "second"), wire.requests.map { it.getJSONObject("params").getString("text") })
                val replay = wire.requests.first().getJSONObject("params")
                assertEquals(original.stream.toString(), replay.getString("input_stream_id"))
                assertEquals(original.sequence.toString(), replay.getString("input_stream_seq"))
            } finally { first.close(); next.close() }
        }
    }

    @Test fun preparationReservationBlocksMouseAndScrollFromPassingImagePaste() = runBlocking<Unit> {
        val wire = Wire(); val client = MobileRpcClient(wire, { "fixture" })
        NativeTerminalInputSession(this).use { session ->
            try {
                client.connect(); session.attach(owner, client, capabilities, targets) { true }
                val queue = session.orderedQueue(client, "workspace", surface)!!
                val prepared = CompletableDeferred<Unit>()
                queue.offerAction(release = {}) { prepared.await(); client.pasteImage("workspace", surface, byteArrayOf(1), "png") }
                val click = async { queue.performOrdered { client.terminalClick("workspace", surface, TerminalGeometry.Cell(2, 3)) } }
                val scroll = async { queue.performOrdered { client.terminalScroll("workspace", surface, TerminalScroll(2.0, 2, 3)) } }
                yield(); assertTrue(wire.requests.isEmpty())
                prepared.complete(Unit)
                withTimeout(3000) { click.await(); assertTrue(scroll.await().has("render_grid")); queue.awaitIdle() }
                assertEquals(listOf("terminal.paste_image", "mobile.terminal.mouse", "mobile.terminal.scroll"), wire.requests.map { it.getString("method") })
            } finally { client.close() }
        }
    }

    @Test fun changingMacRetiresQueuedInputAndOldRpcHooksCannotTargetNewMac() = runBlocking<Unit> {
        val old = MobileRpcClient(Wire(), { "fixture" }); val newWire = Wire(); val next = MobileRpcClient(newWire, { "fixture" })
        NativeTerminalInputSession(this).use { session ->
            try {
                old.connect(); next.connect(); session.attach(owner, old, capabilities, targets) { true }
                val sent = Channel<TerminalInputDelivery>(4)
                val lane = session.registerLane(old, "workspace", surface, MutableStateFlow(true)) { _, d -> sent.send(d); true }
                val queue = session.orderedQueue(old, "workspace", surface)!!
                queue.offer("old input"); withTimeout(3000) { sent.receive() }
                session.attach(owner.copy(device = "other"), next, capabilities, targets) { true }
                assertTrue(queue.status.value.closed)
                assertTrue(runCatching { old.paste("workspace", surface, "old callback", false) }.isFailure)
                next.input("workspace", surface, "new input")
                assertEquals(listOf("new input"), newWire.requests.map { it.getJSONObject("params").getString("text") })
                lane.close()
            } finally { old.close(); next.close() }
        }
    }

    @Test fun absentCapabilityKeepsLegacyParamsAndForeignTargetsFailBeforeWriting() = runBlocking<Unit> {
        val wire = Wire(); val client = MobileRpcClient(wire, { "fixture" })
        NativeTerminalInputSession(this).use { session ->
            try {
                client.connect(); session.attach(owner, client, emptySet(), targets) { true }
                assertNull(session.orderedQueue(client, "workspace", surface))
                client.input("workspace", surface, "legacy")
                assertFalse(wire.requests.single().getJSONObject("params").has("input_stream_id"))
                assertTrue(runCatching { client.input("other-workspace", surface, "wrong") }.isFailure)
                assertTrue(runCatching { client.input("workspace", UUID.randomUUID().toString(), "wrong") }.isFailure)
                assertEquals(1, wire.requests.size)
            } finally { client.close() }
        }
    }

    @Test fun missingIdentityRequiresFreshNegotiationAndExplicitRecoveryBeforeLegacyInput() = runBlocking<Unit> {
        val wire = Wire().apply { identifiedReply = false }; val client = MobileRpcClient(wire, { "fixture" })
        NativeTerminalInputSession(this).use { session ->
            try {
                client.connect(); session.attach(owner, client, capabilities, targets) { true }
                val queue = session.orderedQueue(client, "workspace", surface)!!
                client.input("workspace", surface, "first")
                assertTrue(session.requiresReconnect(key)); assertFalse(session.resume(key))
                assertTrue(runCatching { client.input("workspace", surface, "blocked") }.isFailure)
                session.attach(owner, client, emptySet(), targets) { true }
                assertFalse(session.requiresReconnect(key)); assertTrue(session.resume(key))
                assertSame(queue, session.orderedQueue(client, "workspace", surface))
                queue.offer("explicit legacy"); withTimeout(3000) { queue.awaitIdle() }
                assertEquals(2, wire.requests.size)
                assertFalse(wire.requests.last().getJSONObject("params").has("input_stream_id"))
            } finally { client.close() }
        }
    }

    @Test fun removingTerminalClosesItsQueueAndNeverRecreatesItFromLateInput() = runBlocking<Unit> {
        val wire = Wire(); val client = MobileRpcClient(wire, { "fixture" })
        NativeTerminalInputSession(this).use { session ->
            try {
                client.connect(); session.attach(owner, client, capabilities, targets) { true }
                val queue = session.orderedQueue(client, "workspace", surface)!!
                session.updateTargets(client, emptySet())
                assertTrue(queue.status.value.closed)
                assertTrue(runCatching { client.input("workspace", surface, "gone") }.isFailure)
                assertTrue(wire.requests.isEmpty()); assertFalse(session.status.value.containsKey(key))
            } finally { client.close() }
        }
    }
    @Test fun advertisedDeliveryWithMalformedTerminalIdentityCannotFallBackToLegacyWrites() = runBlocking<Unit> {
        val wire = Wire(); val client = MobileRpcClient(wire, { "fixture" })
        NativeTerminalInputSession(this).use { session ->
            try {
                client.connect()
                session.attach(owner, client, capabilities, setOf(NativeTerminalInputSession.Target("workspace", "1-1-1-1-1"))) { true }
                assertTrue(runCatching { client.input("workspace", "1-1-1-1-1", "invalid") }.isFailure)
                assertTrue(wire.requests.isEmpty())
                session.attach(owner, client, emptySet(), setOf(NativeTerminalInputSession.Target("workspace", "old-terminal"))) { true }
                client.input("workspace", "old-terminal", "legacy")
                assertFalse(wire.requests.single().getJSONObject("params").has("input_stream_id"))
            } finally { client.close() }
        }
    }

}
