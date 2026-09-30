package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun decodeLegacySimulatorBitmap(frame: LegacySimulatorFrame): Bitmap? = withContext(Dispatchers.Default) {
    runCatching {
        val bytes = Base64.decode(frame.base64, Base64.NO_WRAP)
        require(bytes.size <= LegacySimulatorFrame.MAX_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth == frame.width && bounds.outHeight == frame.height)
        require(bounds.outMimeType == if (frame.format == "jpeg") "image/jpeg" else "image/png")
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid simulator image")
    }.getOrNull()
}
