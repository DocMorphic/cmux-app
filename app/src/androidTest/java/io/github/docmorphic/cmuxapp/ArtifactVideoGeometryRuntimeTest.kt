package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
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
        val configurator = Configurator.getInstance()
        val previousIdleTimeout = configurator.waitForIdleTimeout
        // Continuously changing playback timestamps never become idle. Await
        // specific visible states instead of spending the overlay timeout idling.
        configurator.setWaitForIdleTimeout(0)
        val evidence = File(context.getExternalFilesDir(null), "video-geometry").apply { deleteRecursively(); mkdirs() }
        val file = File(context.cacheDir, "geometry.mp4")
        instrumentation.context.assets.open("media/tracks.mp4").use { input -> file.outputStream().use(input::copyTo) }
        fun await(message: String, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(100) }
            fail(message)
        }
        fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 10_000)) { "Missing $selector" }
        fun revealForGesture() {
            await("Controls did not hide before the next gesture") { device.hasObject(By.desc("Show playback controls")) }
            // Tree traversal can consume the entire three-second timeout on a
            // busy guest. Tap the observed backdrop and transport coordinates.
            device.click(device.displayWidth / 2, device.displayHeight * 2 / 3)
            Thread.sleep(100)
        }
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
                var fullscreen = false
                var playing = false
                read { view, state -> fullscreen = state.fullscreen; playing = view.isPlaying; true }
                if (fullscreen && device.hasObject(By.desc("Hide playback controls"))) {
                    val bounds = find(By.desc("Hide playback controls")).visibleBounds
                    // Tap between the center transport buttons and the bottom timeline.
                    device.click(bounds.centerX(), bounds.top + bounds.height() * 2 / 3)
                }
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
                if (fullscreen) {
                    assertTrue("Fullscreen should fit the whole display", painted.height() > device.displayHeight * .8 || painted.width() > device.displayWidth * .8)
                    if (!playing) find(By.desc("Show playback controls")).click()
                }
                if (!fullscreen || !playing) {
                    find(By.desc("Playback position"))
                    find(By.text("Restart").enabled(true))
                }
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
                device.takeScreenshot(File(evidence, "fullscreen-controls.png"))
                // An open menu must not disappear when the playback timer expires.
                val transportButton = find(By.text("Play").enabled(true)).visibleBounds
                val speedButton = find(By.desc("Playback speed")).visibleBounds
                val captionButton = find(By.desc("Subtitle tracks")).visibleBounds
                val mute = find(By.text("Mute")).visibleBounds
                device.click(transportButton.centerX(), transportButton.centerY())
                await("Fullscreen did not play") { read { view, _ -> view.isPlaying } }
                revealForGesture()
                device.click(speedButton.centerX(), speedButton.centerY())
                find(By.text("1.5×"))
                device.takeScreenshot(File(evidence, "speed-menu.png"))
                Thread.sleep(3500)
                find(By.text("1.5×")).click()
                revealForGesture()
                device.click(captionButton.centerX(), captionButton.centerY()); find(By.text("English")).click()
                find(By.text("CMUX ENGLISH CUE"))
                revealForGesture()
                device.click(captionButton.centerX(), captionButton.centerY()); find(By.text("Off")).click()
                revealForGesture()
                val downTime = SystemClock.uptimeMillis()
                fun touch(action: Int) {
                    val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                        mute.centerX().toFloat(), mute.centerY().toFloat(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                    finally { event.recycle() }
                }
                touch(MotionEvent.ACTION_DOWN)
                try {
                    Thread.sleep(3500)
                    assertTrue("Controls disappeared under a held finger", device.hasObject(By.desc("Hide playback controls")))
                } finally { touch(MotionEvent.ACTION_UP) }
                var sameView: ArtifactMediaView? = null
                read { view, _ -> sameView = view; true }
                await("Fullscreen controls did not hide while playing") { device.hasObject(By.desc("Show playback controls")) }
                assertFalse(device.hasObject(By.text("Restart")))
                assertTrue("Hiding controls replaced/stopped the player", read { view, _ -> view === sameView && view.isPlaying })
                revealForGesture()
                // Use the real transport coordinates already observed in this
                // layout, without another accessibility traversal before tapping.
                device.click(transportButton.centerX(), transportButton.centerY())
                await("Visible transport did not pause playback") { read { view, _ -> !view.isPlaying } }
                val paused = position(); Thread.sleep(3500)
                find(By.text("Play").enabled(true))
                assertTrue(read { view, _ -> !view.isPlaying && abs(view.currentPosition - paused) < 100 })
                // Leave enough fixture duration for recreation/rotation even on
                // a slow guest; this also verifies an explicit paused seek.
                read { view, _ -> view.seekTo(15_000); true }
                await("Paused seek before recreation failed") { position() in 14_700..15_400 }
                find(By.text("Play").enabled(true)).click()
                val before = position(); val started = SystemClock.elapsedRealtime()
                scenario.recreate()
                await("Recreation lost video playback") { read { view, state -> state.prepared && view.isPlaying && state.speed == 1.5f && view.currentPosition >= before - 400 && view.currentPosition <= before + (SystemClock.elapsedRealtime() - started) * 1.5 + 1500 } }
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
                find(By.text("Fullscreen")).click(); find(By.text("Exit fullscreen"))
                val portraitTransport = find(By.text("Play").enabled(true)).visibleBounds
                val portraitCaptions = find(By.desc("Subtitle tracks")).visibleBounds
                device.click(portraitTransport.centerX(), portraitTransport.centerY())
                revealForGesture()
                device.click(portraitCaptions.centerX(), portraitCaptions.centerY()); find(By.text("English")).click()
                find(By.text("CMUX ENGLISH CUE"))
                revealForGesture()
                device.click(portraitTransport.centerX(), portraitTransport.centerY())
                await("Portrait transport did not pause") { read { view, _ -> !view.isPlaying } }
                val caption = find(By.text("CMUX ENGLISH CUE")).visibleBounds
                val transport = find(By.text("Play")).visibleBounds
                assertFalse("Portrait captions overlap playback controls", Rect.intersects(caption, transport))
                device.takeScreenshot(File(evidence, "portrait-fullscreen-caption.png"))
                device.dumpWindowHierarchy(File(evidence, "portrait-fullscreen-caption.xml"))
                frame("portrait-fullscreen")
                assertTrue(read { _, state -> state.failure == null && state.controlFailure == null })
            } catch (failure: Throwable) {
                read { view, state ->
                    val bounds = Rect(); view.getGlobalVisibleRect(bounds)
                    File(evidence, "failure.json").writeText(JSONObject().put("bounds", bounds.toShortString())
                        .put("width", view.width).put("height", view.height).put("shown", view.isShown)
                        .put("prepared", state.prepared).put("playing", view.isPlaying).put("position", view.currentPosition)
                        .put("bookmark", state.position).put("videoWidth", state.videoWidth).put("videoHeight", state.videoHeight)
                        .put("failure", state.failure).toString())
                    true
                }
                device.takeScreenshot(File(evidence, "failure.png")); device.dumpWindowHierarchy(File(evidence, "failure.xml"))
                throw failure
            } finally { scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
        } } finally { file.delete(); configurator.setWaitForIdleTimeout(previousIdleTimeout) }
    }
}
