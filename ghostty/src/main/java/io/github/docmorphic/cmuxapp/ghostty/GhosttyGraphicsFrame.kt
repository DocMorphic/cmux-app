package io.github.docmorphic.cmuxapp.ghostty

import java.nio.ByteBuffer
import java.util.Collections

/** Copies of core-owned pixels and placements, valid after updates and close. */
data class GhosttyGraphicsFrame(
    val generation: Long,
    val scrollOffset: Int,
    val images: Map<Long, Image>,
    val placements: List<Placement>,
) {
    class Image internal constructor(val id: Long, val generation: Long, val width: Int, val height: Int,
                                     val format: Int, private val data: ByteArray) {
        /** A defensive copy. Cached generations never share mutable pixel buffers with callers. */
        val pixels: ByteArray get() = data.copyOf()
        /** Unsigned read access for the painter, without copying an entire image per crop. */
        fun byteAt(index: Int): Int = data[index].toInt() and 255
    }
    data class Placement(val imageId: Long, val placementId: Long, val z: Int,
                         val virtual: Boolean, val internal: Boolean, val visible: Boolean,
                         val xOffset: Long, val yOffset: Long, val column: Int, val row: Int,
                         val pixelWidth: Long, val pixelHeight: Long, val gridColumns: Long, val gridRows: Long,
                         val sourceX: Int, val sourceY: Int, val sourceWidth: Int, val sourceHeight: Int,
                         val placeholder: Boolean = false)

    companion object {
        internal fun decode(bytes: ByteArray, previous: Map<Long, Image> = emptyMap()): GhosttyGraphicsFrame {
            require(bytes.size in 24..16 * 1024 * 1024) { "Invalid graphics snapshot size" }
            val input = ByteBuffer.wrap(bytes)
            fun int(): Int { require(input.remaining() >= 4); return input.int }
            fun uint(): Long = int().toLong() and 0xffffffffL
            fun stamp(): Long = (uint() shl 32) or uint()
            require(int() == 0x47564932) { "Unknown graphics snapshot" }
            val generation = stamp()
            val offset = int().also { require(it >= 0) }
            val imageCount = int().also { require(it in 0..1024) }
            val placementCount = int().also { require(it in 0..65536) }
            val images = LinkedHashMap<Long, Image>()
            var totalBytes = 0L
            repeat(imageCount) {
                val id = uint().also { require(it != 0L && it !in images) }
                val imageGeneration = stamp().also { require(it != 0L) }
                val width = int().also { require(it in 1..10000) }
                val height = int().also { require(it in 1..10000) }
                val format = int()
                val bpp = when (format) { 0 -> 3; 1 -> 4; 3 -> 2; 4 -> 1; else -> throw IllegalArgumentException("Unknown decoded pixel format") }
                val length = int()
                val expected = width.toLong() * height * bpp
                totalBytes += expected
                require(totalBytes <= 10_000_000)
                images[id] = if (length == -1) {
                    val cached = requireNotNull(previous[id]) { "Missing image generation" }
                    require(cached.generation == imageGeneration && cached.width == width && cached.height == height && cached.format == format) {
                        "Mismatched image generation"
                    }
                    cached
                } else {
                    require(length.toLong() == expected && length <= input.remaining())
                    val pixels = ByteArray(length).also { input.get(it) }
                    Image(id, imageGeneration, width, height, format, pixels)
                }
            }
            val placements = ArrayList<Placement>(placementCount)
            repeat(placementCount) {
                val id = uint()
                val image = requireNotNull(images[id]) { "Missing placement image" }
                val placementId = uint()
                val z = int()
                val flags = int().also { require(it in 0..15) }
                val xOffset = uint(); val yOffset = uint()
                val column = int(); val row = int()
                val pixelWidth = uint(); val pixelHeight = uint()
                val gridColumns = uint(); val gridRows = uint()
                val x = int(); val y = int(); val width = int(); val height = int()
                require(x >= 0 && y >= 0 && width >= 0 && height >= 0)
                if (flags and 8 != 0) {
                    // Ghostty rounds virtual fragment coordinates independently.
                    // A zero extent or one-pixel edge overshoot is sampled with
                    // texture clamping, as in its renderer, rather than cropped.
                    require(x <= image.width && y <= image.height && width <= image.width && height <= image.height)
                    require(x.toLong() + width <= image.width + 1L && y.toLong() + height <= image.height + 1L)
                } else require(x.toLong() + width <= image.width && y.toLong() + height <= image.height)
                placements += Placement(id, placementId, z, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0,
                    xOffset, yOffset, column, row, pixelWidth, pixelHeight, gridColumns, gridRows, x, y, width, height, flags and 8 != 0)
            }
            require(!input.hasRemaining()) { "Trailing graphics data" }
            return GhosttyGraphicsFrame(generation, offset, Collections.unmodifiableMap(images), Collections.unmodifiableList(placements))
        }
    }
}
