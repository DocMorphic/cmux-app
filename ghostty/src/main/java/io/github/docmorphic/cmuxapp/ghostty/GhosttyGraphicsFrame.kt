package io.github.docmorphic.cmuxapp.ghostty

import java.nio.ByteBuffer

/** Copies of core-owned pixels and placements, valid after updates and close. */
data class GhosttyGraphicsFrame(
    val generation: Long,
    val scrollOffset: Int,
    val images: Map<Long, Image>,
    val placements: List<Placement>,
) {
    data class Image(val id: Long, val generation: Long, val width: Int, val height: Int,
                     val format: Int, val pixels: ByteArray)
    data class Placement(val imageId: Long, val placementId: Long, val z: Int,
                         val virtual: Boolean, val internal: Boolean, val visible: Boolean,
                         val xOffset: Long, val yOffset: Long, val column: Int, val row: Int,
                         val pixelWidth: Long, val pixelHeight: Long, val gridColumns: Long, val gridRows: Long,
                         val sourceX: Int, val sourceY: Int, val sourceWidth: Int, val sourceHeight: Int,
                         val placeholder: Boolean = false)

    companion object {
        internal fun decode(bytes: ByteArray): GhosttyGraphicsFrame {
            require(bytes.size in 24..16 * 1024 * 1024) { "Invalid graphics snapshot size" }
            val input = ByteBuffer.wrap(bytes)
            fun int(): Int { require(input.remaining() >= 4); return input.int }
            fun uint(): Long = int().toLong() and 0xffffffffL
            fun stamp(): Long = (uint() shl 32) or uint()
            require(int() == 0x47564931) { "Unknown graphics snapshot" }
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
                require(length.toLong() == width.toLong() * height * bpp && length <= input.remaining())
                totalBytes += length
                require(totalBytes <= 10_000_000)
                val pixels = ByteArray(length).also { input.get(it) }
                images[id] = Image(id, imageGeneration, width, height, format, pixels)
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
            return GhosttyGraphicsFrame(generation, offset, images, placements)
        }
    }
}
