package io.github.docmorphic.cmuxapp

import java.time.Instant

internal enum class DiagnosticExitReason { JAVA_CRASH, NATIVE_CRASH, ANR }

/** A fixed schema: OS descriptions, traces, process names and memory contents never enter exports. */
internal data class DiagnosticExit(val role: DiagnosticRole, val reason: DiagnosticExitReason,
    val timestamp: Long, val pid: Int, val status: Int) {
    init { require(timestamp >= 0 && pid > 0) }
    val key get() = "$role:$timestamp:$pid"
    fun encode() = "$role,$reason,$timestamp,$pid,$status"
    fun line() = "${Instant.ofEpochMilli(timestamp)} $role PROCESS_EXIT reason=$reason pid=$pid status=$status source=ANDROID_HISTORY\n"
    companion object {
        fun decode(value: String): DiagnosticExit {
            val parts = value.split(','); require(parts.size == 5)
            return DiagnosticExit(DiagnosticRole.valueOf(parts[0]), DiagnosticExitReason.valueOf(parts[1]),
                parts[2].toLong(), parts[3].toInt(), parts[4].toInt())
        }
    }
}
