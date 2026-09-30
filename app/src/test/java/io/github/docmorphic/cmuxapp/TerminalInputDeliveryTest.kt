package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.util.UUID

class TerminalInputDeliveryTest {
    private val golden = JSONObject(javaClass.getResource("/terminal-input-delivery-204a11d.json")!!.readText())
    private val surface = UUID.fromString(golden.getString("surface"))
    private val stream = UUID.fromString(golden.getString("stream"))
    private val identity = TerminalInputDelivery(surface, stream, ULong.MAX_VALUE)
    private fun bytes(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun golden(name: String) = bytes(golden.getString(name))
    @Test fun inputAndIdentityMatchCompiledUpstreamSwiftGoldens() {
        val text = golden.getString("text")
        assertArrayEquals(golden("identity"), identity.encoded())
        assertArrayEquals(golden("legacy"), TerminalLaneProtocol.input(text))
        assertArrayEquals(golden("marked"), TerminalLaneProtocol.input(text, ULong.MAX_VALUE))
        assertArrayEquals(golden("identified"), TerminalLaneProtocol.input(text, delivery = identity))
        assertArrayEquals(golden("both"), TerminalLaneProtocol.input(text, ULong.MAX_VALUE, identity))
        assertEquals(16384 + 4 + 8 + 40, TerminalLaneProtocol.input("a".repeat(16384), ULong.MAX_VALUE, identity).size)
    }
    @Test fun allAcknowledgementStatusesMatchSwiftBinaryAndRpcWithUnsignedSequence() {
        val acks = golden.getJSONArray("acknowledgements")
        for (index in 0 until acks.length()) {
            val row = acks.getJSONObject(index)
            val actual = TerminalInputAcknowledgement.decode(bytes(row.getString("body")))
            assertEquals(TerminalInputAcknowledgement.Status.entries[index], actual.status)
            assertEquals(stream, actual.stream); assertEquals(ULong.MAX_VALUE, actual.sequence)
            assertEquals(actual, TerminalInputAcknowledgement.fromRpc(row.getJSONObject("rpc")))
        }
    }
    @Test fun acknowledgementEnvelopeSurvivesEverySplitAndNeverBecomesTerminalText() {
        val ack = golden.getJSONArray("acknowledgements").getJSONObject(0)
        val raw = bytes(ack.getString("envelope"))
        val encoded = terminalEnvelope(bytes = byteArrayOf(65)) + raw + terminalEnvelope(2, 1uL, byteArrayOf(66))
        for (split in 0..encoded.size) {
            val decoder = TerminalLaneProtocol.Decoder(acceptInputAcknowledgements = true)
            val frames = decoder.feed(encoded.copyOfRange(0, split)) + decoder.feed(encoded.copyOfRange(split, encoded.size))
            assertEquals(3, frames.size); assertTrue(frames[0].replay)
            assertEquals(0, frames[1].bytes.size); assertFalse(frames[1].replay)
            assertEquals(ULong.MAX_VALUE, frames[1].inputAcknowledgement!!.sequence)
            assertArrayEquals(byteArrayOf(66), frames[2].bytes); assertFalse(decoder.partial)
        }
        assertThrows(IllegalArgumentException::class.java) { TerminalLaneProtocol.Decoder().feed(raw) }
    }
    @Test fun malformedAcknowledgementsCannotLookLikeSuccessfulUnidentifiedReplies() {
        assertNull(TerminalInputAcknowledgement.fromRpc(JSONObject().put("ok", true)))
        val valid = golden.getJSONArray("acknowledgements").getJSONObject(0).getJSONObject("rpc").toString()
        val malformed = listOf(JSONObject().put("input_ack", JSONObject.NULL),
            JSONObject(valid).apply { getJSONObject("input_ack").put("status", "future") },
            JSONObject(valid).apply { getJSONObject("input_ack").put("sequence", 1) },
            JSONObject(valid).apply { getJSONObject("input_ack").put("sequence", "18446744073709551616") },
            JSONObject(valid).apply { getJSONObject("input_ack").put("sequence", "0") },
            JSONObject(valid).apply { getJSONObject("input_ack").put("stream_id", "1-1-1-1-1") },
            JSONObject(valid).apply { getJSONObject("input_ack").put("status", "gap").put("expected", "0") })
        malformed.forEach { assertThrows(Exception::class.java) { TerminalInputAcknowledgement.fromRpc(it) } }
    }
    @Test fun binaryVersionStatusBoundsAndAckEnvelopeCountersAreValidated() {
        val ack = golden.getJSONArray("acknowledgements").getJSONObject(0)
        val raw = bytes(ack.getString("body"))
        for (body in listOf(raw.copyOf(33), raw + byteArrayOf(0), raw.copyOf().apply { this[0] = 2 }, raw.copyOf().apply { this[1] = 8 }))
            assertThrows(Exception::class.java) { TerminalInputAcknowledgement.decode(body) }
        val envelope = bytes(ack.getString("envelope"))
        for (frame in listOf(envelope.copyOf().apply { ByteBuffer.wrap(this).putLong(8, 1) },
            envelope.copyOf().apply { ByteBuffer.wrap(this).putLong(16, 1).putLong(24, 35) },
            envelope.copyOf().apply { ByteBuffer.wrap(this).putInt(32, 33).putLong(24, 33) }))
            assertThrows(IllegalArgumentException::class.java) { TerminalLaneProtocol.Decoder(true).feed(frame) }
    }
    @Test fun rpcIdentityCannotBeAttachedToAnotherTerminalOrOverwriteAnotherIdentity() {
        val params = identity.addTo(JSONObject().put("surface_id", surface.toString()))
        assertEquals(stream.toString(), params.getString("input_stream_id"))
        assertEquals(ULong.MAX_VALUE.toString(), params.getString("input_stream_seq"))
        assertThrows(IllegalArgumentException::class.java) { identity.addTo(JSONObject().put("surface_id", UUID.randomUUID().toString())) }
        assertThrows(IllegalArgumentException::class.java) { identity.addTo(params) }
        assertThrows(IllegalArgumentException::class.java) { TerminalInputDelivery(surface, stream, 0uL) }
    }
}
