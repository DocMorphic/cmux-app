package io.github.docmorphic.cmuxapp

import android.content.BroadcastReceiver
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** Real platform focus requests; the protected disconnect broadcast is injected locally. */
class ArtifactAudioInterruptionTest {
    private fun View.media(): ArtifactMediaView? = when (this) {
        is ArtifactMediaView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).media() }
        else -> null
    }
    @Test fun focusLossResumePauseAndHeadphoneDisconnectRespectPlaybackIntent() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "audio-interruptions.wav")
        val bytes = 16_000 * 2 * 120
        file.writeBytes(ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(bytes)
        }.array())
        val manager = context.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        fun request(type: Int) = AudioFocusRequest.Builder(type).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }.build()
        val transient = request(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        val permanent = request(AudioManager.AUDIOFOCUS_GAIN)
        fun await(message: String, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < until) { if (condition()) return; Thread.sleep(75) }
            fail(message)
        }
        try { ActivityScenario.launch<ArtifactPreviewTestActivity>(Intent(context, ArtifactPreviewTestActivity::class.java)
            .putExtra("path", file.absolutePath).putExtra("route", ChangesPreviewRoute.MEDIA.name)
            .putExtra("mime", "audio/wav")).use { scenario ->
            fun read(block: (ArtifactMediaView, ArtifactMediaState, ArtifactAudioFocusOwner) -> Boolean): Boolean {
                var result = false
                scenario.onActivity { activity -> activity.window.decorView.media()?.let { view ->
                    val state = ArtifactMediaView::class.java.getDeclaredField("state").apply { isAccessible = true }.get(view) as ArtifactMediaState
                    val focus = ArtifactMediaView::class.java.getDeclaredField("focus").apply { isAccessible = true }.get(view) as ArtifactAudioFocusOwner
                    result = block(view, state, focus)
                } }
                return result
            }
            fun act(block: (ArtifactMediaView) -> Unit) { scenario.onActivity { block(it.window.decorView.media()!!) } }
            fun takeFocus(request: AudioFocusRequest) { instrumentation.runOnMainSync {
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(request))
            } }
            await("Preview did not prepare") { read { _, state, _ -> state.prepared } }
            assertTrue(read { view, _, focus -> !view.isPlaying && !focus.canPlay })
            act { it.setSpeed(2f) }
            assertTrue(read { view, _, focus -> !view.isPlaying && !focus.canPlay })
            act { it.start() }; await("Play did not acquire focus") { read { view, _, focus -> view.isPlaying && focus.canPlay } }
            takeFocus(transient)
            await("Transient interruption did not pause") { read { view, state, focus -> !view.isPlaying && state.playRequested && !focus.canPlay } }
            manager.abandonAudioFocusRequest(transient)
            await("Focus gain did not resume requested playback") { read { view, _, focus -> view.isPlaying && focus.canPlay } }
            takeFocus(transient)
            await("Second interruption did not pause") { read { view, _, focus -> !view.isPlaying && !focus.canPlay } }
            act { it.pause() }; manager.abandonAudioFocusRequest(transient); Thread.sleep(350)
            assertTrue(read { view, state, focus -> !view.isPlaying && !state.playRequested && !focus.canPlay })
            act { it.start() }; await("Explicit replay failed") { read { view, _, _ -> view.isPlaying } }
            takeFocus(permanent)
            await("Permanent loss kept playback requested") { read { view, state, focus -> !view.isPlaying && !state.playRequested && !focus.canPlay } }
            manager.abandonAudioFocusRequest(permanent); Thread.sleep(350)
            assertTrue(read { view, state, _ -> !view.isPlaying && !state.playRequested })
            act { it.start() }; await("Replay after permanent loss failed") { read { view, _, _ -> view.isPlaying } }
            act { view ->
                val receiver = ArtifactMediaView::class.java.getDeclaredField("disconnect").apply { isAccessible = true }.get(view) as BroadcastReceiver
                receiver.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            }
            assertTrue(read { view, state, focus -> !view.isPlaying && !state.playRequested && !focus.canPlay })
        } } finally {
            manager.abandonAudioFocusRequest(transient); manager.abandonAudioFocusRequest(permanent); file.delete()
        }
    }
}
