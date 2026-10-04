package io.github.docmorphic.cmuxapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider

/** Debug-only host for real Activity recreation with an emulator-local RPC fixture. */
class NativeLifecycleTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixture = checkNotNull(connector)
        setContent { CompositionLocalProvider(LocalNativeHaptics provides haptics) { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = fixture)
        } } } }
    }
    companion object { var connector: NativeConnector? = null; internal var haptics: NativeHaptics? = null }
}
