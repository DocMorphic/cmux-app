package io.github.docmorphic.cmuxapp.ghostty

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

class GhosttyGraphicsFrameTest {
    private fun frame() = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
        listOf(0x47564932, 0, 2, 1, 1, 1).forEach(out::writeInt)
        listOf(-1, 0, 1, 1, 1, 1, 4).forEach(out::writeInt)
        out.write(byteArrayOf(-1, 0, 0, -128))
        listOf(-1, 0, -2, 6, 2, 3, 0, -1, 10, 20, 1, 1, 0, 0, 1, 1).forEach(out::writeInt)
    } }.toByteArray()

    @Test fun copiesUnsignedImageIdentityPixelsAndSignedPlacement() {
        val bytes = frame()
        val graphics = GhosttyGraphicsFrame.decode(bytes)
        bytes.fill(0)
        assertEquals(0xffffffffL, graphics.images.values.single().id)
        assertArrayEquals(byteArrayOf(-1, 0, 0, -128), graphics.images.values.single().pixels)
        val p = graphics.placements.single()
        assertEquals(-1, p.row); assertEquals(-2, p.z); assertTrue(p.internal && p.visible)
    }
    private fun reference(): ByteArray = frame().let { original ->
        (original.copyOfRange(0, 52) + original.copyOfRange(56, original.size)).also {
            ByteBuffer.wrap(it).putInt(48, -1)
        }
    }
    @Test fun referencesReuseImmutableImagesAndRejectMissingOrMismatchedMetadata() {
        val before = GhosttyGraphicsFrame.decode(frame())
        val reused = GhosttyGraphicsFrame.decode(reference(), before.images)
        assertSame(before.images.values.single(), reused.images.values.single())
        reused.images.values.single().pixels.fill(0)
        assertEquals(255, reused.images.values.single().byteAt(0))
        assertArrayEquals(byteArrayOf(-1, 0, 0, -128), before.images.values.single().pixels)
        assertThrows(UnsupportedOperationException::class.java) { (before.images as MutableMap).clear() }
        assertThrows(IllegalArgumentException::class.java) { GhosttyGraphicsFrame.decode(reference()) }
        listOf(32 to 2, 36 to 2, 44 to 0, 48 to -2).forEach { (offset, value) ->
            val invalid = reference().also { ByteBuffer.wrap(it).putInt(offset, value) }
            assertThrows("offset=$offset", IllegalArgumentException::class.java) { GhosttyGraphicsFrame.decode(invalid, before.images) }
        }
    }
    @Test fun referencePacketsRejectTruncationAndDoNotRetainDeletedImages() {
        val before = GhosttyGraphicsFrame.decode(frame())
        val bytes = reference()
        bytes.indices.forEach { end -> assertThrows(IllegalArgumentException::class.java) {
            GhosttyGraphicsFrame.decode(bytes.copyOf(end), before.images)
        } }
        val empty = ByteBuffer.allocate(24).putInt(0x47564932).putLong(4).putInt(0).putInt(0).putInt(0).array()
        assertTrue(GhosttyGraphicsFrame.decode(empty, before.images).images.isEmpty())
    }
    @Test fun rejectsTruncationsTrailingDataAndMissingImages() {
        val bytes = frame()
        bytes.indices.forEach { end -> assertThrows(IllegalArgumentException::class.java) { GhosttyGraphicsFrame.decode(bytes.copyOf(end)) } }
        assertThrows(IllegalArgumentException::class.java) { GhosttyGraphicsFrame.decode(bytes + 0) }
        listOf(0 to 0, 12 to -1, 16 to 1025, 20 to 65537, 36 to 10001, 48 to Int.MAX_VALUE, 56 to 3, 104 to 2).forEach { (offset, value) ->
            val invalid = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) }
            assertThrows("offset=$offset", IllegalArgumentException::class.java) { GhosttyGraphicsFrame.decode(invalid) }
        }
    }
}
