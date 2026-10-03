package io.github.docmorphic.cmuxapp

import android.annotation.SuppressLint
import android.app.Activity
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.*
import android.webkit.*
import kotlinx.coroutines.*
import java.security.MessageDigest

/** Disposable emulator-only IPC fixture. Never present in the signed release. */
class RoutedBrowserTestActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var web: WebView? = null
    private var pinnedCertificate: String? = null
    private var lastError: String? = null
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { message ->
        val reply = message.replyTo
        // Android recycles Message immediately after this handler returns.
        val kind = message.what; val ticket = message.arg1; val args = Bundle(message.data)
        fun answer(value: String) { reply?.send(Message.obtain(null, kind).apply {
            arg1 = ticket; data = Bundle().apply { putString("value", value) }
        }) }
        when (kind) {
            1 -> scope.launch {
                try {
                    val binding = RoutedBrowserBinding(args.getString("storage")!!, args.getInt("port"))
                    RoutedBrowserEnvironment.prepare(this@RoutedBrowserTestActivity, binding)
                    RoutedBrowserEnvironment.requireReady(binding)
                    if (web == null) createWeb()
                    answer("ready")
                } catch (failure: Exception) { answer("rejected:" + failure.message) }
            }
            2 -> {
                lastError = null
                pinnedCertificate = args.getString("certificate")
                web!!.loadUrl(args.getString("url")!!); answer("loading")
            }
            3 -> web!!.evaluateJavascript(args.getString("script")!!) { answer(it) }
            4 -> { CookieManager.getInstance().flush(); answer("flushed") }
            5 -> { answer("closing"); finishAndRemoveTask() }
            6 -> answer(lastError ?: "none")
            7 -> { answer("crashing"); Handler(Looper.getMainLooper()).post { crashForDiagnostics() } }
            8 -> {
                setContentView(android.widget.TextView(this).apply { text = "Disposable ANR fixture" })
                answer("blocking")
                Handler(Looper.getMainLooper()).postDelayed({ blockForDiagnostics() }, 500)
            }
        }
        true
    })
    private fun crashForDiagnostics(): Nothing {
        Thread.currentThread().name = "PRIVATE_DIAGNOSTIC_THREAD"
        throw IllegalStateException("PRIVATE_DIAGNOSTIC_MESSAGE", java.io.IOException("PRIVATE_DIAGNOSTIC_CAUSE"))
    }
    private fun blockForDiagnostics() {
        val monitor = Any()
        val held = java.util.concurrent.CountDownLatch(1)
        Thread({ holdMonitorForDiagnostics(monitor, held) }, "PRIVATE_ANR_OWNER").start()
        check(held.await(5, java.util.concurrent.TimeUnit.SECONDS))
        synchronized(monitor) { /* Deliberately block only this disposable emulator process. */ }
    }
    private fun holdMonitorForDiagnostics(monitor: Any, held: java.util.concurrent.CountDownLatch) {
        synchronized(monitor) {
            held.countDown()
            Thread.sleep(90_000)
        }
    }
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Disposable emulator required" }
        val reply = intent.getParcelableExtra<Messenger>("reply")!!
        reply.send(Message.obtain(null, 0).apply { replyTo = endpoint; arg1 = Process.myPid() })
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWeb() {
        web = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) lastError = "${error.errorCode}:${request.url}"
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    // Only the generated test server's exact certificate is accepted in this debug fixture.
                    // Production LocalBrowserWebHost cancels every TLS verification error.
                    val der = SslCertificate.saveState(error.certificate).getByteArray("x509-certificate")
                    val digest = der?.let { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) } }
                    if (digest != null && digest == pinnedCertificate && error.primaryError == SslError.SSL_UNTRUSTED) handler.proceed()
                    else { lastError = "ssl:${error.primaryError}:${error.url}"; handler.cancel() }
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    view.destroy(); web = null; finishAndRemoveTask(); return true
                }
            }
            webChromeClient = WebChromeClient()
        }
        setContentView(web)
    }
    override fun onDestroy() {
        web?.stopLoading(); web?.destroy(); web = null; scope.cancel()
        super.onDestroy()
        // Process-global proxy and service workers may never survive into a different owner.
        Handler(Looper.getMainLooper()).post { Process.killProcess(Process.myPid()) }
    }
}
