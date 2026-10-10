package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A restored paused bookmark must display video without a hidden Play gesture. */
class ArtifactPausedVideoFrameRuntimeTest {
    private fun View.media(): ArtifactMediaView? = when (this) {
        is ArtifactMediaView -> takeIf { isShown }
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).media() }
        else -> null
    }
    @Test fun pausedVideoPaintsAfterRecreationAudioReplacementAndFullscreen() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val configurator = Configurator.getInstance()
        val oldIdle = configurator.waitForIdleTimeout
        val evidence = File(context.getExternalFilesDir(null), "paused-video-frame").apply { deleteRecursively(); mkdirs() }
        val source = File(context.cacheDir, "paused-video-frame.mp4")
        instrumentation.context.assets.open("media/tracks.mp4").use { input -> source.outputStream().use(input::copyTo) }
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
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", source.path).putExtra("route", ChangesPreviewRoute.MEDIA.name)
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
            fun settled() = read { view, state ->
                val seeking = ArtifactMediaView::class.java.getDeclaredField("seeking").apply { isAccessible = true }.get(view) as Boolean
                state.prepared && !seeking && !view.isPlaying && !state.playRequested &&
                    view.currentPosition in 11_750..12_250 && state.failure == null && state.trackFailure == null
            }
            fun dump(name: String) {
                read { view, state ->
                    File(evidence, "$name.json").writeText(JSONObject().put("position", view.currentPosition)
                        .put("playing", view.isPlaying).put("playRequested", state.playRequested)
                        .put("surface", view.holder.surfaceFrame.toShortString()).put("videoWidth", state.videoWidth)
                        .put("videoHeight", state.videoHeight).put("viewWidth", view.width).put("viewHeight", view.height)
                        .put("audio", state.selectedAudio).put("failure", state.failure).toString())
                    true
                }
                device.takeScreenshot(File(evidence, "$name.png")); device.dumpWindowHierarchy(File(evidence, "$name.xml"))
            }
            fun painted(name: String) {
                await("$name lost the paused bookmark") { settled() }
                await("$name has no visible paused video frame") {
                    val bounds = Rect()
                    if (!read { view, _ ->
                            val location = IntArray(2); view.getLocationOnScreen(location)
                            bounds.set(location[0], location[1], location[0] + view.width, location[1] + view.height); true
                        }) return@await false
                    if (!device.takeScreenshot(File(evidence, "$name.png"))) return@await false
                    val bitmap = BitmapFactory.decodeFile(File(evidence, "$name.png").path) ?: return@await false
                    try {
                        if (!bounds.intersect(0, 0, bitmap.width, bitmap.height)) return@await false
                        var gold = 0; var blue = 0
                        for (y in bounds.top until bounds.bottom step 3) for (x in bounds.left until bounds.right step 3) {
                            val pixel = bitmap.getPixel(x, y)
                            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                            if (r in 195..245 && g in 130..190 && b in 40..110) gold++
                            if (r in 10..60 && g in 50..110 && b in 100..160) blue++
                        }
                        gold > 1_000 && blue > 1_000
                    } finally { bitmap.recycle() }
                }
                assertTrue("Frame refresh silently started playback", settled())
                dump(name)
            }
            try {
                await("Video did not prepare a valid surface") { read { view, state ->
                    state.prepared && view.holder.surface.isValid && view.holder.surfaceFrame.width() == state.videoWidth &&
                        view.holder.surfaceFrame.height() == state.videoHeight
                } }
                read { view, _ -> view.seekTo(12_000); true }
                painted("settled-seek")
                scenario.recreate()
                painted("recreated")
                find(By.desc("Audio tracks")).click(); find(By.text("French")).click()
                await("French audio selection was not retained") { read { _, state ->
                    state.tracks.any { it.kind == ArtifactTrackKind.AUDIO && it.language == "fra" && it.index == state.selectedAudio }
                } }
                painted("audio-replaced")
                find(By.text("Fullscreen")).click()
                painted("fullscreen")
                find(By.text("Exit fullscreen")).click()
                painted("inline-return")
            } catch (failure: Throwable) { dump("failure"); throw failure }
        } } finally { source.delete(); configurator.setWaitForIdleTimeout(oldIdle) }
    }
}
