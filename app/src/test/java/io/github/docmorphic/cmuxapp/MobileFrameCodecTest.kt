package io.github.docmorphic.cmuxapp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MobileFrameCodecTest {
    @Test fun decodesFragmentedAndCoalescedFrames() {
        val first = MobileFrameCodec.encode("""{"id":"one"}""".toByteArray())
        val second = MobileFrameCodec.encode("""{"id":"two"}""".toByteArray())
        val decoder = MobileFrameDecoder()
        assertEquals(0, decoder.feed(first.copyOfRange(0, 2)).size)
        assertEquals(0, decoder.feed(first.copyOfRange(2, 5)).size)
        val frames = decoder.feed(first.copyOfRange(5, first.size) + second)
        assertEquals(2, frames.size)
        assertArrayEquals("""{"id":"one"}""".toByteArray(), frames[0])
        assertArrayEquals("""{"id":"two"}""".toByteArray(), frames[1])
    }

    @Test fun rejectsOverLimitBeforeAllocatingPayload() {
        val decoder = MobileFrameDecoder(maximumFrameBytes = 3)
        assertThrows(IllegalArgumentException::class.java) {
            decoder.feed(byteArrayOf(0, 0, 0, 4))
        }
    }

    @Test fun preservesEmptyFrames() {
        val decoder = MobileFrameDecoder()
        assertEquals(1, decoder.feed(MobileFrameCodec.encode(byteArrayOf())).size)
    }
}
