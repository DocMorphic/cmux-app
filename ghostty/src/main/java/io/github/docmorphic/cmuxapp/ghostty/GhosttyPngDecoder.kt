package io.github.docmorphic.cmuxapp.ghostty

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer

/** Called by the native core. Decode only bounded PNG data, never paths or URIs. */
internal object GhosttyPngDecoder {
    private const val MAX_BYTES = 10_000_000
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    @JvmStatic fun decode(data: ByteArray): ByteArray? {
        if (data.size !in 8..MAX_BYTES || !signature.indices.all { data[it] == signature[it] }) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width !in 1..10000 || height !in 1..10000 || width.toLong() * height * 4 > MAX_BYTES) return null
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        options.inScaled = false
        options.inPremultiplied = false
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null
        try {
            if (bitmap.width != width || bitmap.height != height) return null
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val result = ByteBuffer.allocate(8 + pixels.size * 4).putInt(width).putInt(height)
            for (argb in pixels) result.putInt((argb shl 8) or (argb ushr 24))
            return result.array()
        } finally { bitmap.recycle() }
    }
}
