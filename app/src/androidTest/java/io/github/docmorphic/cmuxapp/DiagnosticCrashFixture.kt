package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File

/** A crash dialog can keep a dying process alive after its exit report is visible. */
internal fun stopDiagnosticBrowser(context: Context, pid: Int) {
    check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic"))
    check(pid != Process.myPid())
    val proc = File("/proc/$pid")
    val name = runCatching { File(proc, "cmdline").inputStream().use { input ->
        val bytes = ByteArray(256); val size = input.read(bytes)
        if (size <= 0) "" else bytes.copyOf(size).toString(Charsets.UTF_8).substringBefore('\u0000')
    } }.getOrNull()
    if (name != "${context.packageName}:browser") return
    Process.killProcess(pid)
    val deadline = SystemClock.elapsedRealtime() + 5000
    while (proc.exists() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
    check(!proc.exists()) { "Disposable browser process did not exit" }
}
