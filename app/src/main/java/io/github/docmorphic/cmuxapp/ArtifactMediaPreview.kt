package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File

private fun Context.previewActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.previewActivity()
    else -> null
}

/** A small saved bookmark, never a retained Activity, player or file descriptor. */
internal class ArtifactMediaState {
    var position = 0
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
            listOf(it.position, it.playRequested && context.previewActivity()?.isChangingConfigurations == true)
        }, restore = { ArtifactMediaState().apply { position = it[0] as Int; playRequested = it[1] as Boolean } })
    }
}

internal class ArtifactMediaView(context: Context, private val state: ArtifactMediaState) : VideoView(context) {
    private var seeking = false
    private var released = false
    init {
        state.view = this
        setMediaController(MediaController(context).also { it.setAnchorView(this) })
        setOnPreparedListener { player ->
            if (!released) {
                state.prepared = true
                player.setOnSeekCompleteListener {
                    if (!released) { seeking = false; capturePosition(); startIfRequested() }
                }
                if (state.position > 0) seekTo(state.position.coerceAtMost(duration.coerceAtLeast(0)))
                else startIfRequested()
            }
        }
        setOnCompletionListener { state.position = duration.coerceAtLeast(0); state.playRequested = false }
        setOnErrorListener { _, _, _ ->
            state.prepared = false; state.playRequested = false
            state.failure = "Android could not play this media format. Use Open in Viewer actions to choose another player."
            true
        }
    }
    fun open(file: File) { setVideoURI(Uri.fromFile(file)) }
    fun capturePosition() {
        if (!released && state.prepared && !seeking) state.position = currentPosition.coerceAtLeast(0)
    }
    override fun seekTo(msec: Int) {
        state.position = msec.coerceAtLeast(0)
        seeking = true
        super.seekTo(state.position)
    }
    override fun start() { state.playRequested = true; startIfRequested() }
    private fun startIfRequested() {
        if (!released && state.foreground && state.prepared && !seeking && state.playRequested) super.start()
    }
    override fun pause() { capturePosition(); state.playRequested = false; super.pause() }
    fun foreground(value: Boolean, changingConfiguration: Boolean = false) {
        state.foreground = value
        if (value) startIfRequested() else {
            capturePosition()
            if (!changingConfiguration) state.playRequested = false
            super.pause()
        }
    }
    fun release() {
        capturePosition(); released = true
        setOnPreparedListener(null); setOnCompletionListener(null); setOnErrorListener(null)
        stopPlayback(); state.prepared = false
        if (state.view === this) state.view = null
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
    Column(Modifier.fillMaxSize()) {
        state.failure?.let { ChangesNotice("Preview unavailable", it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(enabled = state.prepared, onClick = { if (state.playRequested) state.view?.pause() else state.view?.start() }) {
                Text(if (state.playRequested) "Pause" else "Play")
            }
            TextButton(enabled = state.prepared, onClick = { state.view?.pause(); state.view?.seekTo(0) }) { Text("Restart") }
        }
        AndroidView(factory = { ArtifactMediaView(it, state).apply { open(file) } },
            modifier = Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = "Media preview ${file.name}" },
            onRelease = { it.release() })
    }
}
