package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

internal fun terminalEnvelope(kind: Int = 1, start: ULong = 0uL, bytes: ByteArray = byteArrayOf(),
                              retained: ULong = start): ByteArray = ByteBuffer.allocate(36 + bytes.size)
    .putInt(0x434d5854).put(1).put(kind.toByte()).putShort(0).putLong(retained.toLong())
    .putLong(start.toLong()).putLong((start + bytes.size.toULong()).toLong()).putInt(bytes.size).put(bytes).array()

class TerminalLaneProtocolTest {
    @Test fun outputSurvivesEverySplitAndPreservesUnsignedCursors() {
        val start = Long.MAX_VALUE.toULong() + 5u
        val bytes = "A😀é".toByteArray()
        val encoded = terminalEnvelope(start = start, bytes = bytes) + terminalEnvelope(2, start + bytes.size.toULong())
        for (split in 0..encoded.size) {
            val decoder = TerminalLaneProtocol.Decoder()
            val frames = decoder.feed(encoded.copyOfRange(0, split)) + decoder.feed(encoded.copyOfRange(split, encoded.size))
            assertEquals(2, frames.size); assertEquals(start, frames[0].sequence)
            assertArrayEquals(bytes, frames[0].bytes); assertFalse(frames[1].replay); assertFalse(decoder.partial)
        }
        val decoder = TerminalLaneProtocol.Decoder()
        assertTrue(decoder.feed(encoded.copyOfRange(0, 35)).isEmpty()); assertTrue(decoder.partial)
    }

    @Test fun invalidHeaderAndSequenceBoundsFailBeforePayloadAllocation() {
        val base = terminalEnvelope()
        val corrupt = listOf(
            base.copyOf().apply { this[0] = 0 }, base.copyOf().apply { this[4] = 2 },
            base.copyOf().apply { this[5] = 3 }, base.copyOf().apply { this[7] = 1 },
            base.copyOf().apply { ByteBuffer.wrap(this).putInt(32, 262145) },
            base.copyOf().apply { ByteBuffer.wrap(this).putInt(32, -1) },
            base.copyOf().apply { ByteBuffer.wrap(this).putLong(8, 1) },
            base.copyOf().apply { ByteBuffer.wrap(this).putLong(24, 1) }
        )
        corrupt.forEach { frame -> assertThrows(IllegalArgumentException::class.java) { TerminalLaneProtocol.Decoder().feed(frame) } }
    }

    @Test fun inputUsesExactUtf8AndOptionalUnsignedMeasurementMarker() {
        val text = "\u001b[A😀é\r"
        val bytes = text.toByteArray()
        assertArrayEquals(ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array(), TerminalLaneProtocol.input(text))
        val marked = ByteBuffer.wrap(TerminalLaneProtocol.input(text, ULong.MAX_VALUE))
        assertEquals(Int.MIN_VALUE or (bytes.size + 8), marked.int); assertEquals(-1L, marked.long)
        assertArrayEquals(bytes, ByteArray(marked.remaining()).also { marked.get(it) })
        assertEquals(16388, TerminalLaneProtocol.input("a".repeat(16384)).size)
        listOf("", "😀".repeat(4097)).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { TerminalLaneProtocol.input(value) }
        }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { TerminalLaneProtocol.input("\ud800") }
    }
}
