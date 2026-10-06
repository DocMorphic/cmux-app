package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises Android's actual floating-window expansion and Activity result delivery. */
class ArtifactMediaPipReturnRuntimeTest {
    private fun View.media(): ArtifactMediaView? = when (this) {
        is ArtifactMediaView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).media() }
        else -> null
    }
    private fun ArtifactMediaView.state() = ArtifactMediaView::class.java.getDeclaredField("state")
        .apply { isAccessible = true }.get(this) as ArtifactMediaState
    private fun ArtifactMediaView.player() = ArtifactMediaView::class.java.getDeclaredField("player")
        .apply { isAccessible = true }.get(this) as? MediaPlayer
    private fun ArtifactMediaView.seekComplete() = !(ArtifactMediaView::class.java.getDeclaredField("seeking")
        .apply { isAccessible = true }.get(this) as Boolean)

    @Test fun expandedPlayerReturnsChangedBookmarkAndTracksToRecreatedSource() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val source = File(context.cacheDir, "pip-return.mp4")
        instrumentation.context.assets.open("media/tracks.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val evidence = File(context.getExternalFilesDir(null), "media-pip-return").apply { mkdirs() }
        var playback: MediaPlaybackActivity? = null
        var owned: File? = null
        fun await(message: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < end) { if (condition()) return; Thread.sleep(100) }
            fail(message)
        }
        fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 10_000)) { "Missing $selector" }
        fun choose(menu: String, label: String) { find(By.desc(menu)).click(); find(By.text(label)).click() }
        fun onPlayback(block: (MediaPlaybackActivity, ArtifactPlaybackModel) -> Boolean): Boolean {
            var result = false
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                val activity = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED).flatMap { monitor.getActivitiesInStage(it) }
                    .filterIsInstance<MediaPlaybackActivity>().firstOrNull()
                if (activity != null) {
                    playback = activity
                    result = block(activity, ViewModelProvider(activity)[ArtifactPlaybackModel::class.java])
                }
            }
            return result
        }
        fun dump(name: String) {
            device.takeScreenshot(File(evidence, "$name.png"))
            device.dumpWindowHierarchy(File(evidence, "$name.xml"))
        }
        fun retained(view: ArtifactMediaView, state: ArtifactMediaState): Boolean =
            state.prepared && view.seekComplete() && !view.isPlaying && !state.playRequested && view.currentPosition in 11_700..12_400 &&
                state.speed == 1.5f && state.muted && state.failure == null && state.trackFailure == null &&
                state.tracks.any { it.caption && it.language == "fra" && it.key == state.captionPreference } &&
                state.tracks.any { it.kind == ArtifactTrackKind.AUDIO && it.language == "fra" &&
                    it.index == view.player()?.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) }
        try {
            ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
                .putExtra("path", source.absolutePath).putExtra("route", ChangesPreviewRoute.MEDIA.name)
                .putExtra("mime", "video/mp4")).use { scenario ->
                fun read(block: (ArtifactMediaView, ArtifactMediaState) -> Boolean): Boolean {
                    var result = false
                    scenario.onActivity { activity -> activity.window.decorView.media()?.let { result = block(it, it.state()) } }
                    return result
                }
                await("Source did not prepare") { read { _, state -> state.prepared } }
                read { view, _ -> view.seekTo(8_000); true }
                await("Source bookmark was not applied") { read { view, _ -> view.seekComplete() && view.currentPosition in 7_700..8_400 } }
                find(By.text("Picture in picture")).click()
                await("Player did not enter PiP with the source bookmark") { onPlayback { activity, model ->
                    owned = model.entry?.files?.file
                    activity.isInPictureInPictureMode && model.player.prepared &&
                        model.player.view?.seekComplete() == true && model.player.view?.currentPosition in 7_700..8_400
                } }
                assertNotEquals(source.absolutePath, owned!!.absolutePath)
                dump("floating")
                val bounds = Rect()
                assertTrue(onPlayback { activity, _ ->
                    val decor = activity.window.decorView
                    val location = IntArray(2)
                    decor.getLocationOnScreen(location)
                    bounds.set(location[0], location[1], location[0] + decor.width, location[1] + decor.height)
                    File(evidence, "floating-bounds.txt").writeText(bounds.toString())
                    !bounds.isEmpty
                })
                device.click(bounds.centerX(), bounds.centerY())
                dump("system-controls")
                // The OS supplies this button; do not replace expansion with a direct lifecycle callback.
                find(By.desc("Expand")).click()
                await("System expansion did not leave PiP") { onPlayback { activity, model ->
                    !activity.isInPictureInPictureMode && model.player.prepared
                } }
                find(By.text("Done"))
                choose("Audio tracks", "French")
                await("Expanded player did not prepare after selecting audio") { onPlayback { _, model -> model.player.prepared } }
                choose("Subtitle tracks", "French")
                choose("Playback speed", "1.5×")
                find(By.text("Mute")).click()
                onPlayback { _, model -> model.player.view!!.seekTo(12_000); true }
                await("Expanded player did not apply choices") { onPlayback { _, model -> retained(model.player.view!!, model.player) } }
                dump("expanded-changed")
                val timestamp = find(By.text("0:12")).visibleBounds
                val bitmap = android.graphics.BitmapFactory.decodeFile(File(evidence, "expanded-changed.png").path)
                try {
                    var visibleText = 0
                    for (y in timestamp.top until timestamp.bottom) for (x in timestamp.left until timestamp.right) {
                        val pixel = bitmap.getPixel(x, y)
                        if (android.graphics.Color.red(pixel) > 220 && android.graphics.Color.green(pixel) > 220 &&
                            android.graphics.Color.blue(pixel) > 220) visibleText++
                    }
                    assertTrue("Expanded timestamp has no visible text on its black background", visibleText > 30)
                } finally { bitmap.recycle() }
                find(By.text("Done")).click()
                await("Source did not receive the returned bookmark and tracks") { read(::retained) }
                await("Returning left the independent playback copy behind") { !owned!!.exists() }
                assertTrue(source.isFile)
                dump("returned")
                scenario.recreate()
                await("Recreation lost the returned bookmark or track choices") { read(::retained) }
                read { view, state ->
                    File(evidence, "restored.json").writeText(JSONObject().put("position", view.currentPosition)
                        .put("playing", view.isPlaying).put("speed", state.speed).put("muted", state.muted)
                        .put("audio", state.selectedAudio).put("caption", state.selectedCaption)
                        .put("nativeAudio", view.player()?.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO)).toString())
                    view.start(); true
                }
                find(By.text("CMUX FRENCH CUE"))
                await("Restored playback has no visible video frame") {
                    val screenshot = File(evidence, "restored-playing.png")
                    device.takeScreenshot(screenshot)
                    val frame = android.graphics.BitmapFactory.decodeFile(screenshot.path) ?: return@await false
                    try {
                        var gold = 0; var blue = 0
                        for (y in 0 until frame.height step 3) for (x in 0 until frame.width step 3) {
                            val pixel = frame.getPixel(x, y)
                            val r = android.graphics.Color.red(pixel); val g = android.graphics.Color.green(pixel)
                            val b = android.graphics.Color.blue(pixel)
                            if (r in 195..245 && g in 130..190 && b in 40..110) gold++
                            if (r in 10..60 && g in 50..110 && b in 100..160) blue++
                        }
                        gold > 1_000 && blue > 1_000
                    } finally { frame.recycle() }
                }
                dump("restored-playing")
                assertTrue("Resuming lost the saved position or speed", read { view, state ->
                    File(evidence, "playing.json").writeText(JSONObject().put("position", view.currentPosition)
                        .put("statePosition", state.position).put("speed", view.player()?.playbackParams?.speed).toString())
                    view.isPlaying && view.player()?.playbackParams?.speed == 1.5f && view.currentPosition in 11_700..30_000
                })
            }
        } catch (failure: Throwable) { dump("failure"); throw failure }
        finally {
            instrumentation.runOnMainSync { playback?.finish() }
            source.delete()
        }
    }
}
