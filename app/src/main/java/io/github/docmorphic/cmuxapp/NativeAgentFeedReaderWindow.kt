package io.github.docmorphic.cmuxapp

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Original compact landscape reader has no status icons/top status inset (run40003411274). */
@Composable
internal fun AgentFeedReaderStatusBars(hidden: Boolean) {
    val view = LocalView.current
    // Own only the modal window. Never fall back to the surrounding Activity.
    val window = generateSequence(view.parent) { it.parent }
        .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
    DisposableEffect(view, window, hidden) {
        val controller = if (hidden) window?.let { WindowCompat.getInsetsController(it, view) } else null
        var originalVisible: Boolean? = null
        var originalBehavior: Int? = null
        var disposed = false
        fun apply(): Boolean {
            if (disposed || controller == null) return false
            if (originalVisible == null) {
                val insets = ViewCompat.getRootWindowInsets(view) ?: return false
                originalVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars())
                originalBehavior = controller.systemBarsBehavior
            }
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
            return true
        }
        // Initial composition may precede the first inset dispatch. Capture the
        // real previous state before hiding; no guessed restore-to-visible state.
        val preDraw = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (apply()) view.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                return true
            }
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) apply() }
        if (controller != null) {
            if (!apply()) view.viewTreeObserver.addOnPreDrawListener(preDraw)
            view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        }
        onDispose {
            disposed = true
            view.viewTreeObserver.takeIf { it.isAlive }?.apply {
                removeOnPreDrawListener(preDraw)
                removeOnWindowFocusChangeListener(focus)
            }
            originalBehavior?.let { controller?.systemBarsBehavior = it }
            originalVisible?.let {
                if (it) controller?.show(WindowInsetsCompat.Type.statusBars())
                else controller?.hide(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
}
