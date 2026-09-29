package io.github.docmorphic.cmuxapp.ghostty

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream

class GhosttyGraphicsTest {
    private fun GhosttyTerminal.write(text: String) = append(text.toByteArray())
    private fun command(options: String, data: ByteArray = byteArrayOf()) =
        "\u001b_G$options;${Base64.getEncoder().encodeToString(data)}\u001b\\"
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(intArrayOf(0xffff0000.toInt(), 0x8000ff00.toInt()), 0, 2, 0, 0, 2, 1)
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
        finally { bitmap.recycle() }
    }

    @Test fun pngCallbackPreservesPixelsAlphaCropOffsetsAndLayer() {
        GhosttyTerminal(20, 4).use { terminal ->
            terminal.resize(20, 4, 10, 20)
            val transmission = command("a=T,f=100,i=7,p=9,c=3,r=2,x=1,y=0,w=1,h=1,X=2,Y=3,z=-1,C=1", png())
            transmission.toByteArray().forEach { terminal.append(byteArrayOf(it)) }
            val frame = terminal.graphicsSnapshot()
            val image = frame.images.getValue(7)
            assertEquals(2, image.width); assertEquals(1, image.height); assertEquals(1, image.format)
            assertArrayEquals(byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -128), image.pixels)
            val placement = frame.placements.single()
            assertEquals(9L, placement.placementId); assertEquals(-1, placement.z)
            assertEquals(1, placement.sourceX); assertEquals(1, placement.sourceWidth)
            assertEquals(2L, placement.xOffset); assertEquals(3L, placement.yOffset)
            assertEquals(30L, placement.pixelWidth); assertEquals(40L, placement.pixelHeight)
            assertTrue(placement.visible); assertFalse(placement.virtual)
            terminal.resize(20, 4, 12, 24)
            assertEquals(36L, terminal.graphicsSnapshot().placements.single().pixelWidth)
            terminal.close()
            assertEquals(8, image.pixels.size)
            assertThrows(IllegalStateException::class.java) { terminal.graphicsSnapshot() }
        }
    }

    @Test fun compressedChunksReplacementAndDeletePreserveOwnedSnapshots() {
        GhosttyTerminal(10, 3).use { terminal ->
            terminal.resize(10, 3, 10, 20)
            val rgb = byteArrayOf(-1, 0, 0, 0, -1, 0)
            val compressed = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(rgb) } }.toByteArray()
            terminal.write(command("a=T,f=24,s=2,v=1,o=z,i=4294967295,p=3,m=1,C=1", compressed.copyOfRange(0, 5)))
            assertTrue(terminal.graphicsSnapshot().images.isEmpty())
            terminal.write(command("m=0", compressed.copyOfRange(5, compressed.size)))
            val before = terminal.graphicsSnapshot()
            val image = before.images.getValue(0xffffffffL)
            assertEquals(0, image.format); assertArrayEquals(rgb, image.pixels)
            terminal.write(command("a=T,f=32,s=2,v=1,i=4294967295,p=3,C=1", byteArrayOf(0, 0, -1, -1, -1, -1, 0, -1)))
            val after = terminal.graphicsSnapshot()
            assertNotEquals(before.generation, after.generation)
            assertNotEquals(image.generation, after.images.getValue(0xffffffffL).generation)
            assertArrayEquals(rgb, image.pixels)
            terminal.write(command("a=d,d=I,i=4294967295"))
            assertTrue(terminal.graphicsSnapshot().images.isEmpty())
            assertTrue(terminal.graphicsSnapshot().placements.isEmpty())
        }
    }

    @Test fun graphicsHistoryAndAlternateScreenDoNotChangeLiveParsing() {
        GhosttyTerminal(10, 3).use { terminal ->
            terminal.resize(10, 3, 10, 20)
            terminal.write(command("a=T,f=32,s=1,v=1,i=1,c=2,r=2,C=1", byteArrayOf(-1, 0, 0, -1)))
            terminal.write("a\r\nb\r\nc\r\nd")
            val live = terminal.graphicsSnapshot()
            assertEquals(-1, live.placements.single().row)
            assertTrue(live.placements.single().visible)
            val history = terminal.graphicsSnapshot(1)
            assertEquals(1, history.scrollOffset); assertEquals(0, history.placements.single().row)
            terminal.write("\r\ne")
            assertEquals(-2, terminal.graphicsSnapshot().placements.single().row)
            assertEquals(0, history.placements.single().row)
            terminal.write("\u001b[?1049h")
            assertTrue(terminal.graphicsSnapshot().images.isEmpty())
            terminal.write(command("a=T,f=24,s=1,v=1,i=1,U=1,c=1,r=1", byteArrayOf(0, -1, 0)))
            val virtual = terminal.graphicsSnapshot()
            assertTrue(virtual.placements.single().virtual)
            assertFalse(virtual.placements.single().visible)
            terminal.write("\u001b[?1049l")
            assertEquals(live.images.getValue(1).generation, terminal.graphicsSnapshot().images.getValue(1).generation)
        }
    }

    @Test fun malformedOversizedPngAndFilesystemMediaAreRejected() {
        assertNull(GhosttyPngDecoder.decode(byteArrayOf(1, 2, 3)))
        // A real PNG header advertises a huge image, rejected before bitmap allocation.
        val huge = png().also { bytes ->
            java.nio.ByteBuffer.wrap(bytes).putInt(16, 10000).putInt(20, 10000)
            val crc = java.util.zip.CRC32().apply { update(bytes, 12, 17) }.value.toInt()
            java.nio.ByteBuffer.wrap(bytes).putInt(29, crc)
        }
        assertNull(GhosttyPngDecoder.decode(huge))
        val file = java.io.File.createTempFile("cmux-graphics", ".png")
        try {
            file.writeBytes(png())
            GhosttyTerminal(10, 3).use { terminal ->
                terminal.write(command("a=T,f=100,i=1", byteArrayOf(1, 2, 3)))
                terminal.write(command("a=T,f=100,t=f,i=2", file.absolutePath.toByteArray()))
                assertTrue(terminal.graphicsSnapshot().images.isEmpty())
                terminal.write(command("a=T,f=100,i=3", png()))
                assertEquals(setOf(3L), terminal.graphicsSnapshot().images.keys)
            }
        } finally { file.delete() }
    }
}
