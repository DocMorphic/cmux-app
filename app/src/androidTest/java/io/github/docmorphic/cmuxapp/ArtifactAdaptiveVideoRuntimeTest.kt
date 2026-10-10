package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in unresolved decoder diagnostic; never counted as a passing parity regression. */
class ArtifactAdaptiveVideoRuntimeTest {
    private fun View.media(): ArtifactMediaView? = when (this) {
        is ArtifactMediaView -> takeIf { isShown }
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).media() }
        else -> null
    }
    @Test fun continuousStreamResizesSurfaceAndFrameAcrossAspectChanges() {
        assumeTrue("Unresolved MPEG-TS diagnostic; run with -e adaptiveVideoDiagnostic true",
            InstrumentationRegistry.getArguments().getString("adaptiveVideoDiagnostic") == "true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val configurator = Configurator.getInstance()
        val oldIdle = configurator.waitForIdleTimeout
        val evidence = File(context.getExternalFilesDir(null), "adaptive-video").apply { deleteRecursively(); mkdirs() }
        val source = File(context.cacheDir, "adaptive-video.ts")
        instrumentation.context.assets.open("media/adaptive-video.ts").use { input -> source.outputStream().use(input::copyTo) }
        configurator.setWaitForIdleTimeout(0)
        fun await(message: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 18_000
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
            .putExtra("mime", "video/mp2t")).use { scenario ->
            var owner: ArtifactMediaView? = null
            var nativeOwner: MediaPlayer? = null
            fun read(block: (ArtifactMediaView, ArtifactMediaState, MediaPlayer?) -> Boolean): Boolean {
                var result = false
                scenario.onActivity { activity -> activity.window.decorView.media()?.let { view ->
                    val state = ArtifactMediaView::class.java.getDeclaredField("state").apply { isAccessible = true }.get(view) as ArtifactMediaState
                    val player = ArtifactMediaView::class.java.getDeclaredField("player").apply { isAccessible = true }.get(view) as? MediaPlayer
                    result = block(view, state, player)
                } }
                return result
            }
            fun dump(name: String) {
                read { view, state, player ->
                    File(evidence, "$name.json").writeText(JSONObject().put("position", view.currentPosition)
                        .put("nativePosition", player?.currentPosition).put("savedPosition", state.position)
                        .put("duration", state.duration)
                        .put("playing", view.isPlaying).put("playRequested", state.playRequested)
                        .put("foreground", state.foreground).put("prepared", state.prepared)
                        .put("modelWidth", state.videoWidth).put("modelHeight", state.videoHeight)
                        .put("nativeWidth", player?.videoWidth).put("nativeHeight", player?.videoHeight)
                        .put("viewWidth", view.width).put("viewHeight", view.height)
                        .put("buffer", view.holder.surfaceFrame.toShortString())
                        .put("failure", state.failure ?: state.controlFailure ?: state.trackFailure).toString()); true
                }
                device.takeScreenshot(File(evidence, "$name.png")); device.dumpWindowHierarchy(File(evidence, "$name.xml"))
            }
            fun frame(name: String, width: Int, height: Int) {
                val ratio = width.toDouble() / height
                await("$name native surface/view did not resize") { read { view, state, player ->
                    view === owner && player === nativeOwner && state.prepared && view.isPlaying && state.playRequested &&
                        state.videoWidth == width && state.videoHeight == height && player?.videoWidth == width &&
                        player.videoHeight == height && view.holder.surfaceFrame.width() == width &&
                        view.holder.surfaceFrame.height() == height && kotlin.math.abs(view.width.toDouble() / view.height - ratio) < .025
                } }
                val viewport = Rect()
                await("$name resized video did not paint") {
                    read { view, _, _ -> view.getGlobalVisibleRect(viewport) }
                    val screenshot = File(evidence, "$name.png")
                    if (!device.takeScreenshot(screenshot)) return@await false
                    val bitmap = BitmapFactory.decodeFile(screenshot.path) ?: return@await false
                    var gold = 0; var blue = 0
                    var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
                    try {
                        for (y in maxOf(0, viewport.top) until minOf(bitmap.height, viewport.bottom) step 2)
                            for (x in maxOf(0, viewport.left) until minOf(bitmap.width, viewport.right) step 2) {
                                val c = bitmap.getPixel(x, y)
                                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                                val isGold = r in 205..245 && g in 145..185 && b in 55..95
                                val isBlue = r in 20..60 && g in 60..100 && b in 108..148
                                if (isGold) gold++
                                if (isBlue) blue++
                                if (isGold || isBlue) { left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y) }
                            }
                    } finally { bitmap.recycle() }
                    val painted = Rect(left, top, right + 2, bottom + 2)
                    File(evidence, "$name-pixels.json").writeText(JSONObject().put("viewport", viewport.toShortString())
                        .put("painted", painted.toShortString()).put("gold", gold).put("blue", blue).toString())
                    gold > 1_000 && blue > 1_000 && kotlin.math.abs(painted.width().toDouble() / painted.height() - ratio) < .025 &&
                        (kotlin.math.abs(painted.width() - viewport.width()) < 4 || kotlin.math.abs(painted.height() - viewport.height()) < 4)
                }
                dump(name)
            }
            try {
                await("Adaptive fixture did not prepare") { read { view, state, player ->
                    owner = view; nativeOwner = player; state.prepared && player != null
                } }
                find(By.text("Play").enabled(true)).click()
                await("Initial landscape stream did not play") { read { view, _, player -> view.isPlaying && player?.videoWidth == 320 } }
                frame("landscape", 320, 180)
                await("Decoder did not announce the portrait resolution") { read { view, _, player ->
                    view.isPlaying && player?.videoWidth == 180 && player.videoHeight == 320
                } }
                frame("portrait", 180, 320)
                await("Decoder did not return to landscape") { read { view, _, player ->
                    view.isPlaying && player?.videoWidth == 320 && player.videoHeight == 180
                } }
                frame("landscape-return", 320, 180)
                assertTrue(read { _, state, _ -> state.failure == null && state.controlFailure == null && state.trackFailure == null })
            } catch (failure: Throwable) { dump("failure"); throw failure }
        } } finally { source.delete(); configurator.setWaitForIdleTimeout(oldIdle) }
    }
}
