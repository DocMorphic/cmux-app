package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeTerminalSizingSessionTest {
    private val owner = TerminalInputSender.Owner("login", "user", "team", "mac", "nightly")
    private val surface = "11111111-2222-3333-4444-555555555555"
    private class Wire : MobileRpcTransport {
        val received = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val laneSends = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun connect() {}
        override suspend fun read() = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(MobileFrameDecoder().feed(bytes).single().toString(Charsets.UTF_8))
            received += request
            incoming.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", true)
                .put("result", JSONObject()).toString().toByteArray()))
        }
        override suspend fun openTerminalInput(surfaceId: String): TerminalInputLane = object : TerminalInputLane {
            override val closed = MutableStateFlow(false)
            override val supportsIdentifiedInput = true
            override suspend fun send(text: String) { laneSends.incrementAndGet() }
            override suspend fun sendIdentified(text: String, delivery: TerminalInputDelivery) { laneSends.incrementAndGet() }
            override fun close() { closed.value = true }
        }
        override suspend fun openTerminalOutput(surfaceId: String, cursor: ULong?): TerminalOutputLane {
            val input = openTerminalInput(surfaceId)
            return object : TerminalOutputLane, TerminalInputLane by input {
                override suspend fun receive(): TerminalLaneProtocol.Output? = null
            }
        }
        override fun close() { incoming.close() }
    }
    private suspend fun event(wire: Wire, client: MobileRpcClient, topic: String, stream: String, payload: JSONObject) {
        val delivered = CompletableDeferred<Unit>()
        client.observeTerminalSizing { if (it.topic == topic && it.streamId == stream) delivered.complete(Unit) }.use {
            wire.incoming.send(MobileFrameCodec.encode(JSONObject().put("kind", "event").put("topic", topic)
                .put("stream_id", stream).put("payload", payload).toString().toByteArray()))
            withTimeout(3000) { delivered.await() }
        }
    }
    private suspend fun detach(wire: Wire, client: MobileRpcClient, stream: String, reason: String = "disconnected-by") =
        event(wire, client, "mobile.terminal.detached", stream, JSONObject().put("surface_id", surface).put("reason", reason))

    @Test fun detachBlocksAllRpcPathsAndBothNativeLanesThroughLeases() = runBlocking<Unit> {
        val wire = Wire()
        MobileRpcClient(wire, { "fixture" }).use { ownerClient ->
            ownerClient.connect()
            ownerClient.lease {}.use { client ->
                val session = NativeTerminalSizingSession()
                val stream = session.bind(owner, client)
                val delivery = TerminalInputDelivery(UUID.fromString(surface), UUID.randomUUID(), 1u)
                client.useTerminalInputLane(surface) { input ->
                    client.useTerminalOutputLane(surface, null) { output ->
                        input.send("before"); output.sendIdentified("before", delivery)
                        detach(wire, client, stream)
                        for (lane in listOf(input, output)) {
                            assertTrue(runCatching { lane.send("blocked") }.isFailure)
                            assertTrue(runCatching { lane.sendIdentified("blocked", delivery) }.isFailure)
                        }
                        assertTrue(runCatching { output.receive() }.isFailure)
                    }
                }
                for (method in listOf("terminal.input", "terminal.paste", "terminal.paste_image", "mobile.terminal.mouse",
                    "mobile.terminal.scroll", "mobile.terminal.viewport", "mobile.terminal.replay", "terminal.viewport", "terminal.replay")) {
                    assertTrue(method, runCatching { client.request(method, JSONObject().put("surface_id", surface)) }.isFailure)
                }
                assertTrue(wire.received.isEmpty()); assertEquals(2, wire.laneSends.get())
                client.workspaces() // Unrelated traffic remains usable.
                assertEquals("mobile.workspace.list", wire.received.single().getString("method"))
                session.clear()
            }
        }
    }

    @Test fun detachDuringTokenLookupCannotWriteAndDoesNotCloseSharedConnection() = runBlocking<Unit> {
        val wire = Wire(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        MobileRpcClient(wire, { entered.complete(Unit); release.await(); "fixture" }).use { root ->
            root.connect()
            root.lease {}.use { client ->
                val session = NativeTerminalSizingSession(); val stream = session.bind(owner, client)
                val request = async { runCatching { client.input("workspace", surface, "pending") } }
                withTimeout(3000) { entered.await() }; detach(wire, client, stream); release.complete(Unit)
                assertTrue(withTimeout(3000) { request.await() }.isFailure)
                assertTrue(wire.received.isEmpty()); assertFalse(root.isClosed)
                session.clear()
            }
        }
    }

    @Test fun explicitDetachSurvivesReconnectAndOnlyCurrentAcknowledgedReattachClearsIt() = runBlocking<Unit> {
        val firstWire = Wire(); val nextWire = Wire()
        MobileRpcClient(firstWire, { "fixture" }).use { first ->
            MobileRpcClient(nextWire, { "fixture" }).use { next ->
                first.connect(); next.connect()
                val session = NativeTerminalSizingSession(); val oldStream = session.bind(owner, first)
                detach(firstWire, first, oldStream)
                val oldSnapshot = session.state.value.getValue(surface)
                session.unbind(first); val stream = session.bind(owner, next)
                assertFalse(next.terminalTrafficAllowed(surface))
                session.replay(next, surface, JSONObject().put("size_state", JSONObject().put("malformed", true)))
                detach(nextWire, next, stream, "network")
                assertFalse(next.terminalTrafficAllowed(surface))
                assertFalse(session.reattached(first, surface, JSONObject(), oldSnapshot))
                // New explicit detach while reattach is in flight must win.
                val expected = session.state.value.getValue(surface)
                detach(nextWire, next, stream)
                assertFalse(session.reattached(next, surface, JSONObject(), expected))
                val current = session.state.value.getValue(surface)
                val ack = next.reattachTerminal("workspace", surface, true, null)
                assertTrue(session.reattached(next, surface, ack, current))
                assertTrue(next.terminalTrafficAllowed(surface))
                assertTrue(nextWire.received.single().getJSONObject("params").getBoolean("as_viewer"))
                assertFalse(MobileControlResendPolicy.allows("mobile.terminal.reattach", JSONObject()))
                detach(firstWire, first, oldStream) // Replaced wire cannot detach the new binding.
                assertTrue(next.terminalTrafficAllowed(surface))
                session.clear()
            }
        }
    }

    @Test fun detachSurvivesComputerListOtherMacAndBuildNavigationWithoutLeaking() = runBlocking<Unit> {
        val firstWire = Wire(); val otherWire = Wire(); val returnedWire = Wire()
        MobileRpcClient(firstWire, { "fixture" }).use { first ->
            MobileRpcClient(otherWire, { "fixture" }).use { other ->
                MobileRpcClient(returnedWire, { "fixture" }).use { returned ->
                    first.connect(); other.connect(); returned.connect()
                    val session = NativeTerminalSizingSession()
                    val oldStream = session.bind(owner, first); detach(firstWire, first, oldStream)
                    session.retainOwner(null) // Computers list is not an account reset.
                    assertTrue(session.state.value.isEmpty())
                    assertFalse(session.allowsTraffic(owner, surface))
                    val otherOwner = owner.copy(device = "another-mac")
                    session.bind(otherOwner, other)
                    assertTrue(other.terminalTrafficAllowed(surface))
                    assertTrue(session.allowsTraffic(otherOwner, surface))
                    assertTrue(session.allowsTraffic(owner.copy(build = "stable"), surface))
                    assertTrue(session.allowsTraffic(owner.copy(team = "another-team"), surface))
                    assertFalse(session.allowsTraffic(owner, surface))
                    session.bind(owner, returned)
                    assertFalse(returned.terminalTrafficAllowed(surface))
                    val expected = session.state.value.getValue(surface)
                    assertNotNull(expected.detached); assertNull(expected.state)
                    assertTrue(session.reattached(returned, surface, JSONObject(), expected))
                    assertTrue(session.allowsTraffic(owner, surface))
                    detach(firstWire, first, oldStream) // Deselected wire cannot re-detach the returned view.
                    assertTrue(session.allowsTraffic(owner, surface))
                    session.clear()
                }
            }
        }
    }

    @Test fun accountReplacementAndExplicitClearRetireAllRememberedDetachStates() = runBlocking<Unit> {
        val wire = Wire()
        MobileRpcClient(wire, { "fixture" }).use { client ->
            client.connect(); val session = NativeTerminalSizingSession()
            var stream = session.bind(owner, client); detach(wire, client, stream)
            session.retainOwner(null)
            session.bind(owner.copy(login = "next-login"), client)
            assertTrue(client.terminalTrafficAllowed(surface))
            session.bind(owner, client)
            assertTrue(client.terminalTrafficAllowed(surface)) // Old account cache was erased.
            stream = checkNotNull(session.subscription(client)); detach(wire, client, stream)
            session.retainOwner(null); session.clear()
            session.bind(owner, client)
            assertTrue(client.terminalTrafficAllowed(surface))
            session.clear()
        }
    }

    @Test fun ownerKeyCanonicalizesUuidAndKeepsAccountTeamAndBuildSeparate() {
        val mac = NativeCredentialStore.PairedMac("fixture", "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", "Fixture",
            " nightly ", accountUserId = "user", accountTeamId = "team")
        val key = checkNotNull(nativeTerminalInputOwner(mac, "login"))
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", key.device)
        assertEquals("nightly", key.build); assertEquals("user", key.user); assertEquals("team", key.team)
        assertNotEquals(key, nativeTerminalInputOwner(mac.copy(instanceTag = "stable"), "login"))
        assertNotEquals(key, nativeTerminalInputOwner(mac.copy(accountTeamId = "other"), "login"))
        assertNull(nativeTerminalInputOwner(mac, null))
    }

    @Test fun policyAcknowledgementCannotReplacePhoneIdentityWithMacIdentity() = runBlocking<Unit> {
        val wire = Wire()
        MobileRpcClient(wire, { "fixture" }).use { client ->
            client.connect()
            val session = NativeTerminalSizingSession(); session.bind(owner, client)
            val state = JSONObject("""{"generation":1,"cols":80,"rows":24,"reason":"smallest","owners":["mac"],
                "policy":{"mode":"smallest","priority":[],"fixed":null},"participants":[
                {"id":"mac","display_name":"Fixture","device_kind":"mac","counts":true,"priority_key":"mac"},
                {"id":"phone","display_name":"Fixture","device_kind":"unknown","counts":true,"priority_key":"phone"}]}""")
            session.replay(client, surface, JSONObject().put("size_state", state).put("self_participant_id", "phone"))
            session.rendered(client, surface, SharedTerminalGrid(80, 24))
            val reply = JSONObject().put("surface_id", surface).put("size_state", state.put("generation", 2).put("cols", 100))
                .put("self_participant_id", "mac")
            session.mutation(client, surface, reply)
            assertEquals("phone", session.state.value.getValue(surface).selfId)
            assertEquals(100, session.state.value.getValue(surface).state!!.grid.columns)
            assertEquals(1L, session.state.value.getValue(surface).viewportRevision)
            reply.put("surface_id", "another-terminal").getJSONObject("size_state").put("cols", 120)
            session.mutation(client, surface, reply)
            assertEquals(100, session.state.value.getValue(surface).state!!.grid.columns)
            session.clear()
        }
    }

    @Test fun androidReportsTruthfulDeviceIdentityOnlyWhenNegotiated() = runBlocking<Unit> {
        val wire = Wire()
        MobileRpcClient(wire, { "fixture" }).use { client ->
            client.connect()
            client.reportViewport("workspace", surface, TerminalViewport(67, 47), 1)
            assertFalse(wire.received.last().getJSONObject("params").has("device_kind"))
            assertFalse(wire.received.last().getJSONObject("params").has("device_id"))
            client.terminalDeviceIdentity = TerminalDeviceIdentity("Pixel 6a", "f2f98081-af0d-4dbe-a590-aad4d516f450")
            client.reportViewport("workspace", surface, TerminalViewport(67, 47), 2)
            client.replay("workspace", surface, 67, 47, 2)
            client.reattachTerminal("workspace", surface, true, TerminalViewport(67, 47))
            wire.received.drop(1).forEach {
                val params = it.getJSONObject("params")
                assertEquals("unknown", params.getString("device_kind"))
                assertEquals("Pixel 6a", params.getString("device_name"))
                assertEquals("f2f98081-af0d-4dbe-a590-aad4d516f450", params.getString("device_id"))
                assertFalse(params.has("counts_override"))
            }
            client.terminalDeviceIdentity = TerminalDeviceIdentity("Pixel 6a")
            client.reportViewport("workspace", surface, TerminalViewport(67, 47), 3)
            assertEquals("Pixel 6a", wire.received.last().getJSONObject("params").getString("device_name"))
            assertFalse(wire.received.last().getJSONObject("params").has("device_id"))
        }
    }

    @Test fun otherStreamsAreIgnoredAndMalformedDetachIsConservative() = runBlocking<Unit> {
        val wire = Wire()
        MobileRpcClient(wire, { "fixture" }).use { client ->
            client.connect()
            val session = NativeTerminalSizingSession(); val stream = session.bind(owner, client)
            detach(wire, client, "another-consumer")
            assertTrue(client.terminalTrafficAllowed(surface))
            event(wire, client, "mobile.terminal.detached", stream, JSONObject().put("surface_id", surface))
            assertFalse(client.terminalTrafficAllowed(surface)); assertFalse(client.isClosed)
            session.bind(owner.copy(login = "replacement-login"), client)
            assertTrue(client.terminalTrafficAllowed(surface))
            session.clear()
        }
    }
}
