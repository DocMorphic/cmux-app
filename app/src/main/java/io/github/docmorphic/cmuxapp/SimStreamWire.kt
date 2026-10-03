package io.github.docmorphic.cmuxapp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

// cmux SimStreamProtocol/SimStreamWireCodec at 4c5272e9153eca2033c9f40ac749f0c3a5bcb291.
internal enum class SimCodec { HEVC, H264 }
internal enum class SimOrientation { PORTRAIT, LANDSCAPE_LEFT, PORTRAIT_UPSIDE_DOWN, LANDSCAPE_RIGHT }
internal enum class SimHostStatus { PREPARING, STREAMING, DEVICE_UNAVAILABLE, WORKER_CRASHED, FAILED, CLOSED }
internal enum class SimTouchPhase { BEGAN, MOVED, ENDED, CANCELLED }
internal enum class SimButton { HOME, LOCK, SIRI, SIDE_BUTTON, APP_SWITCHER, VOLUME_UP, VOLUME_DOWN, POWER, SWIPE_HOME }

internal sealed interface SimInput {
    data class Touch(val phase: SimTouchPhase, val pointer: Int, val x: Float, val y: Float, val micros: ULong) : SimInput
    data class Text(val text: String) : SimInput
    data class Key(val usage: Int, val down: Boolean) : SimInput
    data class Button(val button: SimButton) : SimInput
}

internal sealed interface SimMessage {
    data class Start(val epoch: ULong, val maximumLongSide: Int, val codecs: List<SimCodec>, val version: Int = 1) : SimMessage
    data class Config(val codec: SimCodec, val width: Long, val height: Long, val scale: Float,
        val orientation: SimOrientation, val nalHeaderLength: Int, val parameterSets: List<ByteArray>) : SimMessage
    data class Frame(val sequence: ULong, val flags: Int, val micros: ULong, val payload: ByteArray) : SimMessage {
        val keyframe get() = flags and 1 != 0
    }
    data class Ack(val sequence: ULong, val receiptMicros: ULong) : SimMessage
    data class Input(val sequence: ULong, val events: List<SimInput>) : SimMessage
    data class State(val status: SimHostStatus, val detail: String) : SimMessage
    data object KeyframeRequest : SimMessage
    data object Stop : SimMessage
}

/** Exact big-endian v1 wire. Semantic decoder limits belong to the video presenter. */
internal object SimStreamWire {
    const val CAPABILITY = "simulator.stream.v2"
    const val MAX_BODY = 8 * 1024 * 1024
    const val MAX_CHUNK = 64 * 1024

    fun encode(message: SimMessage, framed: Boolean = true): ByteArray {
        val w = Writer()
        when (message) {
            is SimMessage.Start -> {
                w.u8(1); w.u8(message.version); w.u64(message.epoch); w.u16(message.maximumLongSide)
                val codecs = message.codecs.take(255); w.u8(codecs.size); codecs.forEach { w.u8(it.ordinal) }
            }
            is SimMessage.Config -> {
                w.u8(2); w.u8(message.codec.ordinal); w.u32(message.width); w.u32(message.height); w.float(message.scale)
                w.u8(message.orientation.ordinal); w.u8(message.nalHeaderLength)
                val sets = message.parameterSets.take(255); w.u8(sets.size); sets.forEach(w::data)
            }
            is SimMessage.Frame -> {
                w.u8(3); w.u64(message.sequence); w.u8(message.flags); w.u64(message.micros); w.data(message.payload)
            }
            is SimMessage.Ack -> { w.u8(4); w.u64(message.sequence); w.u64(message.receiptMicros) }
            is SimMessage.Input -> {
                w.u8(5); w.u64(message.sequence)
                val events = message.events.take(65535); w.u16(events.size)
                events.forEach { event -> when (event) {
                    is SimInput.Touch -> { w.u8(1); w.u8(event.phase.ordinal); w.u8(event.pointer); w.float(event.x); w.float(event.y); w.u64(event.micros) }
                    is SimInput.Text -> { w.u8(2); w.text(event.text) }
                    is SimInput.Key -> { w.u8(3); w.u16(event.usage); w.u8(if (event.down) 1 else 0) }
                    is SimInput.Button -> { w.u8(4); w.u8(event.button.ordinal) }
                } }
            }
            SimMessage.KeyframeRequest -> w.u8(6)
            SimMessage.Stop -> w.u8(7)
            is SimMessage.State -> { w.u8(8); w.u8(message.status.ordinal); w.text(message.detail) }
        }
        val body = w.bytes.toByteArray()
        return if (framed) ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array() else body
    }

    fun decode(body: ByteArray): SimMessage {
        if (body.size > MAX_BODY) throw IOException("Simulator message too large")
        val r = Reader(ByteBuffer.wrap(body))
        val value = when (val type = r.u8()) {
            1 -> {
                val version = r.u8(); val epoch = r.u64(); val pixels = r.u16()
                val codecs = List(r.u8()) { r.u8() }.mapNotNull { SimCodec.entries.getOrNull(it) }
                SimMessage.Start(epoch, pixels, codecs, version)
            }
            2 -> SimMessage.Config(r.enum<SimCodec>(), r.u32(), r.u32(), r.float(), r.enum<SimOrientation>(),
                r.u8(), List(r.u8()) { r.data() })
            3 -> SimMessage.Frame(r.u64(), r.u8(), r.u64(), r.data())
            4 -> SimMessage.Ack(r.u64(), r.u64())
            5 -> {
                val sequence = r.u64()
                SimMessage.Input(sequence, List(r.u16()) {
                    when (r.u8()) {
                        1 -> SimInput.Touch(r.enum<SimTouchPhase>(), r.u8(), r.float(), r.float(), r.u64())
                        2 -> SimInput.Text(r.text())
                        3 -> SimInput.Key(r.u16(), r.u8() != 0)
                        4 -> SimInput.Button(r.enum<SimButton>())
                        else -> throw IOException("Unknown simulator input kind")
                    }
                })
            }
            6 -> SimMessage.KeyframeRequest
            7 -> SimMessage.Stop
            8 -> SimMessage.State(r.enum<SimHostStatus>(), r.text())
            else -> throw IOException("Unknown simulator message type $type")
        }
        if (r.bytes.hasRemaining()) throw IOException("Trailing simulator bytes")
        return value
    }

    private class Writer {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        fun room(count: Int) { require(count >= 0 && count <= MAX_BODY - bytes.size()) { "Simulator message too large" } }
        fun u8(value: Int) { require(value in 0..255); room(1); out.writeByte(value) }
        fun u16(value: Int) { require(value in 0..65535); room(2); out.writeShort(value) }
        fun u32(value: Long) { require(value in 0..0xffffffffL); room(4); out.writeInt(value.toInt()) }
        fun u64(value: ULong) { room(8); out.writeLong(value.toLong()) }
        fun float(value: Float) = u32(value.toRawBits().toLong() and 0xffffffffL)
        fun data(value: ByteArray) { room(4); room(value.size + 4); u32(value.size.toLong()); out.write(value) }
        fun text(value: String) { require(value.length <= MAX_BODY); data(value.toByteArray(Charsets.UTF_8)) }
    }

    private class Reader(val bytes: ByteBuffer) {
        fun need(count: Int) { if (count < 0 || bytes.remaining() < count) throw EOFException("Truncated simulator message") }
        fun u8(): Int { need(1); return bytes.get().toInt() and 255 }
        fun u16(): Int { need(2); return bytes.short.toInt() and 65535 }
        fun u32(): Long { need(4); return bytes.int.toLong() and 0xffffffffL }
        fun u64(): ULong { need(8); return bytes.long.toULong() }
        fun float(): Float = Float.fromBits(u32().toInt())
        inline fun <reified E : Enum<E>> enum(): E = enumValues<E>().getOrNull(u8()) ?: throw IOException("Unknown simulator enum")
        fun data(): ByteArray {
            val count = u32()
            if (count > MAX_BODY) throw IOException("Simulator payload too large")
            need(count.toInt()); return ByteArray(count.toInt()).also(bytes::get)
        }
        fun text(): String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data())).toString()
        } catch (error: java.nio.charset.CharacterCodingException) { throw IOException("Invalid simulator UTF-8", error) }
    }
}

/** At most one declared body is retained; chunk boundaries never become message boundaries. */
internal class SimStreamFramer {
    private val header = ByteArray(4)
    private var headerUsed = 0
    private var body: ByteArray? = null
    private var bodyUsed = 0
    private var failed = false

    suspend fun feed(chunk: ByteArray, consume: suspend (SimMessage) -> Unit) {
        check(!failed) { "Simulator stream decoder retired" }
        try {
            require(chunk.size <= SimStreamWire.MAX_CHUNK)
            var offset = 0
            while (offset < chunk.size) {
                if (body == null) {
                    val count = minOf(4 - headerUsed, chunk.size - offset)
                    chunk.copyInto(header, headerUsed, offset, offset + count); headerUsed += count; offset += count
                    if (headerUsed < 4) continue
                    val length = ByteBuffer.wrap(header).int.toLong() and 0xffffffffL
                    if (length !in 1..SimStreamWire.MAX_BODY.toLong()) throw IOException("Invalid simulator frame length")
                    body = ByteArray(length.toInt()); bodyUsed = 0
                }
                val target = checkNotNull(body)
                val count = minOf(target.size - bodyUsed, chunk.size - offset)
                chunk.copyInto(target, bodyUsed, offset, offset + count); bodyUsed += count; offset += count
                if (bodyUsed == target.size) {
                    body = null; bodyUsed = 0; headerUsed = 0
                    consume(SimStreamWire.decode(target))
                }
            }
        } catch (error: Throwable) { failed = true; body = null; throw error }
    }

    fun finish() {
        if (failed || headerUsed != 0 || body != null) throw EOFException("Truncated simulator stream")
    }
}
