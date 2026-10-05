package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** Real framework controller calls; grouped with media/focus device tests at milestones. */
class ArtifactMediaSessionTest {
    private fun View.media(): ArtifactMediaView? = when (this) {
        is ArtifactMediaView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).media() }
        else -> null
    }
    @Test fun controllerCommandsAndRetiredSessionsRespectPreviewLifecycle() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "media-session.wav")
        val size = 16_000 * 2 * 120
        file.writeBytes(ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(size)
        }.array())
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(75) }
            fail(message)
        }
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", file.absolutePath).putExtra("route", ChangesPreviewRoute.MEDIA.name)
            .putExtra("mime", "audio/wav")).use { scenario ->
            fun read(block: (ArtifactMediaView, ArtifactMediaState, MediaSession) -> Boolean): Boolean {
                var result = false
                scenario.onActivity { activity -> activity.window.decorView.media()?.let { view ->
                    val state = ArtifactMediaView::class.java.getDeclaredField("state").apply { isAccessible = true }.get(view) as ArtifactMediaState
                    val owner = ArtifactMediaView::class.java.getDeclaredField("mediaSession").apply { isAccessible = true }.get(view)
                    val session = ArtifactMediaSession::class.java.getDeclaredField("session").apply { isAccessible = true }.get(owner) as MediaSession
                    result = block(view, state, session)
                } }
                return result
            }
            await("Preview did not prepare") { read { _, state, _ -> state.prepared } }
            assertTrue(read { view, _, session -> !view.isPlaying && !session.isActive })
            lateinit var controller: MediaController
            read { view, _, session -> controller = session.controller; view.start(); true }
            await("Playing state was not published") { controller.playbackState?.state == PlaybackState.STATE_PLAYING }
            val metadata = requireNotNull(controller.metadata)
            assertEquals(file.name, metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
            assertTrue(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) >= 119_000)
            controller.transportControls.pause()
            await("Remote pause failed") { read { view, state, _ -> !view.isPlaying && !state.playRequested } }
            if (Build.VERSION.SDK_INT >= 29) {
                controller.transportControls.setPlaybackSpeed(2f)
                await("Remote speed change failed") { read { view, state, _ -> state.speed == 2f && !view.isPlaying } }
                controller.transportControls.setPlaybackSpeed(999f); instrumentation.waitForIdleSync()
                assertTrue(read { view, state, _ -> state.speed == 2f && !view.isPlaying })
            }
            controller.transportControls.seekTo(Long.MAX_VALUE)
            await("Remote seek did not clamp to duration") { read { _, state, _ -> state.position >= state.duration - 250 } }
            controller.transportControls.seekTo(30_000)
            await("Remote seek did not reach bookmark") { read { _, state, _ -> state.position in 29_750..30_250 } }
            controller.transportControls.fastForward()
            await("Forward skip failed") { read { _, state, _ -> state.position in 39_500..40_500 } }
            controller.transportControls.rewind()
            await("Backward skip failed") { read { _, state, _ -> state.position in 29_500..30_500 } }
            controller.transportControls.play()
            await("Remote play failed") { read { view, _, _ -> view.isPlaying } }
            val now = SystemClock.uptimeMillis()
            assertTrue(controller.dispatchMediaButtonEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)))
            controller.dispatchMediaButtonEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            await("Headset pause event failed") { read { view, state, _ -> !view.isPlaying && !state.playRequested } }
            controller.transportControls.stop()
            await("Remote stop did not clear bookmark") { read { view, state, _ -> !view.isPlaying && state.position == 0 } }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue(read { _, _, session -> !session.isActive })
            controller.transportControls.play(); instrumentation.waitForIdleSync()
            assertTrue(read { view, state, _ -> !view.isPlaying && !state.playRequested })
            scenario.moveToState(Lifecycle.State.RESUMED)
            val destroyed = AtomicBoolean(false)
            val callback = object : MediaController.Callback() {
                override fun onSessionDestroyed() { destroyed.set(true) }
            }
            controller.registerCallback(callback, Handler(Looper.getMainLooper()))
            try {
                scenario.recreate()
                await("Old session was not released") { destroyed.get() }
                await("Replacement did not prepare") { read { _, state, _ -> state.prepared } }
                controller.transportControls.play(); instrumentation.waitForIdleSync()
                assertTrue(read { view, state, session -> !view.isPlaying && !state.playRequested && !session.isActive })
            } finally { controller.unregisterCallback(callback) }
        } } finally { file.delete() }
    }
}
