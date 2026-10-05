package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.core.content.ContextCompat
import android.net.Uri
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.io.File

private fun Context.previewActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.previewActivity()
    else -> null
}

/** A small saved bookmark, never a retained Activity, player or file descriptor. */
internal class ArtifactMediaState {
    var position by mutableIntStateOf(0)
    var duration by mutableIntStateOf(0)
    var speed by mutableFloatStateOf(1f)
    var muted by mutableStateOf(false)
    var fullscreen by mutableStateOf(false)
    var controlFailure by mutableStateOf<String?>(null)
    var trackFailure by mutableStateOf<String?>(null)
    var playRequested by mutableStateOf(false)
    var prepared by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
    var tracks by mutableStateOf<List<ArtifactMediaTrack>>(emptyList())
    var audioPreference by mutableStateOf(ArtifactMediaTracks.AUTO)
    var captionPreference by mutableStateOf(ArtifactMediaTracks.AUTO)
    var selectedAudio by mutableIntStateOf(-1)
    var selectedCaption by mutableIntStateOf(-1)
    var captionText by mutableStateOf<String?>(null)
    var captionScale by mutableFloatStateOf(1f)
    var foreground = false
    var view: ArtifactMediaView? = null
    fun capture() { view?.capturePosition() }
    companion object {
        fun saver(context: Context) = listSaver<ArtifactMediaState, Any>(save = {
            it.capture()
            // Configuration recreation may continue playback. Saved process/task
            // restoration must require a new Play gesture, like returning from Home.
            listOf(it.position, it.playRequested && context.previewActivity()?.isChangingConfigurations == true,
                it.speed, it.muted, it.fullscreen, it.audioPreference, it.captionPreference)
        }, restore = { ArtifactMediaState().apply {
            position = (it[0] as Int).coerceAtLeast(0); playRequested = it[1] as Boolean
            speed = ArtifactMediaControls.speed(it.getOrNull(2) as? Float ?: 1f)
            muted = it.getOrNull(3) as? Boolean ?: false; fullscreen = it.getOrNull(4) as? Boolean ?: false
            audioPreference = it.getOrNull(5) as? String ?: ArtifactMediaTracks.AUTO
            captionPreference = it.getOrNull(6) as? String ?: ArtifactMediaTracks.AUTO
        } })
    }
}

internal class ArtifactMediaView(context: Context, private val state: ArtifactMediaState) : VideoView(context) {
    private var seeking = false
    private var released = false
    private var player: MediaPlayer? = null
    private var appliedSpeed = 1f
    private var sourceFile: File? = null
    private var trackController: ArtifactMediaTrackController? = null
    private val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
    private val focus = ArtifactAudioFocusOwner(ArtifactAndroidAudioFocus(context, audioAttributes), ::focusChanged)
    private val mediaSession = ArtifactMediaSession(context, audioAttributes, state) {
        this.takeIf { !released && state.view === this }
    }
    private var listeningForDisconnect = false
    private val disconnect = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && !released && state.view === this@ArtifactMediaView) pause()
        }
    }
    init {
        // VideoView otherwise requests focus while merely preparing, without a loss listener.
        setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        setAudioAttributes(audioAttributes)
        state.view = this
        setOnPreparedListener { player ->
            if (!released && state.view === this) {
                this.player = player
                appliedSpeed = 1f
                seeking = false
                state.prepared = true
                state.duration = duration.coerceAtLeast(0)
                trackController?.close()
                trackController = ArtifactMediaTrackController(context, player, state) { !released && state.view === this && this.player === player }
                    .also { it.prepare() }
                applyVolume()
                player.setOnSeekCompleteListener {
                    if (!released && state.view === this && this.player === player) { seeking = false; capturePosition(); startIfRequested() }
                }
                if (state.position > 0) seekTo(state.position.coerceAtMost(duration.coerceAtLeast(0)))
                else startIfRequested()
            }
        }
        setOnInfoListener { _, what, _ ->
            if (!released && state.view === this && what == MediaPlayer.MEDIA_INFO_METADATA_UPDATE) trackController?.refresh()
            false
        }
        setOnCompletionListener { if (!released && state.view === this) {
            state.position = duration.coerceAtLeast(0); state.playRequested = false; abandonAudio()
            publishPlayback()
        } }
        setOnErrorListener { _, _, _ ->
            if (released || state.view !== this) return@setOnErrorListener true
            state.prepared = false; state.playRequested = false; abandonAudio()
            state.failure = "Android could not play this media format. Use Open in Viewer actions to choose another player."
            publishPlayback()
            true
        }
    }
    fun open(file: File, retainFocus: Boolean = false) {
        if (released) return
        sourceFile = file
        trackController?.close(); trackController = null
        if (!retainFocus) abandonAudio()
        player?.setOnSeekCompleteListener(null); player = null
        seeking = false; state.prepared = false; state.failure = null; state.controlFailure = null
        setVideoURI(Uri.fromFile(file))
        publishPlayback()
    }
    fun selectAudio(preference: String) {
        if (released || !state.prepared || (preference != ArtifactMediaTracks.AUTO &&
                state.tracks.none { it.kind == ArtifactTrackKind.AUDIO && it.key == preference })) return
        val file = sourceFile ?: return
        if (state.audioPreference == preference) return
        capturePosition()
        state.audioPreference = preference
        // Audio selection is only guaranteed in Prepared. Keep the focus lease,
        // including a transient suspension, while replacing this file's player.
        open(file, retainFocus = true)
    }
    fun selectCaption(preference: String) { trackController?.selectCaption(preference) }
    fun capturePosition() {
        if (!released && state.view === this && state.prepared && !seeking) state.position = currentPosition.coerceAtLeast(0)
        publishPlayback()
    }
    private fun publishPlayback() {
        if (!released) mediaSession.update(sourceFile?.name ?: "Media preview",
            state.prepared && runCatching { isPlaying }.getOrDefault(false), seeking)
    }
    override fun seekTo(msec: Int) {
        if (released) return
        state.position = if (state.prepared) ArtifactMediaControls.seek(msec, 0, state.duration) else msec.coerceAtLeast(0)
        seeking = true
        super.seekTo(state.position)
        publishPlayback()
    }
    override fun start() {
        if (released) return
        state.playRequested = true
        if (state.prepared && state.duration > 0 && state.position >= state.duration) seekTo(0)
        startIfRequested()
    }
    private fun startIfRequested() {
        if (released || !state.foreground || !state.prepared || seeking || !state.playRequested) { publishPlayback(); return }
        when (focus.acquire()) {
            ArtifactFocusPermission.GRANTED -> {
                state.controlFailure = null
                if (!listeningForDisconnect) {
                    ContextCompat.registerReceiver(context.applicationContext, disconnect,
                        IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
                    listeningForDisconnect = true
                }
                if (appliedSpeed != state.speed && !applySpeed(state.speed)) state.speed = appliedSpeed
                applyVolume()
                super.start()
            }
            ArtifactFocusPermission.SUSPENDED -> Unit // Wait for the existing lease; never steal focus back.
            ArtifactFocusPermission.DENIED -> {
                state.playRequested = false
                state.controlFailure = "Audio is unavailable right now. Tap Play to try again."
            }
        }
        publishPlayback()
    }
    override fun pause() { if (!released) {
        capturePosition(); state.playRequested = false; super.pause(); abandonAudio(); publishPlayback()
    } }
    private fun focusChanged(event: ArtifactFocusEvent) {
        if (released || state.view !== this) return
        when (event) {
            ArtifactFocusEvent.GAIN -> { applyVolume(); startIfRequested() }
            ArtifactFocusEvent.DUCK -> applyVolume()
            ArtifactFocusEvent.TRANSIENT_LOSS -> { capturePosition(); super.pause() }
            ArtifactFocusEvent.LOSS -> pause()
        }
        publishPlayback()
    }
    private fun applyVolume() {
        val level = if (state.muted) 0f else focus.volumeMultiplier
        player?.setVolume(level, level)
    }
    private fun abandonAudio() {
        focus.release()
        if (listeningForDisconnect) {
            context.applicationContext.unregisterReceiver(disconnect)
            listeningForDisconnect = false
        }
    }
    fun setSpeed(value: Float) {
        if (released || !state.prepared) return
        val speed = ArtifactMediaControls.speed(value)
        // PlaybackParams starts MediaPlayer. Defer it until playback owns focus.
        if (!state.playRequested || !state.foreground || seeking || !focus.canPlay || applySpeed(speed)) state.speed = speed
        publishPlayback()
    }
    private fun applySpeed(value: Float): Boolean {
        val current = player ?: return false
        return try {
            current.setVolume(0f, 0f)
            current.playbackParams = PlaybackParams().allowDefaults().setSpeed(value)
            appliedSpeed = value
            state.controlFailure = null
            true
        } catch (_: IllegalArgumentException) {
            state.controlFailure = "Playback speed isn't available for this file."; false
        } catch (_: IllegalStateException) {
            state.controlFailure = "Playback speed couldn't be changed. Try reopening this preview."; false
        } finally { runCatching { applyVolume() } }
    }
    fun toggleMute() {
        if (released || !state.prepared) return
        val next = !state.muted
        try {
            val level = if (next) 0f else focus.volumeMultiplier
            player?.setVolume(level, level); state.muted = next
        }
        catch (_: IllegalStateException) { state.controlFailure = "Volume couldn't be changed. Try reopening this preview." }
    }
    fun foreground(value: Boolean, changingConfiguration: Boolean = false) {
        state.foreground = value
        if (value) startIfRequested() else {
            capturePosition()
            if (!changingConfiguration) state.playRequested = false
            super.pause(); abandonAudio()
        }
        publishPlayback()
    }
    fun release() {
        if (released) return
        capturePosition(); released = true
        mediaSession.close()
        trackController?.close(); trackController = null
        abandonAudio(); focus.close()
        player?.setOnSeekCompleteListener(null); player = null
        setOnPreparedListener(null); setOnCompletionListener(null); setOnErrorListener(null); setOnInfoListener(null)
        stopPlayback()
        if (state.view === this) { state.prepared = false; state.view = null }
    }
}

@Composable
internal fun ChangesMediaPreview(file: File) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = rememberSaveable(file.absolutePath, saver = ArtifactMediaState.saver(context)) { ArtifactMediaState() }
    DisposableEffect(lifecycle, state) {
        state.foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    // A restored fullscreen Dialog may compose its player after ON_START.
                    state.foreground = true
                    state.view?.foreground(true)
                }
                Lifecycle.Event.ON_STOP -> {
                    val changing = context.previewActivity()?.isChangingConfigurations == true
                    state.foreground = false
                    if (!changing) state.playRequested = false
                    state.view?.foreground(false, changing)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { state.capture(); delay(250) }
        }
    }
    fun fullscreen(value: Boolean) {
        // Finish the old surface before composing the new host. Its release callback is idempotent.
        state.view?.release()
        state.fullscreen = value
    }
    if (state.fullscreen) {
        Box(Modifier.fillMaxSize())
        Dialog(onDismissRequest = { fullscreen(false) }, properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val view = LocalView.current
            val window = (view.parent as? DialogWindowProvider)?.window
            DisposableEffect(window) {
                val controller = window?.let { WindowCompat.getInsetsController(it, view) }
                controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(WindowInsetsCompat.Type.systemBars())
                onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
            }
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                ArtifactMediaContent(file, state, Modifier.safeDrawingPadding()) { fullscreen(false) }
            }
        }
    } else ArtifactMediaContent(file, state) { fullscreen(true) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtifactMediaContent(file: File, state: ArtifactMediaState, modifier: Modifier = Modifier, onFullscreen: () -> Unit) {
    var speedMenu by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var resumeAfterScrub by remember { mutableStateOf(false) }
    val shownPosition = scrub?.toInt() ?: state.position
    Column(modifier.fillMaxSize()) {
        state.failure?.let { failure ->
            ChangesNotice("Preview unavailable", failure) { state.view?.open(file) }
        }
        state.controlFailure?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        state.trackFailure?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            AndroidView(factory = { ArtifactMediaView(it, state).apply { open(file) } },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = "Media preview ${file.name}" },
                onRelease = { it.release() })
            if (!state.prepared && state.failure == null) CircularProgressIndicator()
            state.captionText?.takeIf { it.isNotBlank() }?.let { cue ->
                Text(cue, Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    .background(Color.Black.copy(alpha = .8f)).padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White, fontSize = (20f * state.captionScale).sp, textAlign = TextAlign.Center)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Slider(value = shownPosition.toFloat().coerceIn(0f, state.duration.coerceAtLeast(1).toFloat()),
                valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(), enabled = state.prepared && state.duration > 0,
                onValueChange = { value ->
                    if (scrub == null) { resumeAfterScrub = state.playRequested; state.view?.pause() }
                    scrub = value
                }, onValueChangeFinished = {
                    scrub?.let { state.view?.seekTo(it.toInt()); if (resumeAfterScrub) state.view?.start() }
                    scrub = null; resumeAfterScrub = false
                }, modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Playback position"
                    stateDescription = "${ArtifactMediaControls.time(shownPosition)} of ${ArtifactMediaControls.time(state.duration)}"
                })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(ArtifactMediaControls.time(shownPosition))
                Text(ArtifactMediaControls.time(state.duration))
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(enabled = state.prepared, modifier = Modifier.semantics { contentDescription = "Back 10 seconds" },
                onClick = { state.view?.seekTo(ArtifactMediaControls.seek(state.position, -10_000, state.duration)) }) { Text("−10s") }
            TextButton(enabled = state.prepared, onClick = { if (state.playRequested) state.view?.pause() else state.view?.start() }) {
                Text(if (state.playRequested) "Pause" else "Play")
            }
            TextButton(enabled = state.prepared, modifier = Modifier.semantics { contentDescription = "Forward 10 seconds" },
                onClick = { state.view?.seekTo(ArtifactMediaControls.seek(state.position, 10_000, state.duration)) }) { Text("+10s") }
            TextButton(enabled = state.prepared, onClick = { state.view?.pause(); state.view?.seekTo(0) }) { Text("Restart") }
        }
        ArtifactMediaTrackMenus(state)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Box {
                TextButton(enabled = state.prepared, onClick = { speedMenu = true },
                    modifier = Modifier.semantics { contentDescription = "Playback speed"; stateDescription = "${state.speed}×" }) {
                    Text("${state.speed}×")
                }
                DropdownMenu(speedMenu, { speedMenu = false }) {
                    ArtifactMediaControls.speeds.forEach { speed ->
                        DropdownMenuItem(text = { Text("${speed}×") }, trailingIcon = { if (state.speed == speed) Text("✓") },
                            onClick = { speedMenu = false; state.view?.setSpeed(speed) })
                    }
                }
            }
            TextButton(enabled = state.prepared, onClick = { state.view?.toggleMute() }) { Text(if (state.muted) "Unmute" else "Mute") }
            TextButton(onClick = onFullscreen) { Text(if (state.fullscreen) "Exit fullscreen" else "Fullscreen") }
        }
    }
}
