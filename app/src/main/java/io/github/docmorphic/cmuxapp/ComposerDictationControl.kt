package io.github.docmorphic.cmuxapp

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import android.view.ViewTreeObserver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal val LocalComposerSpeechService = staticCompositionLocalOf<ComposerSpeechService?> { null }

@Composable
internal fun rememberComposerDictation(owner: Any?, enabled: Boolean, readText: () -> String,
    writeText: (String) -> Boolean, isCurrent: () -> Boolean = { true }): ComposerDictation {
    val context = LocalContext.current.applicationContext
    val service = LocalComposerSpeechService.current ?: remember(context) { AndroidComposerSpeech(context) }
    val scope = rememberCoroutineScope()
    val latestOwner by rememberUpdatedState(owner)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestRead by rememberUpdatedState(readText)
    val latestWrite by rememberUpdatedState(writeText)
    val latestCurrent by rememberUpdatedState(isCurrent)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val controller = remember(owner, service, lifecycle) {
        ComposerDictation(scope, service::create,
            { latestOwner == owner && latestEnabled && latestCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
            { latestRead() }, { latestWrite(it) })
    }
    DisposableEffect(controller, lifecycle, view) {
        val tree = view.viewTreeObserver
        val windowFocus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (!focused && controller.state.value.phase in setOf(ComposerDictation.Phase.STARTING,
                    ComposerDictation.Phase.LISTENING, ComposerDictation.Phase.STOPPING)) controller.cancel()
        }
        tree.addOnWindowFocusChangeListener(windowFocus)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.cancel() }
        lifecycle.addObserver(observer)
        onDispose {
            if (tree.isAlive) tree.removeOnWindowFocusChangeListener(windowFocus)
            lifecycle.removeObserver(observer); controller.close()
        }
    }
    LaunchedEffect(controller, enabled) { if (!enabled) controller.cancel() }
    return controller
}

@Composable
internal fun ComposerDictationButton(controller: ComposerDictation, enabled: Boolean, beforeStart: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val service = LocalComposerSpeechService.current ?: remember(context) { AndroidComposerSpeech(context) }
    val state by controller.state.collectAsState()
    var permission by remember { mutableStateOf<Pair<ComposerDictation, Long>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = permission; permission = null
        request?.first?.permission(request.second, granted)
    }
    // The native screen may stay mounted behind another destination; the visible mic owns capture.
    DisposableEffect(controller) { onDispose { controller.cancel() } }
    val label = when (state.phase) {
        ComposerDictation.Phase.PERMISSION, ComposerDictation.Phase.STARTING -> "Cancel dictation"
        ComposerDictation.Phase.LISTENING -> "Stop dictation"
        ComposerDictation.Phase.STOPPING -> "Finish dictation"
        else -> "Start dictation"
    }
    ComposerIconButton(onClick = {
        when (state.phase) {
            ComposerDictation.Phase.IDLE -> {
                val token = controller.request() ?: return@ComposerIconButton
                beforeStart()
                if (service.permissionGranted) controller.permission(token, true)
                else if (permission == null) {
                    permission = controller to token
                    try { launcher.launch(Manifest.permission.RECORD_AUDIO) }
                    catch (_: Exception) { permission = null; controller.permission(token, false) }
                } else controller.cancel()
            }
            ComposerDictation.Phase.LISTENING -> controller.stop()
            else -> controller.cancel()
        }
    }, enabled = state.locksField || (enabled && service.available && state.phase != ComposerDictation.Phase.CLOSED),
        active = state.locksField, modifier = Modifier.testTag("composer.dictation").semantics {
            stateDescription = when (state.phase) {
                ComposerDictation.Phase.PERMISSION -> "Waiting for microphone permission"
                ComposerDictation.Phase.STARTING -> "Starting dictation"
                ComposerDictation.Phase.LISTENING -> "Listening"
                ComposerDictation.Phase.STOPPING -> "Finishing dictation"
                else -> if (service.available) "Not listening" else "Speech recognition unavailable"
            }
            liveRegion = LiveRegionMode.Polite
        }) {
        Icon(painterResource(R.drawable.ic_composer_mic), label, Modifier.size(22.dp),
            tint = if (state.locksField) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
