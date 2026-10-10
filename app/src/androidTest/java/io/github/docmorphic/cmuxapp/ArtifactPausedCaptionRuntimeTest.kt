package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtifactPausedCaptionRuntimeTest {
    private fun View.media(): ArtifactMediaView? = when (this) {
        is ArtifactMediaView -> takeIf { isShown }
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).media() }
        else -> null
    }
    @Test fun pausedSeeksSwitchCuesClearGapsAndSurviveTrackChangesAndRecreation() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val configurator = Configurator.getInstance()
        val previousIdleTimeout = configurator.waitForIdleTimeout
        val evidence = File(context.getExternalFilesDir(null), "paused-captions").apply { deleteRecursively(); mkdirs() }
        val file = File(context.cacheDir, "paused-captions.mp4")
        instrumentation.context.assets.open("media/paused-captions.mp4").use { input -> file.outputStream().use(input::copyTo) }
        configurator.setWaitForIdleTimeout(0)
        fun await(message: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < end) { if (condition()) return; Thread.sleep(100) }
            fail(message)
        }
        fun find(selector: BySelector): UiObject2 {
            val end = SystemClock.elapsedRealtime() + 10_000
            do {
                if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                device.findObject(selector)?.let { return it }
                Thread.sleep(100)
            } while (SystemClock.elapsedRealtime() < end)
            error("Missing $selector")
        }
        fun choose(menu: String, label: String) { find(By.desc(menu)).click(); find(By.text(label)).click() }
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", file.path).putExtra("route", ChangesPreviewRoute.MEDIA.name)
            .putExtra("mime", "video/mp4")).use { scenario ->
            fun read(block: (ArtifactMediaView, ArtifactMediaState) -> Boolean): Boolean {
                var result = false
                scenario.onActivity {
                    WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { it.media() }?.let { view ->
                        val state = ArtifactMediaView::class.java.getDeclaredField("state").apply { isAccessible = true }.get(view) as ArtifactMediaState
                        result = block(view, state)
                    }
                }
                return result
            }
            fun ready(position: Int, cue: String?) = read { view, state ->
                val seeking = ArtifactMediaView::class.java.getDeclaredField("seeking").apply { isAccessible = true }.get(view) as Boolean
                state.prepared && !seeking && !view.isPlaying && !state.playRequested &&
                    kotlin.math.abs(view.currentPosition - position) < 250 && state.captionText == cue && state.trackFailure == null
            }
            fun dump(name: String) {
                read { view, state ->
                    val player = ArtifactMediaView::class.java.getDeclaredField("player").apply { isAccessible = true }.get(view) as? MediaPlayer
                    File(evidence, "$name.json").writeText(JSONObject().put("position", view.currentPosition)
                        .put("playing", view.isPlaying).put("playRequested", state.playRequested)
                        .put("cue", state.captionText).put("caption", state.selectedCaption)
                        .put("nativeAudio", player?.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO))
                        .put("failure", state.trackFailure ?: state.failure).toString())
                    true
                }
                device.takeScreenshot(File(evidence, "$name.png")); device.dumpWindowHierarchy(File(evidence, "$name.xml"))
            }
            fun painted(name: String, text: String) {
                val bounds = find(By.text(text)).visibleBounds
                dump(name)
                val bitmap = checkNotNull(BitmapFactory.decodeFile(File(evidence, "$name.png").path))
                try {
                    var white = 0
                    for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                        val c = bitmap.getPixel(x, y)
                        if (Color.red(c) > 220 && Color.green(c) > 220 && Color.blue(c) > 220) white++
                    }
                    assertTrue("Paused caption text did not paint", white > 30)
                } finally { bitmap.recycle() }
            }
            fun seek(position: Int, cue: String?) {
                read { view, _ -> view.seekTo(position); true }
                await("Paused cue did not match $position ms / $cue") { ready(position, cue) }
            }
            try {
                await("Fixture did not prepare") { read { _, state -> state.prepared } }
                choose("Subtitle tracks", "English")
                seek(3_000, "CMUX FIRST"); painted("first", "CMUX FIRST")
                seek(6_000, null); dump("gap")
                if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                assertFalse(device.hasObject(By.text("CMUX FIRST")))
                seek(10_000, "CMUX SECOND"); painted("second", "CMUX SECOND")
                choose("Subtitle tracks", "French")
                await("Paused language change did not refresh the current cue") { ready(10_000, "CMUX DEUXIÈME") }
                painted("french", "CMUX DEUXIÈME")
                read { view, _ -> view.seekTo(3_000); view.seekTo(16_000); true }
                await("Rapid seek admitted an older cue") { ready(16_000, "CMUX TROISIÈME") }
                read { view, state ->
                    val english = state.tracks.first { it.caption && it.language == "eng" }.key
                    val french = state.tracks.first { it.caption && it.language == "fra" }.key
                    view.selectCaption(english); view.selectCaption(french); view.selectCaption(ArtifactMediaTracks.OFF); true
                }
                Thread.sleep(1000)
                assertTrue("A retired request overwrote Off", ready(16_000, null))
                choose("Subtitle tracks", "French")
                await("French cue was not restored") { ready(16_000, "CMUX TROISIÈME") }
                choose("Audio tracks", "French")
                await("Audio replacement lost the paused caption/bookmark") { ready(16_000, "CMUX TROISIÈME") }
                scenario.recreate()
                await("Recreation did not rebuild the paused cue") { ready(16_000, "CMUX TROISIÈME") }
                painted("recreated", "CMUX TROISIÈME")
                find(By.text("Fullscreen")).click()
                await("Fullscreen lost the paused cue") { ready(16_000, "CMUX TROISIÈME") }
                painted("fullscreen", "CMUX TROISIÈME")
                scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
                await("Background/return lost paused captions") { ready(16_000, "CMUX TROISIÈME") }
                seek(19_500, null); dump("after-cues")
                seek(10_000, "CMUX DEUXIÈME"); Thread.sleep(1000)
                assertTrue("Caption refresh started playback or advanced the bookmark", ready(10_000, "CMUX DEUXIÈME"))
                painted("restored-paused", "CMUX DEUXIÈME")
            } catch (failure: Throwable) { dump("failure"); throw failure }
        } } finally { file.delete(); configurator.setWaitForIdleTimeout(previousIdleTimeout) }
    }
}
