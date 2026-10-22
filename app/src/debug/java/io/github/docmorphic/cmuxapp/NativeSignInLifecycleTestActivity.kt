package io.github.docmorphic.cmuxapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/** Controlled requests with the production retained sign-in owner; no real email or credentials. */
class NativeSignInLifecycleTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = checkNotNull(session)
        setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            NativeSignIn({ error("Use retained sender") }, { error("Use retained authentication") }, {}, {},
                onSignedIn = { completed?.invoke() }, retained = model)
        } } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saved = Bundle(outState)
    }
    companion object {
        internal var session: NativeSignInSession? = null
        internal var completed: (() -> Unit)? = null
        internal var saved: Bundle? = null
    }
}
