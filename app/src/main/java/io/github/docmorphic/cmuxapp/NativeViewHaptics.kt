package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.SharedPreferences
import android.view.View

/** Connect framework selection/long-press feedback to the same policy as Compose.
 * Attach once when creating a view, after its own default feedback flag is configured.
 * Detached views retain no SharedPreferences subscription; reattachment reads fresh state.
 */
internal class NativeViewHaptics(private val view: View,
    private val preferences: SharedPreferences = view.context.getSharedPreferences("cmux-display", Context.MODE_PRIVATE)
) : View.OnAttachStateChangeListener, SharedPreferences.OnSharedPreferenceChangeListener {
    private val defaultEnabled = view.isHapticFeedbackEnabled
    private var listening = false

    init {
        view.addOnAttachStateChangeListener(this)
        refresh()
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    private fun refresh() {
        view.isHapticFeedbackEnabled = defaultEnabled &&
            (preferences.all[NativeDisplayPreferences.hapticsKey] as? Boolean ?: true)
    }

    override fun onViewAttachedToWindow(view: View) {
        if (!listening) { preferences.registerOnSharedPreferenceChangeListener(this); listening = true }
        refresh()
    }

    override fun onViewDetachedFromWindow(view: View) {
        if (listening) { preferences.unregisterOnSharedPreferenceChangeListener(this); listening = false }
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) {
        if (key == null || key == NativeDisplayPreferences.hapticsKey) refresh()
    }
}
