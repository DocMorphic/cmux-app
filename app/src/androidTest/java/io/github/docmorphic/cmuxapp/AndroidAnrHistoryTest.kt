package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.os.*
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Real input-dispatch ANR; deliberately limited to the disposable emulator browser process. */
class AndroidAnrHistoryTest {
    @Test fun blockedMainAndMonitorOwnerSurviveAnrDeathAsFilteredExport() = runBlocking {
        check(Build.VERSION.SDK_INT >= 30)
        check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic")) { "Emulator required" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val started = CompletableFuture<Pair<Int, Messenger>>()
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            started.complete(message.arg1 to message.replyTo); true
        })
        context.startActivity(Intent(context, RoutedBrowserTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("reply", reply))
        val (pid, browser) = started.get(15, TimeUnit.SECONDS)
        assertNotEquals(Process.myPid(), pid)
        val root = File(context.cacheDir, "anr-history-${UUID.randomUUID()}")
        val since = System.currentTimeMillis() - 1000
        try {
            browser.send(Message.obtain(null, 8))
            Thread.sleep(1500)
            // Asynchronous injection permits Android's own input timeout/dialog handling.
            val eventTime = SystemClock.uptimeMillis()
            assertTrue(instrumentation.uiAutomation.injectInputEvent(
                KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0), false))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(
                KeyEvent(eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0), false))
            var found: DiagnosticExit? = null
            val deadline = SystemClock.elapsedRealtime() + 75_000
            while (found == null && SystemClock.elapsedRealtime() < deadline) {
                device.findObject(By.res("android", "aerr_close"))?.click()
                found = androidExitHistory(context).firstOrNull {
                    it.pid == pid && it.timestamp >= since && it.reason == DiagnosticExitReason.ANR && it.anrStack != null
                }
                if (found == null) Thread.sleep(250)
            }
            assertNotNull("Android did not retain ANR code frames for the disposable browser", found)
            assertEquals(DiagnosticRole.BROWSER, found!!.role)
            val stack = checkNotNull(found.anrStack)
            val main = stack.threads.single { it.main }
            assertEquals(AnrThreadState.BLOCKED, main.state)
            assertTrue(main.frames.any { it.method == "blockForDiagnostics" })
            val owner = stack.threads.single { it.tid == main.owner }
            assertTrue(owner.frames.any { it.method == "holdMonitorForDiagnostics" })
            val files = DiagnosticFiles(File(root, "logs"), File(root, "exports"), "ANR fixture")
            val recorder = DiagnosticRecorder(files, 1, DiagnosticRole.APP, exitHistory = { androidExitHistory(context) })
            fun log(file: File) = ZipFile(file).use { zip ->
                assertEquals(2, zip.size())
                zip.getInputStream(zip.getEntry("cmux-diagnostics/app-events.log")).bufferedReader().use { it.readText() }
            }
            try {
                val export = recorder.export()
                val text = log(export)
                assertEquals(1, Regex("ANR_STACK pid=$pid ").findAll(text).count())
                assertTrue(text.contains("#blockForDiagnostics")); assertTrue(text.contains("#holdMonitorForDiagnostics"))
                listOf("PRIVATE_ANR", "/data/", "/apex/", ".kt", "Cmd line:", "held mutexes", "waiting to lock <").forEach {
                    assertFalse("Unfiltered trace data: $it", text.contains(it))
                }
                export.copyTo(File(context.getExternalFilesDir(null), "anr-diagnostics.zip"), overwrite = true)
                assertEquals(1, Regex("ANR_STACK pid=$pid ").findAll(log(recorder.export())).count())
                recorder.clear(); assertFalse(log(recorder.export()).contains("ANR_STACK pid=$pid "))
            } finally { recorder.shutdown() }
        } finally {
            stopDiagnosticBrowser(context, pid)
            root.deleteRecursively()
        }
    }
}
