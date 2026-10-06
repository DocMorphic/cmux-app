package io.github.docmorphic.cmuxapp

import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

internal class ArtifactPlaybackModel : ViewModel() {
    var entry: ArtifactPlaybackSessions.Entry? = null
    val player = ArtifactMediaState()
    override fun onCleared() { player.view?.release(); entry?.close(); entry = null }
}

/** Separate from terminal/browser Activities so their navigation cannot shrink into the PiP window. */
open class MediaPlaybackActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[ArtifactPlaybackModel::class.java] }
    private var pip by mutableStateOf(false)
    private var enteringPip by mutableStateOf(false)
    private var finishingPlayback = false
    private var initialPip = true
    private var admitted by mutableStateOf(false)
    private var checkOwner: Job? = null
    private val canPip get() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
        model.player.prepared && model.player.videoWidth > 0 && model.player.videoHeight > 0 && !finishingPlayback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (model.entry == null) {
            val entry = ArtifactPlaybackSessions.claim(intent.getStringExtra(ArtifactPlaybackSessions.EXTRA))
            if (entry == null) { finish(); return } // A killed process never resumes private media on its own.
            model.entry = entry
            entry.bookmark.applyTo(model.player)
            model.player.fullscreen = true
            model.player.systemPlaybackOwner = true
        }
        initialPip = savedInstanceState == null
        pip = isInPictureInPictureMode
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finishPlayback() }
        })
        setContent { CmuxTheme {
            if (!admitted) {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {}
                return@CmuxTheme
            }
            val state = model.player
            val compact = pip || enteringPip
            // Read observable values during composition so changes update auto-enter eligibility.
            val eligible = state.prepared && state.videoWidth > 0 && state.videoHeight > 0
            val playing = state.playRequested
            SideEffect {
                updatePip(eligible && playing)
                if (eligible && initialPip) {
                    initialPip = false
                    window.decorView.post { enterPip() }
                }
            }
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                Column(if (compact) Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding()) {
                    if (!compact) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(checkNotNull(model.entry).files.file.name, Modifier.weight(1f).padding(vertical = 12.dp), maxLines = 1)
                        TextButton(onClick = ::finishPlayback) { Text("Done") }
                    }
                    ArtifactMediaContent(checkNotNull(model.entry).files.file, state, Modifier.weight(1f),
                        showControls = !compact, onPictureInPicture = { enterPip() }, onFullscreen = ::finishPlayback)
                }
            }
        } }
        checkOwner = lifecycleScope.launch {
            while (isActive) {
                val admitted = withContext(Dispatchers.IO) {
                    runCatching { ArtifactPlaybackSessions.account(applicationContext) == model.entry?.account }.getOrDefault(false)
                }
                if (!admitted) { this@MediaPlaybackActivity.admitted = false; finishPlayback(); break }
                this@MediaPlaybackActivity.admitted = true
                model.player.capture()
                delay(500)
            }
        }
    }

    private fun params(autoEnter: Boolean): PictureInPictureParams {
        val state = model.player
        val ratio = state.videoWidth.toFloat() / state.videoHeight.coerceAtLeast(1)
        val aspect = when {
            ratio > 2.39f -> Rational(239, 100)
            ratio < 1f / 2.39f -> Rational(100, 239)
            else -> Rational(state.videoWidth.coerceAtLeast(1), state.videoHeight.coerceAtLeast(1))
        }
        val builder = PictureInPictureParams.Builder().setAspectRatio(aspect)
        val rect = Rect()
        if (state.view?.getGlobalVisibleRect(rect) == true && !rect.isEmpty) builder.setSourceRectHint(rect)
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(true)
        return builder.build()
    }
    private fun updatePip(autoEnter: Boolean) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { setPictureInPictureParams(params(autoEnter && canPip)) }
    }
    internal fun enterPip() {
        if (!canPip) return
        val accepted = runCatching { enterPictureInPictureMode(params(false)) }.getOrDefault(false)
        if (!accepted) model.player.controlFailure = "Picture-in-picture isn't available. You can keep watching here."
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && canPip && model.player.playRequested) enterPip()
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode; enteringPip = false
    }
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= 35 && pipState.isTransitioningToPip) enteringPip = true
    }
    override fun onStart() {
        super.onStart()
        model.player.foreground = true; model.player.view?.foreground(true)
    }
    override fun onStop() {
        // PiP is still STARTED while visible. A real stop means it is hidden/closed.
        model.player.view?.foreground(false, isChangingConfigurations)
        model.player.foreground = false
        if (!isChangingConfigurations) model.player.playRequested = false
        publishResult()
        if (isInPictureInPictureMode && !isChangingConfigurations) finishPlayback()
        super.onStop()
    }
    private fun publishResult() {
        if (model.entry == null) return
        setResult(RESULT_OK, ArtifactPlaybackBookmark.capture(model.player).result())
    }
    private fun finishPlayback() {
        if (finishingPlayback) return
        finishingPlayback = true
        checkOwner?.cancel(); updatePip(false)
        model.player.view?.pause(); publishResult(); finish()
    }
    override fun onDestroy() {
        checkOwner?.cancel()
        if (!isChangingConfigurations) model.player.view?.pause()
        super.onDestroy()
    }
}

/** Browser previews use the same implementation and a handoff local to their renderer process. */
class BrowserMediaPlaybackActivity : MediaPlaybackActivity()
