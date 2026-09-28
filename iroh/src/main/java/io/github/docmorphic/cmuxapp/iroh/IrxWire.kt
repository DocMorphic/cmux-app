package io.github.docmorphic.cmuxapp.iroh

import org.json.JSONObject
import org.json.JSONTokener
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** cmux IrxProtocol.swift at 4c5272e9153eca2033c9f40ac749f0c3a5bcb291. */
object IrxWire {
    const val ALPN = "cmux/irx/1"
    const val MAX_CONTROL_BYTES = 256 * 1024
    const val ADMISSION_TIMEOUT_MS = 5_000L

    enum class Lane(val wire: String) {
        CONTROL("control"), KEEPALIVE("keepalive"), EVENTS("events"), TERMINAL("terminal"),
        TERMINAL_INPUT("terminal_input"), ARTIFACT("artifact"), SIMULATOR_STREAM("simulator_stream"),
        CONTROL_REPAIR("control_repair")
    }

    data class Descriptor(val lane: Lane, val resource: String? = null, val cursor: ULong? = null,
                          val offset: ULong? = null) {
        fun json(): JSONObject = JSONObject().put("v", 1).put("lane", lane.wire).apply {
            resource?.let { put("resource", it) }
            // UInt64 cursors must remain JSON integers, including above signed Long.MAX_VALUE.
            cursor?.let { put("cursor", BigInteger(it.toString())) }
            offset?.let { put("offset", BigInteger(it.toString())) }
        }
    }

    enum class CloseCode(val wire: String, val terminalForRedial: Boolean) {
        INVALID_GRANT("invalid-grant", true), GRANT_EXPIRED("grant-expired", true), REVOKED("revoked", true),
        IDENTITY_MISMATCH("identity-mismatch", true), MALFORMED_HELLO("malformed-hello", true),
        PROTOCOL_MISMATCH("protocol-mismatch", true), ADMISSION_TIMEOUT("admission-timeout", false),
        SUPERSEDED("superseded", true), USER_REQUESTED("user-requested", true),
        HOST_SHUTDOWN("host-shutdown", false), KEEPALIVE_TIMEOUT("keepalive-timeout", false),
        EXPLICIT_REDIAL("explicit-redial", false);

        fun reason() = "irx:$wire".toByteArray()
        companion object {
            fun parse(cause: String?): CloseCode? = entries.sortedByDescending { it.wire.length }
                .firstOrNull { cause?.contains("irx:${it.wire}") == true }
        }
    }

    class AdmissionRejected(val code: CloseCode, cause: Throwable? = null) : IOException(code.wire, cause)

    data class Admission(val session: String, val keepaliveIntervalMs: Long, val keepaliveDeadlineMs: Long)

    fun admission(value: JSONObject): Admission {
        requireVersion(value)
        val session = value.opt("session") as? String ?: throw IOException("Missing Irx session")
        if (session.isEmpty()) throw IOException("Empty Irx session")
        return Admission(session, integer(value, "keepaliveIntervalMs"), integer(value, "keepaliveDeadlineMs"))
    }

    fun requireVersion(value: JSONObject) {
        if (integer(value, "v") != 1L) throw IOException("Unsupported Irx version")
    }

    private fun integer(value: JSONObject, name: String): Long {
        val number = value.opt(name)
        if (number !is Int && number !is Long) throw IOException("Invalid Irx integer: $name")
        return (number as Number).toLong().also { if (it < 0) throw IOException("Negative Irx integer: $name") }
    }

    fun encode(value: JSONObject): ByteArray {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_CONTROL_BYTES) { "Irx control frame too large" }
        return ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array()
    }

    /** Reads only the frame's bytes, preserving following raw lane bytes in the native stream. */
    suspend fun read(read: suspend (Int) -> ByteArray): JSONObject? {
        suspend fun exact(count: Int, allowCleanEof: Boolean = false): ByteArray? {
            val result = ByteArray(count)
            var position = 0
            while (position < count) {
                val chunk = read(count - position)
                if (chunk.isEmpty()) {
                    if (position == 0 && allowCleanEof) return null
                    throw EOFException("Truncated Irx frame")
                }
                if (chunk.size > count - position) throw IOException("Irx reader exceeded requested byte count")
                chunk.copyInto(result, position)
                position += chunk.size
            }
            return result
        }
        val header = exact(4, allowCleanEof = true) ?: return null
        val length = ByteBuffer.wrap(header).int.toLong() and 0xffffffffL
        if (length == 0L || length > MAX_CONTROL_BYTES) throw IOException("Invalid Irx control length: $length")
        val bytes = checkNotNull(exact(length.toInt()))
        try {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val tokenizer = JSONTokener(decoder.decode(ByteBuffer.wrap(bytes)).toString())
            val value = tokenizer.nextValue() as? JSONObject ?: throw IOException("Irx frame must be an object")
            if (tokenizer.nextClean() != '\u0000') throw IOException("Trailing Irx frame data")
            return value
        } catch (error: Exception) {
            throw IOException("Malformed Irx control frame", error)
        }
    }
}
