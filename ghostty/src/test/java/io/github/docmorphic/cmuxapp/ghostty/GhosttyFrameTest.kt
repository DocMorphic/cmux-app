package io.github.docmorphic.cmuxapp.ghostty

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

class GhosttyFrameTest {
    private fun frame(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            listOf(0x47565431, 2, 2, 0xeeeeee, 0x111111, -1, 16, 0, 0, 1, 0, 0, 2).forEach(out::writeInt)
            out.writeInt(1)
            listOf(0, 2, -1, 0x123456, -1, 3, 3, 3).forEach(out::writeInt)
            out.write("中".toByteArray())
            out.writeInt(0)
        }
    }.toByteArray()

    @Test fun decodesOwnedWideCellAndAllMetadata() {
        val bytes = frame()
        val frame = GhosttyFrame.decode(bytes)
        bytes.fill(0)
        assertEquals("中", frame.lines[0][0].text)
        assertEquals(2, frame.lines[0][0].width)
        assertTrue(frame.lines[0][0].bold && frame.lines[0][0].italic)
        assertTrue(frame.cursorVisible)
        assertEquals(-1, frame.cursorColor)
    }

    @Test fun rejectsEveryTruncationTrailingBytesAndUnknownVersion() {
        val bytes = frame()
        for (end in bytes.indices) assertThrows(IllegalArgumentException::class.java) {
            GhosttyFrame.decode(bytes.copyOf(end))
        }
        assertThrows(IllegalArgumentException::class.java) { GhosttyFrame.decode(bytes + 0) }
        bytes[0] = 0
        assertThrows(IllegalArgumentException::class.java) { GhosttyFrame.decode(bytes) }
    }

    @Test fun rejectsOutOfRangeDimensionsColorsWidthsAndLengths() {
        listOf(4 to 1001, 12 to -2, 40 to -1, 52 to 3, 60 to 3, 84 to 16385).forEach { (offset, value) ->
            val bytes = frame().also { ByteBuffer.wrap(it).putInt(offset, value) }
            assertThrows("offset=$offset", IllegalArgumentException::class.java) { GhosttyFrame.decode(bytes) }
        }
    }
}
