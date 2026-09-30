package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SimVideoFormatTest {
    private val avc = SimMessage.Config(SimCodec.H264, 64, 96, 2f, SimOrientation.PORTRAIT, 4,
        listOf(byteArrayOf(0x67, 1), byteArrayOf(0x68, 2)))
    private val hevc = avc.copy(codec = SimCodec.HEVC,
        parameterSets = listOf(byteArrayOf(0x40, 1), byteArrayOf(0x42, 1), byteArrayOf(0x44, 1)))

    @Test fun androidCsdUsesSeparateAvcSpsPpsAndCombinedOrderedHevcSets() {
        val a = SimVideoFormat.from(avc.copy(parameterSets = avc.parameterSets.reversed()))
        assertEquals("video/avc", a.mime)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 1), a.codecSpecificData()[0])
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x68, 2), a.codecSpecificData()[1])
        val h = SimVideoFormat.from(hevc.copy(parameterSets = hevc.parameterSets.reversed()))
        assertEquals("video/hevc", h.mime)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x40, 1, 0, 0, 0, 1, 0x42, 1, 0, 0, 0, 1, 0x44, 1), h.codecSpecificData().single())
    }

    @Test fun rawParameterSetsAndReturnedInitializationCannotMutateDecoderConfiguration() {
        val source = avc.copy(parameterSets = avc.parameterSets.map { it.copyOf() })
        val format = SimVideoFormat.from(source)
        source.parameterSets[0][0] = 0
        val copy = format.codecSpecificData(); copy[0][4] = 0
        assertEquals(0x67, format.codecSpecificData()[0][4].toInt())
    }

    @Test fun convertsEverySupportedNalLengthAndMultipleUnitsWithoutChangingPayloadBytes() {
        for (prefix in listOf(1, 2, 4)) {
            val format = SimVideoFormat.from(avc.copy(nalHeaderLength = prefix))
            fun count(n: Int) = ByteArray(prefix).also { it[prefix - 1] = n.toByte() }
            val payload = count(3) + byteArrayOf(0x65, 0, 1) + count(2) + byteArrayOf(0x41, -1)
            val actual = format.accessUnit(payload)
            assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0, 1, 0, 0, 0, 1, 0x41, -1), actual)
        }
    }

    @Test fun truncatedEmptyHugeAndTooManyNalsAreRejectedBeforeCodecSubmission() {
        val format = SimVideoFormat.from(avc)
        for (payload in listOf(byteArrayOf(), byteArrayOf(0, 0), byteArrayOf(0, 0, 0, 0),
            byteArrayOf(-1, -1, -1, -1), byteArrayOf(0, 0, 0, 2, 0x65), byteArrayOf(0, 0, 0, 1, 0x65, 1)))
            assertThrows(IOException::class.java) { format.accessUnit(payload) }
        val tiny = SimVideoFormat.from(avc.copy(nalHeaderLength = 1))
        val excessive = ByteArray((SimVideoFormat.MAX_NALS + 1) * 2) { if (it % 2 == 0) 1 else 0x65 }
        assertThrows(IOException::class.java) { tiny.accessUnit(excessive) }
    }

    @Test fun invalidDimensionsScaleAndNalHeaderCannotCreateDecoder() {
        for (config in listOf(avc.copy(width = 0), avc.copy(width = 8193), avc.copy(width = 8192, height = 8192),
            avc.copy(height = 0xffffffffL), avc.copy(scale = Float.NaN), avc.copy(scale = Float.POSITIVE_INFINITY),
            avc.copy(scale = 0f), avc.copy(nalHeaderLength = 3)))
            assertThrows(IOException::class.java) { SimVideoFormat.from(config) }
    }

    @Test fun missingUnknownOversizedAndMalformedParameterSetsAreRejected() {
        for (config in listOf(avc.copy(parameterSets = emptyList()), avc.copy(parameterSets = avc.parameterSets.take(1)),
            avc.copy(parameterSets = listOf(byteArrayOf(0x65), byteArrayOf(0x68))),
            avc.copy(parameterSets = listOf(byteArrayOf(0xE7.toByte()), byteArrayOf(0x68))),
            hevc.copy(parameterSets = listOf(byteArrayOf(0x40, 0), byteArrayOf(0x42, 1), byteArrayOf(0x44, 1))),
            avc.copy(parameterSets = listOf(ByteArray(1024 * 1024).apply { this[0] = 0x67 }, byteArrayOf(0x68)))))
            assertThrows(IOException::class.java) { SimVideoFormat.from(config) }
    }
}
