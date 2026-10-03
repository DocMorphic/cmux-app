package io.github.docmorphic.cmuxapp

import java.io.IOException
import java.nio.ByteBuffer

/** Converts the Mac's raw parameter sets and AVCC/HVCC access units to Android Annex B. */
internal class SimVideoFormat private constructor(val codec: SimCodec, val width: Int, val height: Int,
    val nalHeaderLength: Int, private val initialization: List<ByteArray>) {
    val mime get() = if (codec == SimCodec.H264) "video/avc" else "video/hevc"
    fun codecSpecificData(): List<ByteArray> = initialization.map { it.copyOf() }

    fun accessUnit(payload: ByteArray): ByteArray {
        if (payload.isEmpty() || payload.size > SimStreamWire.MAX_BODY) throw IOException("Invalid simulator video payload size")
        var offset = 0
        var outputSize = 0
        var nals = 0
        fun length(at: Int): Int {
            if (payload.size - at < nalHeaderLength) throw IOException("Truncated simulator NAL header")
            var value = 0L
            for (index in 0 until nalHeaderLength) value = (value shl 8) or (payload[at + index].toLong() and 255)
            if (value == 0L || value > payload.size - at - nalHeaderLength) throw IOException("Invalid simulator NAL length")
            return value.toInt()
        }
        // Validate the complete access unit before allocating or touching the decoder.
        while (offset < payload.size) {
            val count = length(offset)
            if (++nals > MAX_NALS) throw IOException("Too many simulator NAL units")
            if (count > MAX_ACCESS_UNIT - outputSize - 4) throw IOException("Simulator access unit too large")
            outputSize += 4 + count; offset += nalHeaderLength + count
        }
        val output = ByteBuffer.allocate(outputSize)
        offset = 0
        while (offset < payload.size) {
            val count = length(offset); offset += nalHeaderLength
            output.putInt(1); output.put(payload, offset, count); offset += count
        }
        return output.array()
    }

    companion object {
        const val MAX_NALS = 65536
        const val MAX_ACCESS_UNIT = SimStreamWire.MAX_BODY + 3 * MAX_NALS
        private const val MAX_PARAMETER_BYTES = 1024 * 1024

        fun from(config: SimMessage.Config): SimVideoFormat {
            if (config.width !in 1..8192L || config.height !in 1..8192L || config.width * config.height > 16_777_216L)
                throw IOException("Unsupported simulator video dimensions")
            if (!config.scale.isFinite() || config.scale <= 0 || config.scale > 16 || config.nalHeaderLength !in setOf(1, 2, 4))
                throw IOException("Invalid simulator video configuration")
            val expected = if (config.codec == SimCodec.H264) listOf(7, 8) else listOf(32, 33, 34)
            val groups = expected.associateWith { mutableListOf<ByteArray>() }
            var size = 0
            if (config.parameterSets.isEmpty() || config.parameterSets.size > 255) throw IOException("Missing simulator parameter sets")
            config.parameterSets.forEach { bytes ->
                if (bytes.isEmpty() || bytes.size > MAX_PARAMETER_BYTES - size - 4 || bytes[0].toInt() and 0x80 != 0)
                    throw IOException("Invalid simulator parameter set")
                size += bytes.size + 4
                val type = if (config.codec == SimCodec.H264) bytes[0].toInt() and 31 else {
                    if (bytes.size < 2 || bytes[1].toInt() and 7 == 0) throw IOException("Invalid HEVC parameter set")
                    (bytes[0].toInt() ushr 1) and 63
                }
                val group = groups[type] ?: throw IOException("Unexpected simulator parameter set")
                group += bytes.copyOf()
            }
            if (groups.values.any { it.isEmpty() }) throw IOException("Incomplete simulator parameter sets")
            fun annexB(sets: List<ByteArray>): ByteArray {
                val result = ByteBuffer.allocate(sets.sumOf { it.size + 4 })
                sets.forEach { result.putInt(1).put(it) }
                return result.array()
            }
            val csd = if (config.codec == SimCodec.H264) expected.map { annexB(groups.getValue(it)) }
                else listOf(annexB(expected.flatMap { groups.getValue(it) }))
            return SimVideoFormat(config.codec, config.width.toInt(), config.height.toInt(), config.nalHeaderLength, csd)
        }
    }
}
