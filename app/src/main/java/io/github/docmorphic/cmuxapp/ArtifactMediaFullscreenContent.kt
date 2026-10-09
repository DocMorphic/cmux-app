package io.github.docmorphic.cmuxapp

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.io.File

/** Controls never take layout space from the fullscreen video surface. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtifactMediaFullscreenContent(file: File, state: ArtifactMediaState, modifier: Modifier,
    showControls: Boolean, pipBusy: Boolean, onPictureInPicture: (() -> Unit)?,
    fullscreenLabel: String, onFullscreen: () -> Unit) {
    var visible by remember(file) { mutableStateOf(true) }
    var interaction by remember(file) { mutableIntStateOf(0) }
    var speedMenu by remember { mutableStateOf(false) }
    var trackMenu by remember { mutableStateOf(false) }
    var topFocused by remember { mutableStateOf(false) }
    var transportFocused by remember { mutableStateOf(false) }
    var timelineFocused by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var resumeAfterScrub by remember { mutableStateOf(false) }
    val focused = topFocused || transportFocused || timelineFocused
    val accessibility = LocalAccessibilityManager.current
    val manager = LocalContext.current.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    var exploration by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { exploration = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    fun reveal() { visible = true; interaction++ }
    LaunchedEffect(state.playRequested, state.failure, state.controlFailure, state.trackFailure, exploration) {
        if (!state.playRequested || state.failure != null || state.controlFailure != null || state.trackFailure != null || exploration) reveal()
    }
    LaunchedEffect(visible, interaction, state.playRequested, state.prepared, speedMenu, trackMenu,
        focused, touching, scrub != null, exploration, showControls, state.failure, state.controlFailure, state.trackFailure) {
        if (showControls && visible && state.playRequested && state.prepared && !speedMenu && !trackMenu &&
            !focused && !touching && scrub == null && !exploration && state.failure == null && state.controlFailure == null && state.trackFailure == null) {
            val generation = interaction
            delay(accessibility?.calculateRecommendedTimeoutMillis(3000L, containsText = true, containsControls = true) ?: 3000L)
            // Touch/menu changes can precede recomposition and effect cancellation.
            // Recheck the live state before an older timeout hides these controls.
            if (interaction == generation && visible && showControls && state.playRequested && state.prepared &&
                !speedMenu && !trackMenu && !topFocused && !transportFocused && !timelineFocused &&
                !touching && scrub == null && !exploration && state.failure == null &&
                state.controlFailure == null && state.trackFailure == null) visible = false
        }
    }
    val overlay = showControls && visible
    val shownPosition = scrub?.toInt() ?: state.position
    Box(modifier.fillMaxSize().pointerInput(showControls, overlay) {
        if (showControls) awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touching = true
            // The timeout can expire before the next frame removes the controls.
            // A touch on that still-rendered overlay keeps it visible through release.
            if (overlay) reveal() else interaction++
            try { do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed }) }
            finally { touching = false; interaction++ }
        }
    }, contentAlignment = Alignment.Center) {
        // Keep the native surface outside subcomposition and control visibility.
        // VideoView measures its own aspect-fit bounds, including during rotation.
        AndroidView(factory = { ArtifactMediaView(it, state).apply { open(file) } },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Media preview ${file.name}" },
            onRelease = { it.release() })
        if (!state.prepared && state.failure == null) CircularProgressIndicator(color = Color.White)
        if (showControls) Box(Modifier.matchParentSize().semantics {
            // Use the same composed snapshot as the buttons, so accessibility
            // cannot announce hidden controls while the previous frame still draws them.
            contentDescription = if (overlay) "Hide playback controls" else "Show playback controls"
        }.clickable {
            if (exploration || !overlay) reveal() else { visible = false; interaction++ }
        })
        // Above the transparent tap target: otherwise its full-screen semantics
        // rectangle occludes rendered captions from accessibility traversal.
        state.captionText?.takeIf { it.isNotBlank() }?.let { cue ->
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val ratio = state.videoWidth.toFloat() / state.videoHeight.coerceAtLeast(1)
                val frame = if (state.videoWidth > 0 && state.videoHeight > 0) {
                    val width = minOf(maxWidth.value, maxHeight.value * ratio)
                    Modifier.size(width.dp, (width / ratio).dp)
                } else Modifier.fillMaxSize()
                // Lift captions only where the timeline overlaps the actual frame.
                val height = if (ratio > 0) minOf(maxHeight.value, maxWidth.value / ratio) else maxHeight.value
                val bottomGap = (maxHeight.value - height) / 2
                val captionPadding = if (overlay) maxOf(16f, 112f - bottomGap) else 16f
                Box(frame, contentAlignment = Alignment.Center) {
                    Text(cue, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp,
                        bottom = captionPadding.dp).background(Color.Black.copy(alpha = .8f))
                        .padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White,
                        fontSize = (20f * state.captionScale).sp, textAlign = TextAlign.Center)
                }
            }
        }
        if (overlay) MaterialTheme(colorScheme = darkColorScheme(primary = Color.White, secondary = Color.White)) {
            // A scrollable menu surface keeps every option reachable with enlarged text
            // and on short displays. The transport bar remains independently accessible.
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().heightIn(max = 200.dp)
                .onFocusChanged { topFocused = it.hasFocus }.focusGroup().verticalScroll(rememberScrollState())
                .background(Color.Black.copy(alpha = .72f)).padding(horizontal = 8.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onFullscreen) { Text(fullscreenLabel) }
                    Text(file.name, Modifier.widthIn(max = 180.dp).padding(12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ArtifactMediaTrackButtons(state) { trackMenu = it; if (it) reveal() else interaction++ }
                    Box {
                        TextButton(enabled = state.prepared, onClick = { reveal(); speedMenu = true },
                            modifier = Modifier.semantics { contentDescription = "Playback speed"; stateDescription = "${state.speed}×" }) { Text("${state.speed}×") }
                        DropdownMenu(speedMenu, { speedMenu = false; reveal() }) {
                            ArtifactMediaControls.speeds.forEach { speed ->
                                DropdownMenuItem(text = { Text("${speed}×") }, trailingIcon = { if (state.speed == speed) Text("✓") },
                                    onClick = { state.view?.setSpeed(speed); speedMenu = false; reveal() })
                            }
                        }
                    }
                    TextButton(enabled = state.prepared, onClick = { state.view?.toggleMute(); reveal() }) { Text(if (state.muted) "Unmute" else "Mute") }
                    if (onPictureInPicture != null && state.videoWidth > 0 && state.videoHeight > 0)
                        TextButton(enabled = state.prepared && !pipBusy, onClick = { reveal(); onPictureInPicture() }) {
                            Text(if (pipBusy) "Opening player…" else "Picture in picture")
                        }
                }
                state.failure?.let { ChangesNotice("Preview unavailable", it) { state.view?.open(file); reveal() } }
                state.controlFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.trackFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            FlowRow(Modifier.align(Alignment.Center).onFocusChanged { transportFocused = it.hasFocus }.focusGroup()
                .background(Color.Black.copy(alpha = .65f)), horizontalArrangement = Arrangement.Center) {
                TextButton(enabled = state.prepared, modifier = Modifier.semantics { contentDescription = "Back 10 seconds" },
                    onClick = { state.view?.seekTo(ArtifactMediaControls.seek(state.position, -10_000, state.duration)); reveal() }) { Text("−10s") }
                TextButton(enabled = state.prepared, onClick = { if (state.playRequested) state.view?.pause() else state.view?.start(); reveal() }) {
                    Text(if (state.playRequested) "Pause" else "Play")
                }
                TextButton(enabled = state.prepared, modifier = Modifier.semantics { contentDescription = "Forward 10 seconds" },
                    onClick = { state.view?.seekTo(ArtifactMediaControls.seek(state.position, 10_000, state.duration)); reveal() }) { Text("+10s") }
                TextButton(enabled = state.prepared, onClick = { state.view?.pause(); state.view?.seekTo(0); reveal() }) { Text("Restart") }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onFocusChanged { timelineFocused = it.hasFocus }.focusGroup()
                .background(Color.Black.copy(alpha = .72f)).padding(horizontal = 16.dp, vertical = 4.dp)) {
                Slider(value = shownPosition.toFloat().coerceIn(0f, state.duration.coerceAtLeast(1).toFloat()),
                    valueRange = 0f..state.duration.coerceAtLeast(1).toFloat(), enabled = state.prepared && state.duration > 0,
                    onValueChange = { value ->
                        if (scrub == null) { resumeAfterScrub = state.playRequested; state.view?.pause() }
                        scrub = value; reveal()
                    }, onValueChangeFinished = {
                        scrub?.let { state.view?.seekTo(it.toInt()); if (resumeAfterScrub) state.view?.start() }
                        scrub = null; resumeAfterScrub = false; reveal()
                    }, modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Playback position"
                        stateDescription = "${ArtifactMediaControls.time(shownPosition)} of ${ArtifactMediaControls.time(state.duration)}"
                    })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(ArtifactMediaControls.time(shownPosition)); Text(ArtifactMediaControls.time(state.duration))
                }
            }
        }
    }
}
