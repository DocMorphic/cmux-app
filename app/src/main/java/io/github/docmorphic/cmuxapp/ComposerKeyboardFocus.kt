package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView

/** Explicit focus intent belongs to the currently mounted composer, never saved navigation state. */
internal class ComposerKeyboardFocus {
    val requester = FocusRequester()
    var pending by mutableStateOf<Any?>(null); private set
    fun request() { pending = Any() }
    fun cancel() { pending = null }
}

@Composable
internal fun ComposerKeyboardFocusEffect(focus: ComposerKeyboardFocus, locked: Boolean) {
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val pending = focus.pending
    LaunchedEffect(focus, pending, locked) {
        if (pending != null && !locked) {
            // Send can unlock a read-only dictation field in this composition. Its editable
            // input connection must be installed before requesting the system IME.
            withFrameNanos { }
            if (focus.pending === pending) {
                focus.cancel()
                if (view.hasWindowFocus()) {
                    focus.requester.requestFocus()
                    keyboard?.show()
                }
            }
        }
    }
    DisposableEffect(focus) { onDispose { focus.cancel() } }
}
