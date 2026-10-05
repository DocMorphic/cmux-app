package io.github.docmorphic.cmuxapp

import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ArtifactStreamingTextRuntimeTest {
    private fun View.textPreview(): ArtifactTextScrollView? = when (this) {
        is ArtifactTextScrollView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).textPreview() }
        else -> null
    }
    @Test fun progressiveReadingSelectionTailAndRemotePathSurviveUpdates() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val source = File(context.cacheDir, "streaming-${System.nanoTime()}.txt")
        val lines = (1..180).map { "Line $it 日本語 content\n" }
        val prefix = lines.take(60).joinToString("")
        val middle = lines.take(120).joinToString("")
        val full = lines.joinToString(""); source.writeText(full)
        val remote = "/Users/test/日本語 folder/report.txt"
        val output = File(context.getExternalFilesDir(null), "streaming-text").apply { mkdirs() }
        fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 15_000)) { "Missing $selector" }
        fun await(message: String, check: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < until) { if (check()) return; Thread.sleep(75) }
            device.takeScreenshot(File(output, "failure.png")); fail(message)
        }
        fun menu() { find(By.desc("Viewer actions")).click() }
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", source.absolutePath).putExtra("route", ChangesPreviewRoute.TEXT.name)
            .putExtra("mime", "text/plain").putExtra("initial_text", prefix).putExtra("remote_path", remote)).use { scenario ->
            fun read(check: (ArtifactTextScrollView) -> Boolean): Boolean {
                var result = false
                scenario.onActivity { it.window.decorView.textPreview()?.let { view -> result = check(view) } }
                return result
            }
            await("Prefix was not visible") { read { it.textView.text.toString() == prefix } }
            menu(); assertFalse(find(By.text("Copy Contents")).isEnabled); find(By.text("Copy path")).click()
            instrumentation.runOnMainSync {
                assertEquals(remote, context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            }
            scenario.onActivity { activity ->
                val view = activity.window.decorView.textPreview()!!
                view.scrollTo(0, 300)
                Selection.setSelection(view.textView.text as Spannable, 5, 12)
                activity.streaming = activity.streaming!!.copy(document = ArtifactTextDocument(middle))
            }
            await("Append lost reading position or selection") { read {
                it.textView.text.toString() == middle && it.scrollY in 295..305 && it.textView.selectionStart == 5 && it.textView.selectionEnd == 12
            } }
            scenario.recreate()
            await("Recreation lost the progressive document") { read { it.textView.text.toString() == middle && it.textView.selectionStart == 5 } }
            device.takeScreenshot(File(output, "prefix-recreated.png"))
            menu(); find(By.text("Latest")).click()
            await("Latest did not reach loaded bottom") { read { it.scrollY + it.height >= it.textView.height - 3 } }
            scenario.onActivity { it.streaming = it.streaming!!.copy(document = ArtifactTextDocument(full), complete = true) }
            await("Completion did not follow the appended tail") { read {
                it.textView.text.toString() == full && it.scrollY + it.height >= it.textView.height - 3
            } }
            menu(); find(By.text("End")); assertTrue(find(By.text("Copy Contents")).isEnabled)
            find(By.text("Copy Contents")).click()
            await("Complete contents were not copied") {
                var copied = false
                instrumentation.runOnMainSync { copied = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() == full }
                copied
            }
            device.takeScreenshot(File(output, "completed-tail.png"))
        } } finally { source.delete() }
    }
}
