package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Browser callbacks carry a code only; PKCE material stays in the retained owner. */
@Composable internal fun NativeOAuthBrowserHost(browser: NativeOAuthBrowser) {
    val presentations by browser.presentations.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val registry = checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    DisposableEffect(browser, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) browser.hostResumed(true)
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) browser.hostResumed(false)
        }
        lifecycle.addObserver(observer)
        browser.hostResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Keep an old launch's registration until its result is consumed. Each
    // callback captures its admitted id, so a late old close cannot cancel a retry.
    for (admitted in presentations) key(admitted.id) {
        var launcher by remember(registry, admitted.id) { mutableStateOf<ActivityResultLauncher<Intent>?>(null) }
        DisposableEffect(registry, admitted.id, browser) {
            val registration = registry.register("cmux.oauth." + admitted.id, AuthTabIntent.AuthenticateUserResultContract()) { result ->
                try { when (result.resultCode) {
                    AuthTabIntent.RESULT_OK -> if (result.resultUri?.toString()?.let { browser.receive(admitted.id, it) } != true) browser.failed(admitted.id)
                    AuthTabIntent.RESULT_CANCELED -> browser.closed(admitted.id)
                    else -> browser.failed(admitted.id)
                } } finally { browser.finishPresentation(admitted.id) }
            }
            launcher = registration
            onDispose { registration.unregister() }
        }
        LaunchedEffect(browser, admitted.id, launcher) {
            val registered = launcher ?: return@LaunchedEffect
            if (browser.claim(admitted.id)) try {
                AuthTabIntent.Builder().setEphemeralBrowsingEnabled(true).build()
                    .launch(registered, Uri.parse(admitted.location), NativeOAuthChallenge.SCHEME)
            } catch (_: Exception) { browser.failed(admitted.id); browser.finishPresentation(admitted.id) }
        }
    }
}

/** Unknown/stale links cannot start sign-in, create an account owner or exchange tokens. */
class NativeOAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receive(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); receive(intent) }
    private fun receive(value: Intent?) {
        val callback = value?.dataString
        value?.data = null
        intent = Intent()
        callback?.let(NativeOAuthRedirectCallbacks::deliver)
        finish()
    }
}
