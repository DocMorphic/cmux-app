package io.github.docmorphic.cmuxapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/** Actual Activity recreation with an injected retained production owner; no account or network. */
class NativeChiefLifecycleTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val retained = checkNotNull(computer) { "Chief lifecycle owner was not installed" }
        setContent { CmuxTheme(darkTheme = darkThemeOverride ?: isSystemInDarkTheme()) { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            NativeChiefView(retained, true, Modifier.fillMaxSize())
        } } }
    }
    companion object {
        internal var computer: NativeChiefComputerSession? = null
        internal var darkThemeOverride: Boolean? = null
    }
}
