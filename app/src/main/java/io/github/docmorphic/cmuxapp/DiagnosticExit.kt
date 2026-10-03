package io.github.docmorphic.cmuxapp

import java.time.Instant
import java.util.Base64

internal enum class DiagnosticExitReason { JAVA_CRASH, NATIVE_CRASH, ANR }

/** OS descriptions, raw traces, process names and memory contents never enter exports. */
internal data class DiagnosticExit(val role: DiagnosticRole, val reason: DiagnosticExitReason,
    val timestamp: Long, val pid: Int, val status: Int, val nativeStack: NativeCrashStack? = null,
    val anrStack: AnrStack? = null) {
    init {
        require(timestamp >= 0 && pid > 0)
        require(nativeStack == null || (reason == DiagnosticExitReason.NATIVE_CRASH && nativeStack.pid == pid))
        require(anrStack == null || (reason == DiagnosticExitReason.ANR && anrStack.pid == pid))
    }
    val key get() = "$role:$timestamp:$pid"
    fun encode() = "$role,$reason,$timestamp,$pid,$status" +
        (nativeStack?.let { "," + Base64.getEncoder().encodeToString(it.encode()) }
            ?: anrStack?.let { ",anr:" + Base64.getEncoder().encodeToString(it.encode()) } ?: "")
    fun line() = "${Instant.ofEpochMilli(timestamp)} $role PROCESS_EXIT reason=$reason pid=$pid status=$status source=ANDROID_HISTORY\n" +
        (nativeStack?.text() ?: anrStack?.text() ?: when (reason) {
            DiagnosticExitReason.NATIVE_CRASH -> "  NATIVE_STACK unavailable\n"
            DiagnosticExitReason.ANR -> "  ANR_STACK unavailable\n"
            else -> ""
        })
    companion object {
        fun decode(value: String): DiagnosticExit {
            require(value.length <= 48 * 1024)
            val parts = value.split(','); require(parts.size in 5..6)
            val extra = parts.getOrNull(5)
            return DiagnosticExit(DiagnosticRole.valueOf(parts[0]), DiagnosticExitReason.valueOf(parts[1]),
                parts[2].toLong(), parts[3].toInt(), parts[4].toInt(),
                extra?.takeUnless { it.startsWith("anr:") }?.let { NativeCrashStack.decode(Base64.getDecoder().decode(it)) },
                extra?.takeIf { it.startsWith("anr:") }?.let { AnrStack.decode(Base64.getDecoder().decode(it.removePrefix("anr:"))) })
        }
    }
}
