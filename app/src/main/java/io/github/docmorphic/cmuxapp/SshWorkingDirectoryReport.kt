package io.github.docmorphic.cmuxapp

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Passive OSC 7 observer, matching MobileSSHWorkingDirectoryReport.
 * The original bytes still go to Ghostty; no command is injected into the shell. */
internal class SshWorkingDirectoryReport {
    private enum class State { GROUND, ESCAPE, OSC, OSC_ESCAPE }
    private var state = State.GROUND
    private val body = ByteArray(MAX_REPORT_LENGTH)
    private var count = 0
    private var overflowed = false
    var directory: String? = null
        private set

    fun consume(bytes: ByteArray): String? {
        var reported: String? = null
        for (raw in bytes) {
            val byte = raw.toInt() and 255
            when (state) {
                State.GROUND -> if (byte == 27) state = State.ESCAPE
                State.ESCAPE -> when (byte) {
                    93 -> begin()
                    27 -> Unit
                    else -> state = State.GROUND
                }
                State.OSC -> when (byte) {
                    7 -> finish()?.let { reported = it }
                    27 -> state = State.OSC_ESCAPE
                    24, 26 -> { state = State.GROUND; count = 0 }
                    else -> if (!overflowed) {
                        if (count == body.size) { overflowed = true; count = 0 }
                        else body[count++] = raw
                    }
                }
                State.OSC_ESCAPE -> when (byte) {
                    92 -> finish()?.let { reported = it }
                    93 -> begin()
                    else -> state = if (byte == 27) State.ESCAPE else State.GROUND
                }
            }
        }
        reported?.let { directory = it }
        return reported
    }

    private fun begin() { state = State.OSC; count = 0; overflowed = false }
    private fun finish(): String? {
        state = State.GROUND
        val path = if (!overflowed && count > 2 && body[0] == 55.toByte() && body[1] == 59.toByte())
            path(String(body, 2, count - 2, Charsets.UTF_8)) else null
        count = 0
        return path
    }

    companion object {
        const val MAX_REPORT_LENGTH = 4096
        fun path(report: String): String? {
            val encoded = report.startsWith("file://", ignoreCase = true)
            val prefix = when {
                encoded -> 7
                report.startsWith("kitty-shell-cwd://", ignoreCase = true) -> 18
                else -> return null
            }
            val slash = report.indexOf('/', prefix)
            if (slash < 0) return null
            val raw = report.substring(slash)
            val path = if (encoded) percentDecode(raw) ?: raw else raw
            return path.takeIf { it.startsWith('/') && '\u0000' !in it }
        }

        // URLDecoder treats '+' as a form space; OSC 7 paths must preserve it.
        // Like Foundation, malformed escapes/UTF-8 leave the whole raw path.
        private fun percentDecode(raw: String): String? {
            val source = raw.toByteArray(Charsets.UTF_8)
            val target = ByteArray(source.size)
            var read = 0
            var written = 0
            while (read < source.size) {
                if (source[read] == 37.toByte()) {
                    if (read + 2 >= source.size) return null
                    val hi = source[read + 1].toInt().toChar().digitToIntOrNull(16) ?: return null
                    val lo = source[read + 2].toInt().toChar().digitToIntOrNull(16) ?: return null
                    target[written++] = (hi * 16 + lo).toByte(); read += 3
                } else target[written++] = source[read++]
            }
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(target, 0, written)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) { null }
        }
    }
}
