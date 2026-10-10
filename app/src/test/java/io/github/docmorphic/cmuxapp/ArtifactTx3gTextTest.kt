package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class ArtifactTx3gTextTest {
    private fun sample(bytes: ByteArray) = byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
    @Test fun unicodeLinesAndTrailingStyleBoxesAreDecodedAsTextOnly() {
        val value = "CMUX DEUXIÈME\n日本語 👋"
        assertEquals(value, ArtifactTx3gText.decode(sample(value.toByteArray()) + byteArrayOf(0, 0, 0, 8, 115, 116, 121, 108)))
    }
    @Test fun bothUtf16ByteOrdersRespectTheirBom() {
        val value = "字幕 👋"
        assertEquals(value, ArtifactTx3gText.decode(sample(byteArrayOf(-2, -1) + value.toByteArray(Charsets.UTF_16BE))))
        assertEquals(value, ArtifactTx3gText.decode(sample(byteArrayOf(-1, -2) + value.toByteArray(Charsets.UTF_16LE))))
    }
    @Test fun emptyGapSamplesClearTheCue() {
        assertNull(ArtifactTx3gText.decode(byteArrayOf(0, 0)))
        assertNull(ArtifactTx3gText.decode(sample(" \n".toByteArray())))
    }
    @Test fun truncatedLengthAndMalformedEncodingAreRejected() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 3, 65),
            byteArrayOf(0, 2, -61, 40), byteArrayOf(0, 3, -2, -1, 0))) {
            assertThrows(Exception::class.java) { ArtifactTx3gText.decode(bytes) }
        }
    }
    @Test fun unsignedLengthAllowsTheMaximumTextPayload() {
        val text = "x".repeat(65535)
        assertEquals(text, ArtifactTx3gText.decode(sample(text.toByteArray())))
    }
    @Test fun onlyTheNative3gppMimeVariantsUseThisDecoder() {
        assertTrue(ArtifactTx3gText.supports("text/3gpp")); assertTrue(ArtifactTx3gText.supports("text/3gpp-tt"))
        assertFalse(ArtifactTx3gText.supports("text/vtt")); assertFalse(ArtifactTx3gText.supports("audio/mp4a-latm"))
    }
}
