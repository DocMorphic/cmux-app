package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.widget.VideoView
import androidx.compose.foundation.layout.*
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
    var playRequested by mutableStateOf(false)
    var prepared by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
    var foreground = false
    var view: ArtifactMediaView? = null
    fun capture() { view?.capturePosition() }
    companion object {
        fun saver(context: Context) = listSaver<ArtifactMediaState, Any>(save = {
            it.capture()
            // Configuration recreation may continue playback. Saved process/task
            // restoration must require a new Play gesture, like returning from Home.
            listOf(it.position, it.playRequested && context.previewActivity()?.isChangingConfigurations == true,
                it.speed, it.muted, it.fullscreen)
        }, restore = { ArtifactMediaState().apply {
            position = (it[0] as Int).coerceAtLeast(0); playRequested = it[1] as Boolean
            speed = ArtifactMediaControls.speed(it.getOrNull(2) as? Float ?: 1f)
            muted = it.getOrNull(3) as? Boolean ?: false; fullscreen = it.getOrNull(4) as? Boolean ?: false
        } })
    }
}

internal class ArtifactMediaView(context: Context, private val state: ArtifactMediaState) : VideoView(context) {
    private var seeking = false
    private var released = false
    private var player: MediaPlayer? = null
    init {
        state.view = this
        setOnPreparedListener { player ->
            if (!released && state.view === this) {
                this.player = player
                seeking = false
                state.prepared = true
                state.duration = duration.coerceAtLeast(0)
                player.setVolume(if (state.muted) 0f else 1f, if (state.muted) 0f else 1f)
                if (state.speed != 1f && !applySpeed(state.speed, resume = false)) state.speed = 1f
                player.setOnSeekCompleteListener {
                    if (!released && state.view === this && this.player === player) { seeking = false; capturePosition(); startIfRequested() }
                }
                if (state.position > 0) seekTo(state.position.coerceAtMost(duration.coerceAtLeast(0)))
                else startIfRequested()
            }
        }
        setOnCompletionListener { if (!released && state.view === this) { state.position = duration.coerceAtLeast(0); state.playRequested = false } }
        setOnErrorListener { _, _, _ ->
            if (released || state.view !== this) return@setOnErrorListener true
            state.prepared = false; state.playRequested = false
            state.failure = "Android could not play this media format. Use Open in Viewer actions to choose another player."
            true
        }
    }
    fun open(file: File) {
        if (released) return
        player?.setOnSeekCompleteListener(null); player = null
        seeking = false; state.prepared = false; state.failure = null; state.controlFailure = null
        setVideoURI(Uri.fromFile(file))
    }
    fun capturePosition() {
        if (!released && state.view === this && state.prepared && !seeking) state.position = currentPosition.coerceAtLeast(0)
    }
    override fun seekTo(msec: Int) {
        if (released) return
        state.position = if (state.prepared) ArtifactMediaControls.seek(msec, 0, state.duration) else msec.coerceAtLeast(0)
        seeking = true
        super.seekTo(state.position)
    }
    override fun start() {
        if (released) return
        state.playRequested = true
        if (state.prepared && state.duration > 0 && state.position >= state.duration) seekTo(0)
        startIfRequested()
    }
    private fun startIfRequested() {
        if (!released && state.foreground && state.prepared && !seeking && state.playRequested) super.start()
    }
    override fun pause() { if (!released) { capturePosition(); state.playRequested = false; super.pause() } }
    fun setSpeed(value: Float) {
        val speed = ArtifactMediaControls.speed(value)
        if (state.prepared && applySpeed(speed, state.foreground && state.playRequested && !seeking)) state.speed = speed
    }
    private fun applySpeed(value: Float, resume: Boolean): Boolean {
        val current = player ?: return false
        return try {
            // A nonzero PlaybackParams speed starts MediaPlayer, including from Paused.
            // Silence that transition and immediately restore the user's paused state.
            current.setVolume(0f, 0f)
            current.playbackParams = PlaybackParams().allowDefaults().setSpeed(value)
            if (!resume) current.pause()
            state.controlFailure = null
            true
        } catch (_: IllegalArgumentException) {
            state.controlFailure = "Playback speed isn't available for this file."; false
        } catch (_: IllegalStateException) {
            state.controlFailure = "Playback speed couldn't be changed. Try reopening this preview."; false
        } finally {
            if (!resume) runCatching { current.pause() }
            runCatching { current.setVolume(if (state.muted) 0f else 1f, if (state.muted) 0f else 1f) }
        }
    }
    fun toggleMute() {
        if (released || !state.prepared) return
        val next = !state.muted
        try { player?.setVolume(if (next) 0f else 1f, if (next) 0f else 1f); state.muted = next }
        catch (_: IllegalStateException) { state.controlFailure = "Volume couldn't be changed. Try reopening this preview." }
    }
    fun foreground(value: Boolean, changingConfiguration: Boolean = false) {
        state.foreground = value
        if (value) startIfRequested() else {
            capturePosition()
            if (!changingConfiguration) state.playRequested = false
            super.pause()
        }
    }
    fun release() {
        if (released) return
        capturePosition(); released = true
        player?.setOnSeekCompleteListener(null); player = null
        setOnPreparedListener(null); setOnCompletionListener(null); setOnErrorListener(null)
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
                Lifecycle.Event.ON_START -> state.view?.foreground(true)
                Lifecycle.Event.ON_STOP -> state.view?.foreground(false, context.previewActivity()?.isChangingConfigurations == true)
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
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            AndroidView(factory = { ArtifactMediaView(it, state).apply { open(file) } },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = "Media preview ${file.name}" },
                onRelease = { it.release() })
            if (!state.prepared && state.failure == null) CircularProgressIndicator()
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
