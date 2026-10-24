package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/** Compose idleness does not cover the native IME/adjustResize animation before a pointer tap. */
internal fun awaitNativeSignInIme(activity: Activity) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    UiDevice.getInstance(instrumentation).waitForIdle(2_000)
    val deadline = SystemClock.elapsedRealtime() + 5_000
    var previous: Pair<Int, Int>? = null; var stable = 0
    while (SystemClock.elapsedRealtime() < deadline) {
        var sample: Pair<Int, Int>? = null
        instrumentation.runOnMainSync {
            val view = activity.window.decorView
            val insets = ViewCompat.getRootWindowInsets(view)
            if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                val visible = Rect(); view.getWindowVisibleDisplayFrame(visible)
                sample = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom to visible.bottom
            }
        }
        stable = if (sample != null && sample == previous) stable + 1 else 0
        if (stable >= 3) return
        previous = sample; SystemClock.sleep(50)
    }
    error("Native sign-in IME did not settle; do not tap a moving target")
}
