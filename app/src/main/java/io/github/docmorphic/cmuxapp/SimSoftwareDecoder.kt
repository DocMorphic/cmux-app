package io.github.docmorphic.cmuxapp

import android.view.Surface
import java.io.IOException

/** A bounded software path for codecs that buffer past cmux's two-frame credit window. */
internal class SimSoftwareDecoder(format: SimVideoFormat) : AutoCloseable {
    private var handle = SimVideoNative.create(format.codec.ordinal, format.width, format.height,
        format.codecSpecificData().fold(byteArrayOf()) { all, set -> all + set }).also {
        if (it == 0L) throw IOException("Simulator software decoder unavailable")
    }
    fun render(bytes: ByteArray, surface: Surface, timestamp: Long): Boolean =
        handle != 0L && SimVideoNative.render(handle, bytes, surface, timestamp)
    override fun close() { val old = handle; handle = 0; if (old != 0L) SimVideoNative.destroy(old) }
}

internal object SimVideoNative {
    init { System.loadLibrary("cmux_video") }
    external fun create(codec: Int, width: Int, height: Int, extra: ByteArray): Long
    external fun render(handle: Long, bytes: ByteArray, surface: Surface, timestamp: Long): Boolean
    external fun destroy(handle: Long)
}
