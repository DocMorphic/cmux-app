package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.media.MediaPlayer
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ArtifactPreviewLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun guard() = check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
    private fun await(message: String, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) { if (test()) return; Thread.sleep(100) }
        fail(message)
    }
    private fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 15_000)) { "Missing $selector" }
    private fun action(label: String) { find(By.desc("Viewer actions")).click(); find(By.text(label)).click() }
    private fun findView(view: View, type: Class<out View>): View? = when {
        type.isInstance(view) -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findView(view.getChildAt(it), type) }
        else -> null
    }
    private inline fun <reified T : View> View.child(): T? = findView(this, T::class.java) as T?
    private fun <T> ActivityScenario<ArtifactPreviewTestActivity>.read(block: (ArtifactPreviewTestActivity) -> T): T {
        var value: T? = null; onActivity { value = block(it) }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun launch(file: File, route: ChangesPreviewRoute, mime: String) =
        ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", file.absolutePath).putExtra("route", route.name).putExtra("mime", mime))
    private fun screenshot(name: String) {
        val directory = File(context.getExternalFilesDir(null), "preview-lifecycle").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }
    private fun wav(file: File) {
        val size = 16_000 * 2 * 30
        file.writeBytes(ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(size); repeat(size / 2) { putShort(0) }
        }.array())
    }

    @Test fun mediaRestoresPausedAndPlayingPositionsAndStaysPausedAfterBackground() {
        guard()
        val file = File(context.cacheDir, "lifecycle-audio.wav").also(::wav)
        try { launch(file, ChangesPreviewRoute.MEDIA, "audio/wav").use { scenario ->
            fun media() = scenario.read { it.window.decorView.child<ArtifactMediaView>() }
            fun position() = scenario.read { it.window.decorView.child<ArtifactMediaView>()?.currentPosition ?: -1 }
            fun playing() = scenario.read { it.window.decorView.child<ArtifactMediaView>()?.isPlaying == true }
            find(By.text("Play").enabled(true))
            scenario.onActivity { it.window.decorView.child<ArtifactMediaView>()!!.seekTo(8_000) }
            await("Paused seek did not complete") { position() in 7_800..8_500 }
            val first = media()
            scenario.recreate(); find(By.text("Play").enabled(true))
            await("Paused position was lost") { position() in 7_800..8_500 }
            assertNotSame(first, media()); assertFalse(playing()); assertFalse(first!!.isPlaying)
            find(By.text("Play").enabled(true)).click()
            await("Playback did not start") { playing() && position() > 8_600 }
            val before = position()
            scenario.recreate()
            await("Playing state/position was lost on recreation") { playing() && position() >= before - 300 && position() < before + 5_000 }
            val backgroundView = media()!!
            scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.runOnMainSync { assertFalse("Background must stop audio", backgroundView.isPlaying) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            find(By.text("Play").enabled(true))
            val stopped = position(); Thread.sleep(450)
            assertFalse(playing()); assertEquals(stopped, position())
            // A saved task returning from the background must not restart playback.
            scenario.recreate(); find(By.text("Play").enabled(true))
            await("Background bookmark lost") { kotlin.math.abs(position() - stopped) < 300 }
            assertFalse(playing())
            screenshot("media-restored")
            find(By.text("Restart")).click()
            await("Restart did not reset position") { position() in 0..250 }
            assertFalse(playing())
            val closing = media()!!
            scenario.close(); assertFalse(closing.isPlaying)
        } } finally { file.delete() }
    }

    @Test fun mediaSpeedSeekingMuteAndFullscreenKeepPlaybackBookmarks() {
        guard(); check(Build.VERSION.SDK_INT >= 29)
        val file = File(context.cacheDir, "media-controls.wav").also(::wav)
        try { launch(file, ChangesPreviewRoute.MEDIA, "audio/wav").use { scenario ->
            fun media() = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { root ->
                root.child<ArtifactMediaView>()?.takeIf { it.isShown }
            }
            fun position() = scenario.read { media()?.currentPosition ?: -1 }
            fun playing() = scenario.read { media()?.isPlaying == true }
            fun actualSpeed() = scenario.read {
                media()?.let { view ->
                    (ArtifactMediaView::class.java.getDeclaredField("player").apply { isAccessible = true }.get(view) as? MediaPlayer)
                        ?.playbackParams?.speed
                }
            }
            find(By.text("Play").enabled(true))
            scenario.onActivity { media()!!.seekTo(5000) }
            await("Initial seek failed") { position() in 4800..5300 }
            find(By.desc("Playback speed")).click(); find(By.text("2.0×")).click()
            find(By.desc("Playback speed").text("2.0×"))
            Thread.sleep(350); assertFalse("Changing speed must preserve Pause", playing())
            assertTrue(position() in 4800..5300)
            find(By.text("Mute")).click(); find(By.text("Unmute"))
            find(By.desc("Forward 10 seconds")).click()
            await("Forward skip failed") { position() in 14_800..15_300 }
            find(By.desc("Back 10 seconds")).click()
            await("Backward skip failed") { position() in 4800..5300 }
            val slider = find(By.desc("Playback position")).visibleBounds
            device.click(slider.centerX(), slider.centerY())
            await("Scrubber failed") { position() in 13_500..16_500 }
            assertFalse(playing())
            val paused = position()
            find(By.text("Fullscreen")).click(); find(By.text("Exit fullscreen")); find(By.text("Play").enabled(true))
            await("Fullscreen lost paused bookmark") { kotlin.math.abs(position() - paused) < 350 }
            assertFalse(playing()); find(By.text("Unmute"))
            assertTrue(scenario.read { media()!!.rootView !== it.window.decorView })
            screenshot("media-fullscreen-paused")
            find(By.text("Play").enabled(true)).click()
            await("Fullscreen playback failed") { playing() }
            assertEquals(2f, actualSpeed()!!, .01f)
            val before = position()
            scenario.recreate(); find(By.text("Exit fullscreen"))
            await("Fullscreen recreation lost playback") { playing() && position() >= before - 350 && position() < before + 6000 }
            assertEquals(2f, actualSpeed()!!, .01f); find(By.text("Unmute"))
            find(By.text("Pause")).click(); val stopped = position()
            device.pressBack(); find(By.text("Fullscreen")); find(By.text("Play").enabled(true))
            await("Leaving fullscreen lost bookmark") { kotlin.math.abs(position() - stopped) < 350 }
            assertFalse(playing()); find(By.text("Unmute"))
            screenshot("media-controls-inline")
        } } finally { file.delete() }
    }

    @Test fun rawTextRestoresSearchSelectionViewportAndLineNumbersAcrossRecreation() {
        guard()
        val preferences = context.getSharedPreferences("cmux-artifact-text", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val file = File(context.cacheDir, "lifecycle-text.txt").apply {
            writeText((1..180).joinToString("\n") { "Line $it ${if (it == 4 || it == 130) "Needle" else "content"} " + "text ".repeat(9) })
        }
        try { launch(file, ChangesPreviewRoute.TEXT, "text/plain").use { scenario ->
            await("Text not laid out") { scenario.read { it.window.decorView.child<ArtifactTextScrollView>()?.textView?.layout != null } }
            action("Search"); find(By.clazz("android.widget.EditText")).text = "needle"
            find(By.text("1/2")); find(By.desc("Next match")).click(); find(By.text("2/2"))
            device.pressBack() // Dismiss the search keyboard while retaining the search.
            await("Search did not move viewport") { scenario.read { (it.window.decorView.child<ArtifactTextScrollView>()?.scrollY ?: 0) > 1_000 } }
            action("Line numbers")
            val beforeY = scenario.read { it.window.decorView.child<ArtifactTextScrollView>()!!.scrollY }
            val beforePadding = scenario.read { it.window.decorView.child<ArtifactTextScrollView>()!!.textView.paddingLeft }
            val selectedText = "Line 130"
            val offset = file.readText().indexOf(selectedText)
            scenario.onActivity { Selection.setSelection(it.window.decorView.child<ArtifactTextScrollView>()!!.textView.text as Spannable, offset, offset + selectedText.length) }
            scenario.recreate(); find(By.text("2/2"))
            // Search autofocus may open the keyboard; the anchored line must survive it.
            device.pressBack()
            await("Reading position reset") { scenario.read { kotlin.math.abs(it.window.decorView.child<ArtifactTextScrollView>()!!.scrollY - beforeY) < 5 } }
            scenario.onActivity {
                val text = it.window.decorView.child<ArtifactTextScrollView>()!!.textView
                assertEquals(beforePadding, text.paddingLeft)
                assertEquals(offset, text.selectionStart); assertEquals(offset + selectedText.length, text.selectionEnd)
                assertEquals(file.readText(), text.text.toString())
            }
            // AndroidView draws beyond its Compose allocation unless explicitly clipped.
            // The middle of the action row is empty; document glyphs must never paint there.
            val actionBounds = find(By.desc("Viewer actions")).visibleBounds
            val pixels = instrumentation.uiAutomation.takeScreenshot()
            var leakedTextPixels = 0
            try {
                for (x in pixels.width * 45 / 100 until pixels.width * 75 / 100)
                    for (y in actionBounds.top until actionBounds.bottom) {
                        val color = pixels.getPixel(x, y)
                        if (android.graphics.Color.red(color) > 150 && android.graphics.Color.green(color) > 150 &&
                            android.graphics.Color.blue(color) > 150) leakedTextPixels++
                    }
            } finally { pixels.recycle() }
            screenshot("text-restored")
            assertEquals("Scrolled document painted over the action row", 0, leakedTextPixels)
            find(By.desc("Next match")).click(); find(By.text("1/2"))
            await("Restored search did not wrap to first match") { scenario.read { it.window.decorView.child<ArtifactTextScrollView>()!!.scrollY < 200 } }
        } } finally { file.delete(); preferences.edit().clear().commit() }
    }

    @Test fun markdownRawModeAndGoToLineDraftSurviveRecreation() {
        guard()
        val file = File(context.cacheDir, "lifecycle-markdown.md").apply { writeText("# Document\n" + "paragraph\n".repeat(200)) }
        try { launch(file, ChangesPreviewRoute.TEXT, "text/markdown").use { scenario ->
            action("Raw")
            await("Raw Markdown source not shown") { scenario.read { it.window.decorView.child<ArtifactTextScrollView>()?.textView?.text?.toString() == file.readText() } }
            action("Go to line"); find(By.clazz("android.widget.EditText")).text = "120"
            scenario.recreate()
            find(By.text("Go to line")); assertEquals("120", find(By.clazz("android.widget.EditText")).text)
            find(By.text("Go").enabled(true)).click()
            await("Restored line draft did not navigate") { scenario.read { (it.window.decorView.child<ArtifactTextScrollView>()?.scrollY ?: 0) > 1_000 } }
            screenshot("markdown-raw-restored")
        } } finally { file.delete() }
    }
}
