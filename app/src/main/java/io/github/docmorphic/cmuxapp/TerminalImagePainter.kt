package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import io.github.docmorphic.cmuxapp.ghostty.GhosttyGraphicsFrame

/** Owns only copied pixels. Generation stamps are unique across native owners/screens. */
internal class TerminalImagePainter : AutoCloseable {
    enum class Layer { BELOW_BACKGROUND, BELOW_TEXT, ABOVE_TEXT }
    private data class Crop(val generation: Long, val x: Int, val y: Int, val width: Int, val height: Int)
    private data class Cached(val bitmap: Bitmap, val shader: BitmapShader)
    private val bitmaps = LinkedHashMap<Crop, Cached>(16, 0.75f, true)
    private var bitmapBytes = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var placements = emptyList<GhosttyGraphicsFrame.Placement>()
    private var snapshot: TerminalGraphicsDisplay.Snapshot? = null
    private val source = Rect()
    private val target = RectF()
    private val textureSource = RectF()
    private val textureMatrix = Matrix()

    fun prepare(next: TerminalGraphicsDisplay.Snapshot?) {
        snapshot = next
        val generations = next?.frame?.images?.values?.map { it.generation }?.toSet().orEmpty()
        // Drop references instead of recycling bitmaps still held by a hardware display list.
        val iterator = bitmaps.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.generation !in generations) { bitmapBytes -= entry.value.bitmap.byteCount; iterator.remove() }
        }
        placements = next?.frame?.placements.orEmpty().filterNot { it.virtual }
            .sortedWith(compareBy({ it.z }, { it.imageId }, { it.placementId }))
    }

    fun draw(canvas: Canvas, layer: Layer, originX: Float, originY: Float,
             cellWidth: Float, cellHeight: Float, rows: Int, extraBottomRow: Boolean) {
        val current = snapshot ?: return
        val xScale = cellWidth / current.cellWidth
        val yScale = cellHeight / current.cellHeight
        for (placement in placements) {
            val placementLayer = when {
                placement.z < Int.MIN_VALUE / 2 -> Layer.BELOW_BACKGROUND
                placement.z < 0 -> Layer.BELOW_TEXT
                else -> Layer.ABOVE_TEXT
            }
            if (placementLayer != layer || (!placement.visible && !(extraBottomRow && placement.row == rows)) ||
                (!placement.placeholder && (placement.sourceWidth == 0 || placement.sourceHeight == 0)) ||
                placement.pixelWidth == 0L || placement.pixelHeight == 0L) continue
            val image = current.frame.images[placement.imageId] ?: continue
            val crop = if (placement.placeholder) Crop(image.generation, 0, 0, image.width, image.height)
                else Crop(image.generation, placement.sourceX, placement.sourceY, placement.sourceWidth, placement.sourceHeight)
            val cached = bitmaps[crop] ?: run {
                val size = crop.width.toLong() * crop.height * 4
                // Native storage is at most 10 MB; grayscale expands to at most
                // 40 MB of ARGB. Bound cached crops to that same worst case.
                while (bitmapBytes + size > 40_000_000 && bitmaps.isNotEmpty()) {
                    val iterator = bitmaps.iterator()
                    bitmapBytes -= iterator.next().value.bitmap.byteCount; iterator.remove()
                }
                val bitmap = bitmap(image, crop)
                Cached(bitmap, BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)).also {
                    bitmaps[crop] = it; bitmapBytes += bitmap.byteCount
                }
            }
            val bitmap = cached.bitmap
            source.set(0, 0, bitmap.width, bitmap.height)
            val x = originX + placement.column * cellWidth + placement.xOffset * xScale
            val y = originY + placement.row * cellHeight + placement.yOffset * yScale
            target.set(x, y, x + placement.pixelWidth * xScale, y + placement.pixelHeight * yScale)
            if (placement.placeholder) {
                // Use Ghostty's resolved fragment coordinates and edge-clamped
                // filtering, including tiny images split across many cells.
                // A sub-texel epsilon represents a constant coordinate without
                // passing a singular transform to Android's bitmap shader.
                textureSource.set(placement.sourceX.toFloat(), placement.sourceY.toFloat(),
                    placement.sourceX + maxOf(placement.sourceWidth.toFloat(), 1f / 1024),
                    placement.sourceY + maxOf(placement.sourceHeight.toFloat(), 1f / 1024))
                if (textureMatrix.setRectToRect(textureSource, target, Matrix.ScaleToFit.FILL)) {
                    cached.shader.setLocalMatrix(textureMatrix)
                    paint.shader = cached.shader
                    try { canvas.drawRect(target, paint) } finally { paint.shader = null }
                }
            } else canvas.drawBitmap(bitmap, source, target, paint)
        }
    }

    // Crop before scaling. Canvas filtering of a source subrect can otherwise
    // sample neighboring pixels outside the requested Kitty source rectangle.
    private fun bitmap(image: GhosttyGraphicsFrame.Image, crop: Crop): Bitmap {
        val bytes = image.pixels
        var index = 0
        fun byte() = bytes[index++].toInt() and 255
        val bpp = when (image.format) { 0 -> 3; 1 -> 4; 3 -> 2; 4 -> 1; else -> error("Unexpected pixel format") }
        val colors = IntArray(crop.width * crop.height) { position ->
            index = ((crop.y + position / crop.width) * image.width + crop.x + position % crop.width) * bpp
            val r = byte()
            val g: Int; val b: Int; val a: Int
            when (image.format) {
                0 -> { g = byte(); b = byte(); a = 255 }
                1 -> { g = byte(); b = byte(); a = byte() }
                3 -> { g = r; b = r; a = byte() }
                4 -> { g = r; b = r; a = 255 }
                else -> error("Unexpected decoded image format")
            }
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(colors, crop.width, crop.height, Bitmap.Config.ARGB_8888)
    }

    override fun close() { bitmaps.clear(); bitmapBytes = 0; placements = emptyList(); snapshot = null }
}
