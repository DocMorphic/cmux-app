package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import java.io.IOException

class TerminalInputDeliveryRpcTest {
    private class Wire(val failWrites: Boolean = false) : MobileRpcTransport {
        val requests = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        private val responses = Channel<ByteArray>(Channel.UNLIMITED)
        override suspend fun connect() {}
        override suspend fun read() = responses.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(String(bytes.copyOfRange(4, bytes.size), Charsets.UTF_8))
            requests += request
            if (failWrites) throw IOException("Fixture write outcome unknown")
            val parameters = request.getJSONObject("params")
            val result = JSONObject()
            if (parameters.has("input_stream_id")) result.put("input_ack", JSONObject().put("status", "applied")
                .put("stream_id", parameters.getString("input_stream_id"))
                .put("sequence", parameters.getString("input_stream_seq")).put("expected", "0"))
            responses.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id"))
                .put("ok", true).put("result", result).toString().toByteArray()))
        }
        override fun close() { responses.close() }
    }
    @Test fun everyTerminalWritingRpcCarriesTheSameExplicitIdentityFields() = runBlocking<Unit> {
        val wire = Wire(); val client = MobileRpcClient(wire, { "fixture-token" })
        val surface = UUID.randomUUID(); val stream = UUID.randomUUID()
        fun identity(seq: Int) = TerminalInputDelivery(surface, stream, seq.toULong())
        try {
            client.connect()
            val results = listOf(
                client.input("workspace", surface.toString(), "typed", identity(1)),
                client.paste("workspace", surface.toString(), "pasted\n", true, identity(2)),
                client.pasteImage("workspace", surface.toString(), byteArrayOf(1, 2), "png", identity(3)),
                client.terminalClick("workspace", surface.toString(), TerminalGeometry.Cell(3, 4), identity(4)),
                client.terminalScroll("workspace", surface.toString(), TerminalScroll(2.5, 3, 4, 100), identity(5)))
            assertEquals(listOf("terminal.input", "terminal.paste", "terminal.paste_image", "mobile.terminal.mouse", "mobile.terminal.scroll"),
                wire.requests.map { it.getString("method") })
            wire.requests.forEachIndexed { index, request ->
                val parameters = request.getJSONObject("params")
                assertEquals("workspace", parameters.getString("workspace_id")); assertEquals(surface.toString(), parameters.getString("surface_id"))
                assertEquals(stream.toString(), parameters.getString("input_stream_id")); assertEquals((index + 1).toString(), parameters.getString("input_stream_seq"))
                assertEquals((index + 1).toULong(), TerminalInputAcknowledgement.fromRpc(results[index])!!.sequence)
            }
            assertEquals("return", wire.requests[1].getJSONObject("params").getString("submit_key"))
            assertEquals("AQI=", wire.requests[2].getJSONObject("params").getString("image_base64"))
            assertEquals(100, wire.requests[4].getJSONObject("params").getInt("max_scrollback_rows"))
        } finally { client.close() }
    }
    @Test fun legacyCallsKeepTheirExistingWireShapeAndNonUuidFixtureTargets() = runBlocking<Unit> {
        val wire = Wire(); val client = MobileRpcClient(wire, { "fixture-token" })
        try {
            client.connect(); client.input("workspace", "terminal", "hello")
            client.paste("workspace", "terminal", "paste", false)
            client.pasteImage("workspace", "terminal", byteArrayOf(1), "png")
            client.terminalClick("workspace", "terminal", TerminalGeometry.Cell(0, 0))
            client.terminalScroll("workspace", "terminal", TerminalScroll(1.0, 0, 0))
            assertEquals(5, wire.requests.size)
            wire.requests.forEach { assertFalse(it.getJSONObject("params").has("input_stream_id")); assertFalse(it.getJSONObject("params").has("input_stream_seq")) }
        } finally { client.close() }
    }
    @Test fun identityMismatchIsRejectedBeforeAnyRpcWrite() = runBlocking<Unit> {
        val wire = Wire(); val client = MobileRpcClient(wire, { "fixture-token" })
        try {
            client.connect()
            val result = runCatching { client.input("workspace", UUID.randomUUID().toString(), "text", TerminalInputDelivery(UUID.randomUUID(), UUID.randomUUID(), 1uL)) }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException); assertTrue(wire.requests.isEmpty())
        } finally { client.close() }
    }
    @Test fun identityMetadataDoesNotEnableAutomaticControlRequestReplay() = runBlocking<Unit> {
        val wire = Wire(failWrites = true); val client = MobileRpcClient(wire, { "fixture-token" })
        val identity = TerminalInputDelivery(UUID.randomUUID(), UUID.randomUUID(), 1uL)
        try {
            client.connect()
            assertTrue(runCatching { client.input("workspace", identity.surface.toString(), "text", identity) }.isFailure)
            assertEquals(1, wire.requests.size)
        } finally { client.close() }
    }
}
