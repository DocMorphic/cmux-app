package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
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
import org.junit.Test

class ArtifactMediaTracksRuntimeTest {
    private fun View.media(): ArtifactMediaView? = when (this) {
        is ArtifactMediaView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).media() }
        else -> null
    }
    @Test fun realVideoTracksPreserveBookmarkAndRenderSelectedSubtitleAfterRecreation() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val evidence = File(context.getExternalFilesDir(null), "media-tracks").apply { mkdirs() }
        val file = File(context.cacheDir, "tracks.mp4")
        instrumentation.context.assets.open("media/tracks.mp4").use { input -> file.outputStream().use(input::copyTo) }
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; Thread.sleep(100) }
            fail(message)
        }
        fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 10_000)) { "Missing $selector" }
        fun choose(menu: String, label: String) { find(By.desc(menu)).click(); find(By.text(label)).click() }
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", file.absolutePath).putExtra("route", ChangesPreviewRoute.MEDIA.name)
            .putExtra("mime", "video/mp4")).use { scenario ->
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
                    File(evidence, "$name.json").writeText(JSONObject().put("prepared", state.prepared)
                        .put("playing", view.isPlaying).put("position", view.currentPosition)
                        .put("audio", state.selectedAudio).put("caption", state.selectedCaption)
                        .put("tracks", state.tracks.toString()).put("cue", state.captionText)
                        .put("failure", state.trackFailure ?: state.failure)
                        .put("nativeAudio", player?.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO)).toString())
                    true
                }
                device.takeScreenshot(File(evidence, "$name.png"))
                device.dumpWindowHierarchy(File(evidence, "$name.xml"))
            }
            try {
                await("Video did not prepare") { read { _, state, _ -> state.prepared } }
                dump("prepared")
                assertTrue("Missing embedded tracks", read { _, state, _ ->
                    state.tracks.count { it.kind == ArtifactTrackKind.AUDIO } == 2 && state.tracks.count { it.caption } == 2
                })
                choose("Subtitle tracks", "Off")
                read { view, _, _ -> view.seekTo(15_000); true }
                await("Initial bookmark failed") { read { view, _, _ -> view.currentPosition in 14_700..15_400 } }
                choose("Audio tracks", "French")
                await("Alternate audio selection lost paused state or bookmark") { read { view, state, player ->
                    val french = state.tracks.single { it.kind == ArtifactTrackKind.AUDIO && it.language == "fra" }
                    state.prepared && !view.isPlaying && view.currentPosition in 14_700..15_400 &&
                        player?.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) == french.index
                } }
                choose("Subtitle tracks", "English")
                read { view, _, _ -> view.seekTo(0); view.start(); true }
                find(By.text("CMUX ENGLISH CUE"))
                dump("english")
                choose("Subtitle tracks", "Off")
                await("Off left a visible subtitle") { !device.hasObject(By.text("CMUX ENGLISH CUE")) }
                choose("Subtitle tracks", "French")
                read { view, _, _ -> view.seekTo(0); true }
                find(By.text("CMUX FRENCH CUE"))
                read { view, _, _ -> view.pause(); true }
                scenario.recreate()
                await("Track selection was lost on recreation") { read { view, state, player ->
                    state.prepared && !view.isPlaying && state.tracks.any { it.caption && it.language == "fra" && it.key == state.captionPreference } &&
                        state.tracks.any { it.kind == ArtifactTrackKind.AUDIO && it.language == "fra" && it.index == player?.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) }
                } }
                read { view, _, _ -> view.start(); true }
                val cue = find(By.text("CMUX FRENCH CUE"))
                dump("restored-french")
                val bounds = cue.visibleBounds
                val bitmap = BitmapFactory.decodeFile(File(evidence, "restored-french.png").absolutePath)
                try {
                    var blue = 0; var gold = 0; var white = 0
                    for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                        val color = bitmap.getPixel(x, y)
                        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
                        if (r in 20..60 && g in 60..100 && b in 108..148) blue++
                        if (r in 205..245 && g in 145..185 && b in 55..95) gold++
                        if (bounds.contains(x, y) && r > 220 && g > 220 && b > 220) white++
                    }
                    assertTrue("Video colors did not render: blue=$blue gold=$gold", blue > 1000 && gold > 1000)
                    assertTrue("Subtitle text has no visible pixels: $white", white > 30)
                } finally { bitmap.recycle() }
                assertTrue(read { _, state, _ -> state.trackFailure == null && state.failure == null })
            } catch (failure: Throwable) { dump("failure"); throw failure }
        } } finally { file.delete() }
    }
}
