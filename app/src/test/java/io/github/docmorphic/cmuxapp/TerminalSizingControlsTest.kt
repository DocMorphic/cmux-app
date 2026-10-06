package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TerminalSizingControlsTest {
    private fun participant(id: String, key: String = id) = TerminalSizeParticipant(id, "fixture", "Fixture", "unknown", id,
        null, SharedTerminalGrid(80, 24), null, true, key)
    private fun presentation(policy: TerminalSizePolicy = TerminalSizePolicy(TerminalSizeMode.SMALLEST),
        rows: List<TerminalSizeParticipant> = listOf(participant("mac"), participant("phone"), participant("browser"))) =
        TerminalSizingPresentation(TerminalSizeState(1, SharedTerminalGrid(67, 24), "smallest", listOf("mac", "phone"), policy, rows), "phone")

    @Test fun policySelectionInitializesRequiredValuesAndPreservesInactiveSettings() {
        val p = presentation()
        assertEquals(listOf("phone", "mac", "browser"), p.rows.map { it.id })
        assertEquals(listOf("phone", "mac", "browser"), p.select(TerminalSizeMode.PRIORITY).priority)
        assertEquals(SharedTerminalGrid(67, 24), p.select(TerminalSizeMode.FIXED).fixed)
        val fixed = TerminalSizePolicy(TerminalSizeMode.FIXED, listOf("offline", "mac"), SharedTerminalGrid(100, 40))
        assertEquals(fixed.copy(mode = TerminalSizeMode.LATEST), presentation(fixed).select(TerminalSizeMode.LATEST))
        assertEquals("mac, This phone", p.ownerLabel)
    }
    @Test fun priorityMoveDeduplicatesKeysAndKeepsDetachedRanks() {
        val p = presentation(TerminalSizePolicy(TerminalSizeMode.PRIORITY, listOf("offline", "browser", "mac", "offline")))
        assertEquals(listOf("browser", "mac", "phone"), p.rows.map { it.id })
        assertEquals(listOf("phone", "browser", "mac", "offline"), p.move("phone", 0).priority)
        assertEquals(listOf("mac", "phone", "browser", "offline"), p.move("browser", 3).priority)
        val duplicate = presentation(TerminalSizePolicy(TerminalSizeMode.PRIORITY),
            listOf(participant("phone", "shared"), participant("mac", "shared"), participant("browser")))
        assertEquals(listOf("browser", "shared"), duplicate.move("browser", 0).priority)
    }
    @Test fun fixedFieldsUseIosBoundsAndRejectInvalidNumbers() {
        assertEquals(SharedTerminalGrid(20, 120), presentation().fixed("1", "999")!!.fixed)
        assertNull(presentation().fixed("", "24")); assertNull(presentation().fixed("NaN", "24"))
        assertNull(presentation().fixed("2147483648", "24"))
    }
    private class Wire : MobileRpcTransport {
        val received = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
        val replies = Channel<ByteArray>(Channel.UNLIMITED)
        var failOn: String? = null
        override suspend fun connect() {}
        override suspend fun read() = replies.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(MobileFrameDecoder().feed(bytes).single().toString(Charsets.UTF_8)); received += request
            val fail = request.getJSONObject("params").optString("participant_id") == failOn
            replies.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", !fail)
                .put(if (fail) "error" else "result", if (fail) JSONObject().put("message", "Fixture refusal") else JSONObject())
                .toString().toByteArray()))
        }
        override fun close() { replies.close() }
    }
    @Test fun wirePreservesViewportGenerationNullOverrideAndCapturedParticipantIds() = runBlocking<Unit> {
        val wire = Wire()
        MobileRpcClient(wire, { "fixture" }).use { client ->
            client.connect(); client.terminalDeviceIdentity = TerminalDeviceIdentity("Pixel 6a", "f2f98081-af0d-4dbe-a590-aad4d516f450")
            val viewport = TerminalViewport(67, 47)
            suspend fun change(action: TerminalSizingAction) = client.changeTerminalSizing("workspace", "surface", action, viewport, 19) { true }
            change(TerminalSizingAction.Counts(false)); change(TerminalSizingAction.Counts(null))
            change(TerminalSizingAction.Policy(presentation().select(TerminalSizeMode.PRIORITY)))
            change(TerminalSizingAction.Disconnect(listOf("mac", "browser")))
            val params = wire.received.map { it.getJSONObject("params") }
            assertFalse(params[0].getBoolean("counts_override")); assertTrue(params[1].has("counts_override")); assertTrue(params[1].isNull("counts_override"))
            assertEquals(19, params[0].getInt("viewport_generation")); assertEquals(67, params[0].getInt("viewport_columns"))
            assertEquals("unknown", params[0].getString("device_kind")); assertEquals("Pixel 6a", params[0].getString("device_name"))
            assertEquals("f2f98081-af0d-4dbe-a590-aad4d516f450", params[0].getString("device_id"))
            assertEquals("priority", params[2].getJSONObject("policy").getString("mode"))
            assertEquals(listOf("mac", "browser"), params.drop(3).map { it.getString("participant_id") })
            assertEquals(1, params.map { it.getString("client_id") }.distinct().size)
            assertTrue(params.all { it.getString("workspace_id") == "workspace" && it.getString("surface_id") == "surface" })
            assertTrue(wire.received.all { !MobileControlResendPolicy.allows(it.getString("method"), it.getJSONObject("params")) })
        }
    }
    @Test fun selectionChangeDuringAuthenticationRejectsMutationBeforeWriting() = runBlocking<Unit> {
        val wire = Wire(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var current = true
        MobileRpcClient(wire, { entered.complete(Unit); release.await(); "fixture" }).use { client ->
            client.connect()
            val operation = async { runCatching { client.changeTerminalSizing("workspace", "surface",
                TerminalSizingAction.Policy(presentation().state.policy), null, 0) { current } } }
            withTimeout(3000) { entered.await() }; current = false; release.complete(Unit)
            assertTrue(withTimeout(3000) { operation.await() }.isFailure); assertTrue(wire.received.isEmpty())
        }
    }
    @Test fun invalidAndDetachedMutationsNeverSendAndDisconnectBatchStopsAfterRefusal() = runBlocking<Unit> {
        val wire = Wire()
        MobileRpcClient(wire, { "fixture" }).use { client ->
            client.connect()
            suspend fun change(action: TerminalSizingAction) = client.changeTerminalSizing("workspace", "surface", action, null, 0) { true }
            assertTrue(runCatching { change(TerminalSizingAction.Disconnect(listOf(client.terminalParticipantId))) }.isFailure)
            assertTrue(runCatching { change(TerminalSizingAction.Counts(true)) }.isFailure)
            assertTrue(runCatching { change(TerminalSizingAction.Policy(TerminalSizePolicy(TerminalSizeMode.FIXED))) }.isFailure)
            client.terminalTrafficAllowed = { false }
            assertTrue(runCatching { change(TerminalSizingAction.Policy(presentation().state.policy)) }.isFailure)
            assertTrue(wire.received.isEmpty())
            client.terminalTrafficAllowed = { true }; wire.failOn = "second"
            assertTrue(runCatching { change(TerminalSizingAction.Disconnect(listOf("first", "second", "third"))) }.isFailure)
            assertEquals(listOf("first", "second"), wire.received.map { it.getJSONObject("params").getString("participant_id") })
        }
    }
}
