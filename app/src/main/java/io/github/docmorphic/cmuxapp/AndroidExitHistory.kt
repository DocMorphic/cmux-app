package io.github.docmorphic.cmuxapp

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

internal fun androidExitHistory(context: Context): List<DiagnosticExit> =
    if (Build.VERSION.SDK_INT >= 30) AndroidExitHistory.read(context) else emptyList()

@RequiresApi(30)
private object AndroidExitHistory {
    fun read(context: Context): List<DiagnosticExit> {
        val ownPackage = context.packageName
        var traceReads = 0
        return context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(ownPackage, 0, 32).sortedByDescending { it.timestamp }.mapNotNull { exit ->
                // Android may include bound external services; only our two production processes qualify.
                val role = when (exit.processName) {
                    ownPackage -> DiagnosticRole.APP
                    "$ownPackage:browser" -> DiagnosticRole.BROWSER
                    else -> return@mapNotNull null
                }
                val reason = when (exit.reason) {
                    ApplicationExitInfo.REASON_CRASH -> DiagnosticExitReason.JAVA_CRASH
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> DiagnosticExitReason.NATIVE_CRASH
                    ApplicationExitInfo.REASON_ANR -> DiagnosticExitReason.ANR
                    else -> return@mapNotNull null
                }
                if (exit.timestamp < 0 || exit.pid <= 0) return@mapNotNull null
                // Each stream is bounded by the parser. Limit work per recovery
                // query and keep reason summaries even when OS traces are absent.
                val stack = if (Build.VERSION.SDK_INT >= 31 && reason == DiagnosticExitReason.NATIVE_CRASH && traceReads++ < 4)
                    runCatching { exit.traceInputStream?.use { NativeTombstone.read(it, exit.pid) } }.getOrNull() else null
                val anr = if (reason == DiagnosticExitReason.ANR && traceReads++ < 4)
                    runCatching { exit.traceInputStream?.use { AnrTrace.read(it, exit.pid) } }.getOrNull() else null
                DiagnosticExit(role, reason, exit.timestamp, exit.pid, exit.status, stack, anr)
            }
    }
}
