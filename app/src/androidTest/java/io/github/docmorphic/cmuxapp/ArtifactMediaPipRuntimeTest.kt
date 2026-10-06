package io.github.docmorphic.cmuxapp

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ArtifactMediaPipRuntimeTest {
    @Test fun playbackHostsAndAccountProviderArePrivate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (type in listOf(MediaPlaybackActivity::class.java, BrowserMediaPlaybackActivity::class.java)) {
            val info = context.packageManager.getActivityInfo(ComponentName(context, type), 0)
            assertFalse(info.exported)
            assertTrue(info.configChanges and ActivityInfo.CONFIG_SCREEN_SIZE != 0)
            assertTrue(info.configChanges and ActivityInfo.CONFIG_ORIENTATION != 0)
        }
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.media-playback-account", 0)
        assertNotNull(provider); assertFalse(provider!!.exported)
    }

    @Test fun realVideoEntersPipKeepsItsPrivateFileAndReleasesWhenClosed() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        val device = UiDevice.getInstance(instrumentation)
        val source = File(context.cacheDir, "pip-runtime.mp4")
        instrumentation.context.assets.open("media/tracks.mp4").use { input -> source.outputStream().use(input::copyTo) }
        val evidence = File(context.getExternalFilesDir(null), "media-pip").apply { mkdirs() }
        var playback: MediaPlaybackActivity? = null
        var owned: File? = null
        fun onPlayback(block: (MediaPlaybackActivity, ArtifactPlaybackModel) -> Boolean): Boolean {
            var result = false
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                val activity = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED).flatMap { monitor.getActivitiesInStage(it) }
                    .filterIsInstance<MediaPlaybackActivity>().firstOrNull()
                if (activity != null) { playback = activity; result = block(activity, ViewModelProvider(activity)[ArtifactPlaybackModel::class.java]) }
            }
            return result
        }
        fun await(message: String, condition: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < end) { if (condition()) return; Thread.sleep(100) }
            fail(message)
        }
        try {
            ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
                .putExtra("path", source.absolutePath).putExtra("route", ChangesPreviewRoute.MEDIA.name)
                .putExtra("mime", "video/mp4")).use { scenario ->
                val button = checkNotNull(device.wait(Until.findObject(By.text("Picture in picture")), 15_000))
                button.click()
                await("Dedicated player did not enter PiP") { onPlayback { activity, model ->
                    owned = model.entry?.files?.file
                    activity.isInPictureInPictureMode && model.player.prepared && model.player.view != null
                } }
                assertTrue(owned!!.isFile); assertNotEquals(source.absolutePath, owned!!.absolutePath)
                source.delete() // The originating preview's cache may now be retired.
                onPlayback { _, model -> model.player.view!!.start(); true }
                await("Playback did not advance in PiP") { onPlayback { _, model ->
                    model.player.capture(); model.player.position > 250 && model.player.view!!.isPlaying
                } }
                await("PiP reported playback without a visible video frame") {
                    val screenshot = File(evidence, "playing.png")
                    device.takeScreenshot(screenshot)
                    val pixels = android.graphics.BitmapFactory.decodeFile(screenshot.path) ?: return@await false
                    try {
                        var gold = 0; var blue = 0
                        for (y in 0 until pixels.height step 3) for (x in 0 until pixels.width step 3) {
                            val pixel = pixels.getPixel(x, y)
                            val r = android.graphics.Color.red(pixel); val g = android.graphics.Color.green(pixel); val b = android.graphics.Color.blue(pixel)
                            if (r in 195..245 && g in 130..190 && b in 40..110) gold++
                            if (r in 10..60 && g in 50..110 && b in 100..160) blue++
                        }
                        gold > 100 && blue > 100
                    } finally { pixels.recycle() }
                }
                onPlayback { _, model -> model.player.view!!.pause(); true }
                assertTrue(onPlayback { _, model -> !model.player.playRequested && model.player.systemPlaybackOwner })
                // The source Activity can close without destroying the PiP player's private bytes.
                scenario.close()
                assertTrue(owned!!.isFile)
                instrumentation.runOnMainSync { playback!!.onBackPressedDispatcher.onBackPressed() }
                await("Closed player retained its media copy") { !owned!!.exists() }
            }
        } finally {
            instrumentation.runOnMainSync { playback?.finish() }
            source.delete()
        }
    }
}
