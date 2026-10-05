package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class ArtifactTextStreamTest {
    @Test fun splitUtf8CodePointsNeverPublishReplacementCharacters() {
        val bytes = "A日本語🌍\r\nB".toByteArray()
        for (boundary in 1 until bytes.size) {
            val decoder = ArtifactTextStream()
            val first = decoder.append(bytes.copyOfRange(0, boundary), false)!!
            assertFalse(first.text.contains('\uFFFD'))
            val final = decoder.append(bytes.copyOfRange(boundary, bytes.size), true)!!
            assertFalse(decoder.invalid); assertEquals("A日本語🌍\r\nB", final.text)
            assertEquals(2, final.lineCount)
        }
    }
    @Test fun batchesFastChunksButAlwaysPublishesEof() {
        var time = 0L; val decoder = ArtifactTextStream { time }
        assertEquals("a", decoder.append("a".toByteArray(), false)!!.text)
        assertNull(decoder.append("b".toByteArray(), false))
        time = 100_000_000L
        assertEquals("abc", decoder.append("c".toByteArray(), false)!!.text)
        assertEquals("abcd", decoder.append("d".toByteArray(), true)!!.text)
    }
    @Test fun malformedAndTruncatedUtf8AreRejected() {
        for (bytes in listOf(byteArrayOf(0xC0.toByte(), 0xAF.toByte()), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), byteArrayOf(0xE2.toByte(), 0x82.toByte()))) {
            val decoder = ArtifactTextStream()
            assertNull(decoder.append(bytes, true)); assertTrue(decoder.invalid)
            assertNull(decoder.append("valid".toByteArray(), true))
        }
    }
    @Test fun overflowBuffersAndUtf16LineOffsetsRemainExact() {
        val text = "🌍line\n".repeat(20_000)
        val document = ArtifactTextStream().append(text.toByteArray(), true)!!
        assertEquals(text, document.text); assertEquals(20_001, document.lineCount)
        assertEquals(7, document.offset(2))
    }
    @Test fun oneByteChunksAndEmptyTailCompleteCleanly() {
        val decoder = ArtifactTextStream()
        "🌍".toByteArray().forEach { decoder.append(byteArrayOf(it), false) }
        assertEquals("🌍", decoder.append(byteArrayOf(), true)!!.text)
    }
    @Test fun emptyFileHasOneEmptyLine() {
        val document = ArtifactTextStream().append(byteArrayOf(), true)!!
        assertEquals("", document.text); assertEquals(1, document.lineCount)
    }
}
