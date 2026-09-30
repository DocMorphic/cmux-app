package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID

/** Optional upstream 204a11d input identity. A sent identity always keeps the same payload. */
internal data class TerminalInputDelivery(val surface: UUID, val stream: UUID, val sequence: ULong) {
    init { require(sequence > 0uL) }
    fun encoded(): ByteArray = ByteBuffer.allocate(BYTES).apply {
        putUuid(surface); putUuid(stream); putLong(sequence.toLong())
    }.array()
    fun addTo(parameters: JSONObject): JSONObject {
        val target = strictInputUuid(parameters.opt("surface_id"))
        require(target == surface) { "Input delivery terminal mismatch" }
        require(!parameters.has("input_stream_id") && !parameters.has("input_stream_seq"))
        return parameters.put("input_stream_id", stream.toString()).put("input_stream_seq", sequence.toString())
    }
    companion object {
        const val CAPABILITY = "terminal.input.exactly_once.v1"
        const val BYTES = 40
    }
}

internal data class TerminalInputAcknowledgement(val status: Status, val stream: UUID,
    val sequence: ULong, val expected: ULong = 0uL) {
    enum class Status(val wire: Int, val rpc: String) {
        APPLIED(1, "applied"), DUPLICATE(2, "duplicate"), GAP(3, "gap"),
        SURFACE_MISMATCH(4, "surface_mismatch"), TERMINAL_UNAVAILABLE(5, "terminal_unavailable"),
        BUSY(6, "busy"), REJECTED(7, "rejected")
    }
    init { require(sequence > 0uL && (status != Status.GAP || expected > 0uL)) { "Invalid input acknowledgement sequence" } }
    companion object {
        const val BYTES = 34
        fun decode(bytes: ByteArray): TerminalInputAcknowledgement {
            require(bytes.size == BYTES) { "Invalid input acknowledgement length" }
            val buffer = ByteBuffer.wrap(bytes)
            require(buffer.get().toInt() == 1) { "Unknown input acknowledgement version" }
            val kind = buffer.get().toInt()
            val status = Status.entries.singleOrNull { it.wire == kind } ?: error("Unknown input acknowledgement status")
            return TerminalInputAcknowledgement(status, buffer.getUuid(), buffer.long.toULong(), buffer.long.toULong())
        }
        /** Missing identity may mean a host downgrade. Malformed identity must never mean success. */
        fun fromRpc(response: JSONObject): TerminalInputAcknowledgement? {
            if (!response.has("input_ack")) return null
            val body = response.opt("input_ack") as? JSONObject ?: error("Invalid input acknowledgement")
            val status = Status.entries.singleOrNull { it.rpc == body.opt("status") } ?: error("Unknown input acknowledgement status")
            fun integer(name: String): ULong {
                val value = body.opt(name) as? String ?: error("Invalid input acknowledgement sequence")
                require(value.isNotEmpty() && value.length <= 20 && value.all { it in '0'..'9' })
                return value.toULongOrNull() ?: error("Input acknowledgement sequence overflow")
            }
            return TerminalInputAcknowledgement(status, strictInputUuid(body.opt("stream_id")), integer("sequence"),
                if (body.has("expected")) integer("expected") else 0uL)
        }
    }
}

private fun ByteBuffer.putUuid(value: UUID) { putLong(value.mostSignificantBits); putLong(value.leastSignificantBits) }
private fun ByteBuffer.getUuid() = UUID(long, long)
private fun strictInputUuid(value: Any?): UUID {
    require(value is String && value.length == 36) { "Invalid input identity" }
    return UUID.fromString(value).also { require(it.toString().equals(value, ignoreCase = true)) }
}

internal fun JSONObject.withInputDelivery(delivery: TerminalInputDelivery?): JSONObject = delivery?.addTo(this) ?: this
