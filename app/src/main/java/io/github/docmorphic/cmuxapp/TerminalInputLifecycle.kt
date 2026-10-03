package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Forget terminal/composer focus on background, without sending or deleting a draft. */
@Composable
internal fun RetireTerminalInputOnBackground(editor: TerminalKeyboardView?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestEditor = rememberUpdatedState(editor)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(lifecycle, focus, keyboard) {
        val observer = LifecycleEventObserver { _, event ->
            // PAUSE also covers temporary interruptions; only STOP ends input ownership.
            // Observe the event directly: a paused composition can coalesce stop/start state.
            if (event == Lifecycle.Event.ON_STOP) {
                latestEditor.value?.dispose()
                focus.clearFocus(force = true)
                keyboard?.hide()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
