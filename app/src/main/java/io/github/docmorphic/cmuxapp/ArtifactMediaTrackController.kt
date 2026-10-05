package io.github.docmorphic.cmuxapp

import android.content.Context
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.accessibility.CaptioningManager
import java.util.Locale

/** Platform selection/rendering remains owned by this specific prepared player. */
internal class ArtifactMediaTrackController(context: Context, private val player: MediaPlayer,
    private val state: ArtifactMediaState, private val current: () -> Boolean) : AutoCloseable {
    private val captions = context.getSystemService(CaptioningManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var selectionGeneration = 0L
    private val settings = object : CaptioningManager.CaptioningChangeListener() {
        override fun onEnabledChanged(enabled: Boolean) = settingsChanged()
        override fun onLocaleChanged(locale: Locale?) = settingsChanged()
        override fun onFontScaleChanged(fontScale: Float) = settingsChanged()
    }
    private fun owned() = !closed && current()
    fun prepare() {
        if (!owned()) return
        state.trackFailure = null
        captions.addCaptioningChangeListener(settings)
        player.setOnTimedTextListener { source, text ->
            if (owned() && source === player && state.captionPreference != ArtifactMediaTracks.OFF)
                state.captionText = text?.text
        }
        refresh()
        try {
            val requested = state.tracks.firstOrNull { it.kind == ArtifactTrackKind.AUDIO && it.key == state.audioPreference }
            if (requested != null) player.selectTrack(requested.index)
            else if (state.audioPreference != ArtifactMediaTracks.AUTO) {
                state.audioPreference = ArtifactMediaTracks.AUTO
                state.trackFailure = "The selected audio track is no longer available. Using the default audio."
            }
            captureSelection()
        } catch (_: RuntimeException) { state.trackFailure = "This audio track couldn't be selected. Choose another track or reopen the preview." }
    }
    fun refresh() {
        if (!owned()) return
        try {
            state.tracks = player.trackInfo.mapIndexedNotNull { index, info ->
                val kind = when (info.trackType) {
                    MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO -> ArtifactTrackKind.AUDIO
                    MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE -> ArtifactTrackKind.SUBTITLE
                    MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT -> ArtifactTrackKind.TIMED_TEXT
                    else -> return@mapIndexedNotNull null
                }
                val format = info.format
                fun flag(key: String, fallback: Boolean) = runCatching {
                    if (format?.containsKey(key) == true) format.getInteger(key) != 0 else fallback
                }.getOrDefault(fallback)
                ArtifactMediaTrack(index, kind, ArtifactMediaTracks.language(info.language),
                    runCatching { format?.getString(MediaFormat.KEY_MIME).orEmpty().take(96) }.getOrDefault(""),
                    flag(MediaFormat.KEY_IS_DEFAULT, false), flag(MediaFormat.KEY_IS_FORCED_SUBTITLE, false),
                    flag(MediaFormat.KEY_IS_AUTOSELECT, true))
            }
            settingsChanged()
            captureSelection()
        } catch (_: RuntimeException) { state.trackFailure = "Track options couldn't be loaded. Reopen the preview to try again." }
    }
    fun selectCaption(preference: String) {
        if (!owned() || !state.prepared || (preference !in listOf(ArtifactMediaTracks.AUTO, ArtifactMediaTracks.OFF) &&
                state.tracks.none { it.caption && it.key == preference })) return
        state.captionPreference = preference
        state.trackFailure = null
        applyCaption()
    }
    private fun settingsChanged() {
        if (!owned()) return
        state.captionScale = captions.fontScale.takeIf { it.isFinite() }?.coerceIn(.5f, 3f) ?: 1f
        applyCaption()
    }
    private fun applyCaption() {
        if (!owned()) return
        val generation = ++selectionGeneration
        val desired = ArtifactMediaTracks.caption(state.tracks, state.captionPreference, captions.isEnabled,
            captions.locale ?: Locale.getDefault())
        if (state.captionPreference !in listOf(ArtifactMediaTracks.AUTO, ArtifactMediaTracks.OFF) &&
            state.tracks.none { it.caption && it.key == state.captionPreference }) state.captionPreference = ArtifactMediaTracks.AUTO
        try {
            state.captionText = null
            requestCaption(desired?.index ?: -1)
            // SubtitleController may use multiple asynchronous handler hops.
            // A newer choice or retired player invalidates this confirmation.
            confirmCaption(generation, desired?.index ?: -1, attempts = 20)
        } catch (_: RuntimeException) { state.trackFailure = "Subtitles couldn't be changed. Reopen the preview to try again." }
    }
    private fun requestCaption(desired: Int) {
        val selected = listOf(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE, MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)
            .map { player.getSelectedTrack(it) }.filter { it >= 0 }.distinct()
        selected.filter { it != desired }.forEach(player::deselectTrack)
        if (desired >= 0 && desired !in selected) player.selectTrack(desired)
    }
    private fun confirmCaption(generation: Long, desired: Int, attempts: Int) {
        handler.postDelayed({
            if (owned() && generation == selectionGeneration) {
                try {
                    captureSelection()
                    if (state.selectedCaption != desired) {
                        if (attempts > 1) {
                            // A previous native selection may finish after a newer Off/Auto choice.
                            requestCaption(desired)
                            confirmCaption(generation, desired, attempts - 1)
                        }
                        else state.trackFailure = "This subtitle track couldn't be selected. Try another subtitle option."
                    }
                } catch (_: RuntimeException) {
                    state.trackFailure = "Subtitles couldn't be changed. Reopen the preview to try again."
                }
            }
        }, 50L)
    }
    private fun captureSelection() {
        if (!owned()) return
        state.selectedAudio = player.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO)
        state.selectedCaption = listOf(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE, MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT)
            .map { player.getSelectedTrack(it) }.firstOrNull { it >= 0 } ?: -1
    }
    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        captions.removeCaptioningChangeListener(settings)
        player.setOnTimedTextListener(null)
        state.captionText = null
    }
}
