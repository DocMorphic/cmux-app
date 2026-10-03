package io.github.docmorphic.cmuxapp

import java.time.Instant
import java.util.Base64

internal enum class DiagnosticExitReason { JAVA_CRASH, NATIVE_CRASH, ANR }

/** OS descriptions, raw traces, process names and memory contents never enter exports. */
internal data class DiagnosticExit(val role: DiagnosticRole, val reason: DiagnosticExitReason,
    val timestamp: Long, val pid: Int, val status: Int, val nativeStack: NativeCrashStack? = null) {
    init {
        require(timestamp >= 0 && pid > 0)
        require(nativeStack == null || (reason == DiagnosticExitReason.NATIVE_CRASH && nativeStack.pid == pid))
    }
    val key get() = "$role:$timestamp:$pid"
    fun encode() = "$role,$reason,$timestamp,$pid,$status" +
        (nativeStack?.let { "," + Base64.getEncoder().encodeToString(it.encode()) } ?: "")
    fun line() = "${Instant.ofEpochMilli(timestamp)} $role PROCESS_EXIT reason=$reason pid=$pid status=$status source=ANDROID_HISTORY\n" +
        (nativeStack?.text() ?: if (reason == DiagnosticExitReason.NATIVE_CRASH) "  NATIVE_STACK unavailable\n" else "")
    companion object {
        fun decode(value: String): DiagnosticExit {
            require(value.length <= 48 * 1024)
            val parts = value.split(','); require(parts.size in 5..6)
            return DiagnosticExit(DiagnosticRole.valueOf(parts[0]), DiagnosticExitReason.valueOf(parts[1]),
                parts[2].toLong(), parts[3].toInt(), parts[4].toInt(),
                if (parts.size == 6) NativeCrashStack.decode(Base64.getDecoder().decode(parts[5])) else null)
        }
    }
}
