package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
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
import kotlin.math.abs

/** Uses the original 320×180 two-color video to prove the displayed geometry. */
class ArtifactVideoGeometryRuntimeTest {
    private fun View.media(): ArtifactMediaView? = when (this) {
        is ArtifactMediaView -> takeIf { isShown }
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).media() }
        else -> null
    }

    @Test fun videoFitsAcrossRotationFullscreenRecreationAndBackground() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(context.getExternalFilesDir(null), "video-geometry").apply { mkdirs() }
        val file = File(context.cacheDir, "geometry.mp4")
        instrumentation.context.assets.open("media/tracks.mp4").use { input -> file.outputStream().use(input::copyTo) }
        fun await(message: String, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(100) }
            fail(message)
        }
        fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 10_000)) { "Missing $selector" }
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", file.absolutePath).putExtra("route", ChangesPreviewRoute.MEDIA.name)
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
            fun position(): Int { var value = -1; read { view, _ -> value = view.currentPosition; true }; return value }
            fun frame(name: String) {
                val screenshot = File(evidence, "$name.png")
                var viewport = Rect(); var painted = Rect(); var colors = 0
                await("Video frame did not paint for $name") {
                    read { view, state -> view.getGlobalVisibleRect(viewport); state.prepared }
                    assertTrue(device.takeScreenshot(screenshot))
                    val bitmap = BitmapFactory.decodeFile(screenshot.absolutePath)
                    var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
                    colors = 0
                    try {
                        // Controls use blue accents too. Measure the native video surface,
                        // rather than mistaking antialiased button text for video pixels.
                        for (y in maxOf(0, viewport.top) until minOf(bitmap.height, viewport.bottom) step 2)
                            for (x in maxOf(0, viewport.left) until minOf(bitmap.width, viewport.right) step 2) {
                            val c = bitmap.getPixel(x, y)
                            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                            if ((r in 20..60 && g in 60..100 && b in 108..148) ||
                                (r in 205..245 && g in 145..185 && b in 55..95)) {
                                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y); colors++
                            }
                        }
                        painted = Rect(left, top, right + 2, bottom + 2)
                    } finally { bitmap.recycle() }
                    colors > 1000
                }
                val ratio = painted.width().toDouble() / painted.height()
                File(evidence, "$name.json").writeText(JSONObject().put("viewport", viewport.toShortString())
                    .put("painted", painted.toShortString()).put("aspectRatio", ratio).put("colorSamples", colors)
                    .put("position", position()).toString())
                device.dumpWindowHierarchy(File(evidence, "$name.xml"))
                assertEquals("Video stretched or cropped in $name: $painted in $viewport", 16.0 / 9, ratio, .025)
                assertTrue("Painted frame exceeds viewport", Rect(viewport).apply { inset(-3, -3) }.contains(painted))
                assertTrue("Video underfills its viewport", abs(painted.width() - viewport.width()) < 4 || abs(painted.height() - viewport.height()) < 4)
                find(By.desc("Playback position"))
                find(By.text("Restart").enabled(true))
            }
            try {
                find(By.text("Play").enabled(true)).click()
                await("Video did not start") { read { view, state -> view.isPlaying && state.videoWidth == 320 && state.videoHeight == 180 } }
                find(By.text("Pause")).click()
                read { view, _ -> view.seekTo(15_000); true }
                await("Seek failed") { position() in 14_700..15_400 }
                frame("portrait")
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                await("Landscape did not restore paused bookmark") { device.displayWidth > device.displayHeight && read { view, state -> state.prepared && !view.isPlaying && view.currentPosition in 14_700..15_400 } }
                frame("landscape")
                find(By.text("Fullscreen")).click(); find(By.text("Exit fullscreen"))
                device.wait(Until.findObject(By.pkg("com.android.systemui").text("Got it")), 1500)?.click()
                await("Fullscreen lost bookmark") { read { view, state -> state.prepared && !view.isPlaying && abs(view.currentPosition - 15_000) < 400 } }
                frame("fullscreen")
                find(By.text("Play").enabled(true)).click()
                await("Fullscreen did not play") { read { view, _ -> view.isPlaying } }
                val before = position(); val started = SystemClock.elapsedRealtime()
                scenario.recreate()
                await("Recreation lost video playback") { read { view, state -> state.prepared && view.isPlaying && view.currentPosition >= before - 400 && view.currentPosition <= before + SystemClock.elapsedRealtime() - started + 1500 } }
                frame("fullscreen-recreated")
                scenario.moveToState(Lifecycle.State.CREATED)
                assertTrue("Background left video running", read { view, _ -> !view.isPlaying })
                scenario.moveToState(Lifecycle.State.RESUMED)
                find(By.text("Play").enabled(true))
                val stopped = position(); Thread.sleep(350)
                assertTrue(read { view, _ -> !view.isPlaying && abs(view.currentPosition - stopped) < 100 })
                device.pressBack(); find(By.text("Fullscreen"))
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                await("Portrait return lost bookmark") { device.displayWidth < device.displayHeight && read { view, state -> state.prepared && !view.isPlaying && abs(view.currentPosition - stopped) < 400 } }
                frame("portrait-return")
                assertTrue(read { _, state -> state.failure == null && state.controlFailure == null })
            } catch (failure: Throwable) {
                device.takeScreenshot(File(evidence, "failure.png")); device.dumpWindowHierarchy(File(evidence, "failure.xml"))
                throw failure
            } finally { scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
        } } finally { file.delete() }
    }
}
