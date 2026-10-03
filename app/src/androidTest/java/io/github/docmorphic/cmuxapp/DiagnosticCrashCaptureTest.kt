package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** Kills only a disposable emulator browser process, never a physical phone. */
class DiagnosticCrashCaptureTest {
    @Test fun actualUncaughtBrowserCrashKeepsAndroidHandlingAndExportsMetadataOnly() = runBlocking {
        check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic")) { "Emulator required" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recorder = checkNotNull(MobileDiagnostics.recorder)
        val started = CompletableFuture<Pair<Int, Messenger>>()
        val reply = Messenger(Handler(Looper.getMainLooper()) { message -> started.complete(message.arg1 to message.replyTo); true })
        context.startActivity(Intent(context, RoutedBrowserTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("reply", reply))
        val (pid, browser) = started.get(15, TimeUnit.SECONDS)
        assertNotEquals(Process.myPid(), pid)
        try {
            // Clearing while the other process is alive must still permit its next crash record.
            recorder.clear()
            browser.send(Message.obtain(null, 7))
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var exit: DiagnosticExit? = null
            while (exit == null && SystemClock.elapsedRealtime() < deadline) {
                exit = androidExitHistory(context).firstOrNull { it.pid == pid && it.reason == DiagnosticExitReason.JAVA_CRASH }
                if (exit == null) Thread.sleep(100)
            }
            assertNotNull("Android's original handler did not publish the crash", exit)
            repeat(2) {
                val bundle = recorder.export()
                val text = ZipFile(bundle).use { zip ->
                    assertEquals(2, zip.size())
                    zip.getInputStream(zip.getEntry("cmux-diagnostics/app-events.log")).bufferedReader().use { it.readText() }
                }
                assertEquals(1, Regex("BROWSER JAVA_STACK pid=$pid ").findAll(text).count())
                assertTrue(text.contains("RoutedBrowserTestActivity#crashForDiagnostics"))
                assertTrue(text.contains("type=java.io.IOException"))
                assertFalse(text.contains("PRIVATE_DIAGNOSTIC"))
                assertFalse(text.contains("RoutedBrowserTestActivity.kt"))
            }
            recorder.clear()
            ZipFile(recorder.export()).use { zip ->
                val text = zip.getInputStream(zip.getEntry("cmux-diagnostics/app-events.log")).bufferedReader().use { it.readText() }
                assertFalse(text.contains("JAVA_STACK pid=$pid "))
            }
        } finally {
            stopDiagnosticBrowser(context, pid)
        }
    }
}
