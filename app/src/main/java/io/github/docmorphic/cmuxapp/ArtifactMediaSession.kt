package io.github.docmorphic.cmuxapp

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** One foreground preview owns one session; no command can resurrect a retired player. */
internal class ArtifactMediaSession(
    context: Context,
    attributes: AudioAttributes,
    private val state: ArtifactMediaState,
    private val current: () -> ArtifactMediaView?,
) : AutoCloseable {
    private val session = MediaSession(context.applicationContext, "cmux artifact preview")
    private var closed = false
    private var hasPlayed = false
    private var metadata: Pair<String, Int>? = null
    private var lastPlayback: Triple<Int, Long, Float>? = null
    private var lastActions = -1L
    private fun target(): ArtifactMediaView? =
        if (!closed && session.isActive && state.foreground && state.prepared && state.failure == null) current() else null

    init {
        session.setPlaybackToLocal(attributes)
        // These flags are implicit on recent Android and needed by older supported versions.
        @Suppress("DEPRECATION")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { target()?.start() }
            override fun onPause() { target()?.pause() }
            override fun onStop() { target()?.let { it.pause(); it.seekTo(0) } }
            override fun onSeekTo(pos: Long) {
                target()?.seekTo(pos.coerceIn(0L, state.duration.coerceAtLeast(0).toLong()).toInt())
            }
            override fun onFastForward() { skip(10_000) }
            override fun onRewind() { skip(-10_000) }
            override fun onSetPlaybackSpeed(speed: Float) {
                if (speed in ArtifactMediaControls.speeds) target()?.setSpeed(speed)
            }
            private fun skip(delta: Int) {
                target()?.let { it.capturePosition(); it.seekTo(ArtifactMediaControls.seek(state.position, delta, state.duration)) }
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun update(title: String, playing: Boolean, seeking: Boolean) {
        if (closed) return
        if (playing) hasPlayed = true
        val available = current() != null && state.foreground && state.failure == null
        val active = available && hasPlayed
        if (session.isActive != active) session.isActive = active
        val nextMetadata = title to state.duration
        if (metadata != nextMetadata) {
            metadata = nextMetadata
            session.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.duration.coerceAtLeast(0).toLong()).build())
        }
        val status = when {
            !available -> PlaybackState.STATE_STOPPED
            !state.prepared || seeking -> PlaybackState.STATE_BUFFERING
            playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        val actions = if (active && state.prepared) PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_SET_PLAYBACK_SPEED else 0L
        val position = state.position.coerceAtLeast(0).toLong()
        val speed = if (playing && !seeking) state.speed else 0f
        val nextPlayback = Triple(status, position, speed)
        if (nextPlayback != lastPlayback || actions != lastActions) {
            lastPlayback = nextPlayback; lastActions = actions
            session.setPlaybackState(PlaybackState.Builder().setActions(actions)
                .setState(status, position, speed, SystemClock.elapsedRealtime()).build())
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        session.isActive = false
        session.setCallback(null)
        session.release()
    }
}
