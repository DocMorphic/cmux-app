package io.github.docmorphic.cmuxapp

/**
 * cmux mobile RPC framing: a four-byte unsigned big-endian payload length
 * followed by JSON bytes. Matches CMUXMobileCore/MobileSyncProtocol.swift at
 * upstream commit 4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0.
 */
object MobileFrameCodec {
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME_BYTES) { "Mobile frame is too large" }
        val length = payload.size
        return byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte()
        ) + payload
    }
}

/** Incremental decoder that keeps at most one frame's bytes between reads. */
class MobileFrameDecoder(private val maximumFrameBytes: Int = MobileFrameCodec.MAX_FRAME_BYTES) {
    private val header = ByteArray(4)
    private var headerSize = 0
    private var payload: ByteArray? = null
    private var payloadSize = 0

    init { require(maximumFrameBytes in 0..MobileFrameCodec.MAX_FRAME_BYTES) }

    fun feed(bytes: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            if (payload == null) {
                val count = minOf(header.size - headerSize, bytes.size - offset)
                bytes.copyInto(header, headerSize, offset, offset + count)
                headerSize += count
                offset += count
                if (headerSize < header.size) continue
                val length = header.fold(0L) { previous, byte ->
                    (previous shl 8) or (byte.toLong() and 0xff)
                }
                require(length <= maximumFrameBytes) { "Mobile frame is too large" }
                payload = ByteArray(length.toInt())
                payloadSize = 0
                if (length == 0L) {
                    frames += ByteArray(0)
                    reset()
                }
                continue
            }
            val target = payload!!
            val count = minOf(target.size - payloadSize, bytes.size - offset)
            bytes.copyInto(target, payloadSize, offset, offset + count)
            payloadSize += count
            offset += count
            if (payloadSize == target.size) {
                frames += target
                reset()
            }
        }
        return frames
    }

    private fun reset() {
        headerSize = 0
        payload = null
        payloadSize = 0
    }
}
