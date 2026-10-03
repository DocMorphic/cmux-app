package io.github.docmorphic.cmuxapp

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.webkit.ProcessGlobalConfig
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** An opaque per-Mac, per-app-session ID. Never put login credentials in this storage key. */
internal data class RoutedBrowserBinding(val storageId: String, val proxyPort: Int) {
    init {
        require(storageId.matches(Regex("[a-f0-9]{32}"))) { "Invalid browser storage identity" }
        require(proxyPort in 1..65535) { "Invalid browser proxy port" }
    }
    val suffix get() = "cmux_browser_$storageId"
}

/** Immutable process-wide network and storage. Switching owners requires a new browser process. */
internal object RoutedBrowserEnvironment {
    const val PROCESS = ":browser"
    private val preparing = Mutex()
    private var binding: RoutedBrowserBinding? = null
    private var ready = false
    private var failed = false

    suspend fun prepare(context: Context, requested: RoutedBrowserBinding) = withContext(Dispatchers.Main.immediate) {
        preparing.withLock {
            val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
                    ?.singleOrNull { it.pid == Process.myPid() }?.processName
            check(processName == context.packageName + PROCESS) { "Routed browsers require their dedicated process" }
            check(binding == null || binding == requested) { "Restart the browser process to change its computer or proxy" }
            check(!failed) { "Browser environment initialization failed; restart its process" }
            if (ready) return@withLock
            binding = requested
            try {
                // This startup feature check does not load WebView. It must precede all runtime WebKit calls.
                check(WebViewFeature.isStartupFeatureSupported(context, WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX)) {
                    "Update Android System WebView to isolate computer browsing"
                }
                ProcessGlobalConfig.apply(ProcessGlobalConfig().setDataDirectorySuffix(context, requested.suffix))
                check(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    "Update Android System WebView to use the computer's network"
                }
                val proxy = ProxyConfig.Builder().addProxyRule("socks://127.0.0.1:${requested.proxyPort}")
                    .removeImplicitRules().build()
                // No DIRECT fallback. Mac/phone routing is decided only by the owner-bound SOCKS backend.
                withTimeout(10_000) {
                    suspendCancellableCoroutine { continuation ->
                        val main = Handler(Looper.getMainLooper())
                        ProxyController.getInstance().setProxyOverride(proxy, { main.post(it) }) {
                            continuation.resume(Unit)
                        }
                    }
                }
                ready = true
            } catch (failure: Throwable) { failed = true; throw failure }
        }
    }

    fun requireReady(expected: RoutedBrowserBinding) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(ready && !failed && binding == expected) { "Browser network is not ready" }
    }
}
