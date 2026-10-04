package io.github.docmorphic.cmuxapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider

/** Debug-only generated feedback fixture. Never sends through the production client. */
class NativeFeedbackLifecycleTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val send = checkNotNull(submit) { "Feedback test sender was not installed" }
        setContent { CompositionLocalProvider(LocalNativeHaptics provides haptics) { CmuxTheme { NativeFeedbackHost("fixture-account", "reply@example.test", send) {
            val open = checkNotNull(LocalNativeFeedback.current)
            TextButton(onClick = open) { Text("Open feedback") }
        } } } }
    }
    companion object {
        internal var submit: (suspend (String, String, NativeFeedbackStamp) -> Unit)? = null
        internal var haptics: NativeHaptics? = null
    }
}
