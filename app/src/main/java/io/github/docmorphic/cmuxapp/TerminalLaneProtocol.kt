package io.github.docmorphic.cmuxapp

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** CMXT framing, including opt-in identified input at upstream 204a11d. All cursors are UInt64. */
internal object TerminalLaneProtocol {
    const val MAX_INPUT = 16 * 1024
    const val MAX_OUTPUT = 256 * 1024
    data class Output(val replay: Boolean, val retained: ULong, val sequence: ULong,
                      val current: ULong, val bytes: ByteArray,
                      val inputAcknowledgement: TerminalInputAcknowledgement? = null)

    fun input(text: String, marker: ULong? = null, delivery: TerminalInputDelivery? = null): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(text))
        require(encoded.remaining() in 1..MAX_INPUT) { "Terminal input exceeds lane bounds" }
        val length = encoded.remaining() + (if (marker == null) 0 else 8) + (if (delivery == null) 0 else TerminalInputDelivery.BYTES)
        return ByteBuffer.allocate(4 + length).apply {
            putInt(length or (if (marker == null) 0 else Int.MIN_VALUE) or (if (delivery == null) 0 else 0x40000000))
            marker?.let { putLong(it.toLong()) }
            delivery?.let { put(it.encoded()) }
            put(encoded)
        }.array()
    }

    class Decoder(private val acceptInputAcknowledgements: Boolean = false) {
        private val header = ByteArray(36)
        private var headerCount = 0
        private var payload: ByteArray? = null
        private var payloadCount = 0
        private var replay = false
        private var acknowledgement = false
        private var retained = 0uL
        private var sequence = 0uL
        private var current = 0uL
        val partial get() = headerCount != 0

        fun feed(bytes: ByteArray): List<Output> {
            val frames = mutableListOf<Output>()
            var offset = 0
            while (offset < bytes.size) {
                if (payload == null) {
                    val count = minOf(36 - headerCount, bytes.size - offset)
                    bytes.copyInto(header, headerCount, offset, offset + count)
                    offset += count; headerCount += count
                    if (headerCount < 36) continue
                    val buffer = ByteBuffer.wrap(header)
                    require(buffer.int == 0x434d5854 && buffer.get().toInt() == 1) { "Invalid terminal envelope" }
                    val kind = buffer.get().toInt()
                    require(kind == 1 || kind == 2 || (kind == 3 && acceptInputAcknowledgements))
                    replay = kind == 1; acknowledgement = kind == 3
                    require(buffer.short.toInt() == 0) { "Invalid terminal reserved bits" }
                    retained = buffer.long.toULong(); sequence = buffer.long.toULong(); current = buffer.long.toULong()
                    val length = buffer.int.toUInt().toLong()
                    require(length <= MAX_OUTPUT && retained <= sequence && sequence <= current &&
                        current - sequence == length.toULong()) { "Invalid terminal sequence or payload length" }
                    if (acknowledgement) require(length == TerminalInputAcknowledgement.BYTES.toLong() &&
                        retained == 0uL && sequence == 0uL) { "Invalid input acknowledgement envelope" }
                    payload = ByteArray(length.toInt())
                    payloadCount = 0
                }
                val target = checkNotNull(payload)
                val count = minOf(target.size - payloadCount, bytes.size - offset)
                bytes.copyInto(target, payloadCount, offset, offset + count)
                offset += count; payloadCount += count
                if (payloadCount == target.size) {
                    frames += if (acknowledgement) Output(false, retained, sequence, current, byteArrayOf(), TerminalInputAcknowledgement.decode(target))
                        else Output(replay, retained, sequence, current, target)
                    payload = null; headerCount = 0
                }
            }
            return frames
        }
    }
}
