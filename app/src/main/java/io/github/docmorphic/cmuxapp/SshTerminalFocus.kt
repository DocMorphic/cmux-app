package io.github.docmorphic.cmuxapp

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun ObserveSshTerminalFocus(input: SshTerminalInteraction, enabled: Boolean) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val admitted = rememberUpdatedState(enabled)
    DisposableEffect(input, view, lifecycle, enabled) {
        var active = false
        fun update() {
            val next = admitted.value && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && view.hasWindowFocus()
            if (next != active) {
                active = next
                if (admitted.value) input.focus(next)
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        val window = ViewTreeObserver.OnWindowFocusChangeListener { update() }
        val tree = view.viewTreeObserver
        lifecycle.addObserver(observer)
        tree.addOnWindowFocusChangeListener(window)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            if (tree.isAlive) tree.removeOnWindowFocusChangeListener(window)
            if (active && admitted.value) input.focus(false)
        }
    }
}
