package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LegacySimulatorSessionTest {
    private val panel = "abcdef01-2345-6789-abcd-ef0123456789"
    private val caps = setOf("simulator.stream.v1", "simulator.input.v1", "simulator.keepalive.v1")
    private fun descriptor(owner: String? = "this-connection", owned: Boolean? = true) = JSONObject()
        .put("panel_id", panel).put("workspace_id", "w").put("title", "Simulator").put("status", "ready")
        .put("is_ready", true).put("supports_touch", true).put("supports_keyboard", true)
        .put("supports_hardware_buttons", true).put("supports_rotation", true)
        .put("owner_connection_id", owner ?: JSONObject.NULL).put("is_owned_by_current_connection", owned ?: JSONObject.NULL)
    private fun frame(sequence: Long = 1) = JSONObject().put("panel_id", panel).put("seq", sequence).put("format", "png")
        .put("pixel_width", 64).put("pixel_height", 96).put("display_scale", 2).put("data_base64", "YWJj")
    private inner class Endpoint : LegacySimulatorEndpoint {
        override val events = MutableSharedFlow<MobileRpcClient.Event>(extraBufferCapacity = 32)
        var stream = ""
        val calls = mutableListOf<Pair<String, JSONObject>>()
        var response: suspend (String, JSONObject) -> JSONObject = { method, _ ->
            if (method == "mobile.simulator.stream.start") descriptor() else JSONObject()
        }
        override suspend fun subscribe(streamId: String): JSONObject {
            stream = streamId; calls += "subscribe" to JSONObject().put("stream_id", streamId)
            return JSONObject().put("stream_id", streamId)
        }
        override suspend fun unsubscribe(streamId: String) { calls += "unsubscribe" to JSONObject().put("stream_id", streamId) }
        override suspend fun request(method: String, params: JSONObject): JSONObject {
            calls += method to params; return response(method, params)
        }
        fun emit(topic: String, payload: JSONObject, id: String = stream) { check(events.tryEmit(MobileRpcClient.Event(topic, payload, id))) }
        val starts get() = calls.count { it.first == "mobile.simulator.stream.start" }
        val inputs get() = calls.filter { it.first.startsWith("mobile.simulator.input.") }
    }
    private fun TestScope.session(capabilities: Set<String> = caps, decode: suspend (LegacySimulatorFrame) -> String? = { "image" }) =
        LegacySimulatorSession(NativeSimulator.read(descriptor(), "w")!!, capabilities, decode, {}, { testScheduler.currentTime })

    @Test fun personalizedStartEnablesOnlySupportedInputAndCleanupStopsOnlyThisPanelAndSubscription() = runTest {
        val endpoint = Endpoint(); val session = session(); val text = LegacySimulatorInput.Text("hello 👋")
        assertFalse(session.input(text)) // Inventory personalization belongs to a previous attachment.
        val running = launch { session.run(endpoint) }; runCurrent()
        assertTrue(session.input(text)); assertTrue(session.input(LegacySimulatorInput.Button(LegacySimulatorButton.HOME))); runCurrent()
        assertEquals(listOf("mobile.simulator.input.text", "mobile.simulator.input.button"), endpoint.inputs.map { it.first })
        endpoint.emit("simulator.state", descriptor().put("supports_keyboard", false)); runCurrent()
        assertFalse(session.input(text)); assertTrue(session.input(LegacySimulatorInput.Button(LegacySimulatorButton.LOCK)))
        session.retire(); assertFalse(session.input(text)); running.cancelAndJoin()
        assertEquals(listOf("mobile.simulator.stream.stop", "unsubscribe"), endpoint.calls.takeLast(2).map { it.first })
        assertEquals(endpoint.stream, endpoint.calls.last().second.getString("stream_id"))
        for ((method, params) in endpoint.calls.filter { it.first.startsWith("mobile.simulator.") }) {
            assertEquals(method, panel, params.getString("panel_id")); assertEquals("w", params.getString("workspace_id"))
            assertFalse(params.has("surface_id"))
        }
    }

    @Test fun lockedStartNeverSendsInputOrStopsSomeOtherOwnersStream() = runTest {
        val endpoint = Endpoint().apply { response = { _, _ -> throw MobileRpcException("locked", "Other owner") } }
        val session = session(); val running = launch { session.run(endpoint) }; runCurrent()
        assertEquals(LegacySimulatorPhase.LOCKED, session.state.value.phase)
        assertFalse(session.input(LegacySimulatorInput.Text("forbidden")))
        running.cancelAndJoin()
        assertFalse(endpoint.calls.any { it.first == "mobile.simulator.stream.stop" })
        assertEquals("unsubscribe", endpoint.calls.last().first)
    }

    @Test fun sharedUpdatesCannotGrantOrCarryControlAcrossAnOwnerChangeAndForeignEventsAreIgnored() = runTest {
        val endpoint = Endpoint(); val session = session(); val running = launch { session.run(endpoint) }; runCurrent()
        try {
            endpoint.emit("simulator.state", descriptor(owned = null)); runCurrent()
            assertEquals(true, session.state.value.descriptor.ownedByCurrentConnection)
            endpoint.emit("simulator.state", descriptor("other", null), "another-subscription")
            endpoint.emit("simulator.state", descriptor("other", null).put("workspace_id", "other"))
            endpoint.emit("simulator.frame", frame().put("panel_id", "other")); runCurrent()
            assertEquals(true, session.state.value.descriptor.ownedByCurrentConnection); assertNull(session.state.value.presentation)
            endpoint.emit("simulator.state", descriptor("other", null)); runCurrent()
            assertEquals(LegacySimulatorPhase.LOCKED, session.state.value.phase)
            assertFalse(session.input(LegacySimulatorInput.Button(LegacySimulatorButton.HOME)))
            endpoint.emit("simulator.state", descriptor(null, null)); runCurrent()
            assertEquals(false, session.state.value.descriptor.ownedByCurrentConnection)
        } finally { running.cancelAndJoin() }
    }

    @Test fun stateDuringStartWinsButSharedSameOwnerStateCanUseItsPersonalizedGrant() = runTest {
        val gate = CompletableDeferred<Unit>(); val endpoint = Endpoint()
        endpoint.response = { method, _ -> if (method.endsWith(".start")) { gate.await(); descriptor() } else JSONObject() }
        val session = session(); val running = launch { session.run(endpoint) }; runCurrent()
        endpoint.emit("simulator.state", descriptor(owned = null).put("title", "New title")); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("New title", session.state.value.descriptor.title)
        assertEquals(true, session.state.value.descriptor.ownedByCurrentConnection)
        running.cancelAndJoin()

        val gate2 = CompletableDeferred<Unit>(); val newer = Endpoint()
        newer.response = { method, _ -> if (method.endsWith(".start")) { gate2.await(); descriptor() } else JSONObject() }
        val other = session(); val next = launch { other.run(newer) }; runCurrent()
        newer.emit("simulator.state", descriptor("other", false)); runCurrent(); gate2.complete(Unit); runCurrent()
        assertEquals("other", other.state.value.descriptor.ownerConnectionId)
        assertEquals(false, other.state.value.descriptor.ownedByCurrentConnection)
        assertEquals(LegacySimulatorPhase.LOCKED, other.state.value.phase)
        next.cancelAndJoin()
    }

    @Test fun keepaliveCapabilityAvoidsFalseStaticScreenStallsAndStalledStateRequiresDecodedFrame() = runTest {
        val oldHost = Endpoint(); val oldSession = session(caps - "simulator.keepalive.v1")
        val old = launch { oldSession.run(oldHost) }; runCurrent(); advanceTimeBy(90_000); runCurrent()
        assertEquals(1, oldHost.starts); old.cancelAndJoin()
        val endpoint = Endpoint(); val session = session(); val running = launch { session.run(endpoint) }; runCurrent()
        try {
            endpoint.emit("simulator.frame", frame(2)); runCurrent()
            advanceTimeBy(10_000); endpoint.emit("simulator.state", descriptor(owned = null)); runCurrent()
            advanceTimeBy(5000); runCurrent(); assertEquals(1, endpoint.starts)
            advanceTimeBy(15_000); runCurrent()
            assertEquals(2, endpoint.starts); assertEquals(LegacySimulatorPhase.STALLED, session.state.value.phase)
            assertFalse(session.input(LegacySimulatorInput.Text("not while stalled")))
            endpoint.emit("simulator.state", descriptor(owned = null)); runCurrent()
            assertEquals(LegacySimulatorPhase.STALLED, session.state.value.phase)
            endpoint.emit("simulator.frame", frame(1)); runCurrent()
            assertEquals(LegacySimulatorPhase.STALLED, session.state.value.phase)
            endpoint.emit("simulator.frame", frame(2)); runCurrent()
            assertEquals(LegacySimulatorPhase.STREAMING, session.state.value.phase)
            assertEquals(2uL, session.state.value.presentation!!.frame.sequence)
        } finally { running.cancelAndJoin() }
    }

    @Test fun decodeFailureRecoveryWorksEvenWithoutKeepalivesAndClearsOnlyWhenImageDecodes() = runTest {
        val endpoint = Endpoint(); var decodes = false
        val session = session(caps - "simulator.keepalive.v1") { if (decodes) "decoded" else null }
        val running = launch { session.run(endpoint) }; runCurrent()
        try {
            repeat(3) { endpoint.emit("simulator.frame", frame(it + 1L)); runCurrent() }
            assertEquals(2, endpoint.starts); assertEquals(LegacySimulatorPhase.STALLED, session.state.value.phase)
            decodes = true; endpoint.emit("simulator.frame", frame(3)); runCurrent()
            assertEquals(LegacySimulatorPhase.STREAMING, session.state.value.phase)
        } finally { running.cancelAndJoin() }
    }

    @Test fun uncertainInputDropsQueuedActionsAndNeverReplaysThemAfterExplicitRefresh() = runTest {
        val gate = CompletableDeferred<Unit>(); val endpoint = Endpoint()
        endpoint.response = { method, _ -> when {
            method.endsWith(".start") -> descriptor()
            method.endsWith(".text") -> { gate.await(); throw MobileRpcOutcomeUnknown() }
            else -> JSONObject()
        } }
        val session = session(); val running = launch { session.run(endpoint) }; runCurrent()
        try {
            assertTrue(session.input(LegacySimulatorInput.Text("once"))); runCurrent()
            assertTrue(session.input(LegacySimulatorInput.Button(LegacySimulatorButton.HOME)))
            gate.complete(Unit); runCurrent()
            assertEquals(1, endpoint.inputs.size); assertTrue(session.state.value.inputPaused)
            assertFalse(session.input(LegacySimulatorInput.Text("must wait")))
            assertTrue(session.refresh()); runCurrent()
            assertFalse(session.state.value.inputPaused); assertEquals(1, endpoint.inputs.size)
            assertTrue(session.input(LegacySimulatorInput.Button(LegacySimulatorButton.LOCK))); runCurrent()
            assertEquals(listOf("mobile.simulator.input.text", "mobile.simulator.input.button"), endpoint.inputs.map { it.first })
        } finally { running.cancelAndJoin() }
    }

    @Test fun inputCapabilityAndQueueBoundsAreEnforcedBeforeAnyOversizedOrQueuedWrite() = runTest {
        val endpoint = Endpoint(); val session = session(caps - "simulator.input.v1")
        val running = launch { session.run(endpoint) }; runCurrent()
        assertFalse(session.input(LegacySimulatorInput.Text("no capability"))); running.cancelAndJoin()
        val next = session(); val job = launch { next.run(endpoint) }; runCurrent()
        assertFalse(next.input(LegacySimulatorInput.Text("x".repeat(65_536))))
        assertTrue(next.state.value.inputPaused); assertTrue(endpoint.inputs.isEmpty()); job.cancelAndJoin()
    }

    @Test fun closedPanelTerminatesCollectorAndCannotBeRefreshedOrReopenedByLateFrames() = runTest {
        val endpoint = Endpoint(); val session = session(); val running = launch { session.run(endpoint) }; runCurrent()
        endpoint.emit("simulator.frame", frame()); runCurrent(); assertNotNull(session.state.value.presentation)
        endpoint.emit("simulator.closed", JSONObject().put("panel_id", panel)); runCurrent()
        assertTrue(running.isCompleted); assertEquals(LegacySimulatorPhase.CLOSED, session.state.value.phase)
        assertNull(session.state.value.presentation); assertFalse(session.refresh())
        endpoint.emit("simulator.frame", frame(2)); runCurrent(); assertNull(session.state.value.presentation)
        assertFalse(endpoint.calls.any { it.first == "mobile.simulator.stream.stop" })
    }

    @Test fun cancellationWaitsForLateStartAcknowledgmentThenStopsBeforeAReplacementCanEnter() = runTest {
        val gate = CompletableDeferred<Unit>(); val endpoint = Endpoint()
        endpoint.response = { method, _ -> if (method.endsWith(".start")) { gate.await(); descriptor() } else JSONObject() }
        val session = session(); var nextEntered = false
        val running = launch { SimulatorTransitions.use("mac", panel) { session.run(endpoint) } }; runCurrent()
        running.cancel(); runCurrent()
        val next = launch { SimulatorTransitions.use("mac", panel) { nextEntered = true } }; runCurrent()
        assertFalse(nextEntered)
        gate.complete(Unit); runCurrent(); running.join(); next.join()
        assertTrue(nextEntered)
        assertEquals(listOf("mobile.simulator.stream.stop", "unsubscribe"), endpoint.calls.takeLast(2).map { it.first })
        assertFalse(session.input(LegacySimulatorInput.Text("late")))
    }
}
