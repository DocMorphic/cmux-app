package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Real OS crash reports from a disposable browser process; never crash the user's phone. */
class AndroidExitHistoryTest {
    private fun verifyCrash(native: Boolean) {
        check(Build.VERSION.SDK_INT >= 30)
        check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic")) { "Emulator required" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val started = CompletableFuture<Int>()
        val reply = Messenger(Handler(Looper.getMainLooper()) { message -> started.complete(message.arg1); true })
        context.startActivity(Intent(context, RoutedBrowserTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("reply", reply))
        val pid = started.get(15, TimeUnit.SECONDS)
        assertNotEquals(Process.myPid(), pid)
        val since = System.currentTimeMillis() - 1000
        val root = File(context.cacheDir, "exit-history-test-${UUID.randomUUID()}")
        try {
            if (native) Process.sendSignal(pid, 6)
            else UiDevice.getInstance(instrumentation).executeShellCommand("am crash $pid")
            val reason = if (native) DiagnosticExitReason.NATIVE_CRASH else DiagnosticExitReason.JAVA_CRASH
            var found: DiagnosticExit? = null
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (found == null && SystemClock.elapsedRealtime() < deadline) {
                found = androidExitHistory(context).firstOrNull { it.pid == pid && it.timestamp >= since && it.reason == reason &&
                    (!native || Build.VERSION.SDK_INT < 31 || it.nativeStack != null) }
                if (found == null) Thread.sleep(200)
            }
            assertNotNull("OS did not retain $reason for browser pid $pid", found)
            assertEquals(DiagnosticRole.BROWSER, found!!.role)
            if (native) {
                assertEquals(6, found.status)
                if (Build.VERSION.SDK_INT >= 31) {
                    val stack = checkNotNull(found.nativeStack)
                    assertEquals(pid, stack.pid); assertTrue(stack.tid > 0)
                    assertTrue(stack.frames.any { it.module == "libc.so" })
                    assertTrue(stack.frames.any { it.buildId.isNotEmpty() })
                    assertTrue(stack.frames.size in 1..64)
                }
            }
            runBlocking {
                val files = DiagnosticFiles(File(root, "logs"), File(root, "exports"), "OS fixture")
                val recorder = DiagnosticRecorder(files, 1, DiagnosticRole.APP, exitHistory = { androidExitHistory(context) })
                fun count(file: File) = ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("cmux-diagnostics/app-events.log"))
                    .bufferedReader().use { it.readLines().count { line -> line.contains("reason=$reason pid=$pid ") } } }
                try {
                    recorder.recoverExits()
                    val exported = recorder.export()
                    assertEquals(1, count(exported))
                    if (native && Build.VERSION.SDK_INT >= 31) {
                        val text = ZipFile(exported).use { zip -> zip.getInputStream(zip.getEntry("cmux-diagnostics/app-events.log"))
                            .bufferedReader().use { it.readText() } }
                        assertTrue(text.contains("NATIVE_STACK tid=${found.nativeStack!!.tid}"))
                        assertTrue(text.contains("module=libc.so rel_pc=0x"))
                        assertFalse(text.contains("/data/")); assertFalse(text.contains("/apex/"))
                        exported.copyTo(File(context.getExternalFilesDir(null), "native-crash-diagnostics.zip"), overwrite = true)
                    }
                    assertEquals(1, count(recorder.export()))
                    recorder.clear()
                    assertEquals(0, count(recorder.export()))
                } finally { recorder.shutdown() }
            }
        } finally {
            stopDiagnosticBrowser(context, pid)
            root.deleteRecursively()
        }
    }
    @Test fun javaBrowserCrashSurvivesProcessDeathAndIsExportedOnce() = verifyCrash(false)
    @Test fun nativeBrowserAbortSurvivesProcessDeathAndIsExportedOnce() = verifyCrash(true)
}
