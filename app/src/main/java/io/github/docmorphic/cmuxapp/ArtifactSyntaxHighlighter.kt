package io.github.docmorphic.cmuxapp

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/** Local, off-screen JS computation; native TextView remains the only displayed code view. */
internal object ArtifactSyntaxHighlighter {
    private val lock = Mutex()
    suspend fun highlight(context: Context, text: String, language: String?, dark: Boolean = true): ArtifactSyntaxResult? = lock.withLock {
        if (text.isEmpty()) return@withLock null
        withContext(Dispatchers.Main.immediate) {
            val shell = withContext(Dispatchers.IO) {
                fun read(name: String) = context.assets.open("raw-code/$name").bufferedReader().use { it.readText() }
                """<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
                    <style>${read(if (dark) "xcode-dark.min.css" else "xcode.min.css")}</style></head><body><pre id="code" class="hljs"></pre>
                    <script>${read("highlight.min.js")}</script><script>${read("native-runs.js")}</script></body></html>"""
            }
            val worker = SyntaxWorker(context.applicationContext)
            try {
                withTimeoutOrNull(15_000) {
                    val script = "window.cmuxNativeHighlight(${quote(text)},${language?.let(::quote) ?: "null"});"
                    val raw = worker.run(shell, script) ?: return@withTimeoutOrNull null
                    withContext(Dispatchers.Default) { ArtifactSyntaxResult.decode(raw, text) }
                }
            } finally { worker.close() }
        }
    }
    private fun quote(text: String) = JSONObject.quote(text).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
}

private class SyntaxWorker(private val context: Context) : AutoCloseable {
    private var view: WebView? = null
    private var continuation: CancellableContinuation<String?>? = null
    @Volatile private var started = false
    @Volatile private var closed = false
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(shell: String, script: String): String? = suspendCancellableCoroutine { result ->
        continuation = result
        val web = WebView(context)
        view = web
        web.settings.apply {
            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            blockNetworkLoads = true; domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (started || closed) return
                started = true
                view.evaluateJavascript(script) { complete(it) }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                closed || started || !request.isForMainFrame || request.url.scheme !in setOf("data", "about")
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!started && request.isForMainFrame && request.url.scheme in setOf("data", "about")) return null
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) complete(null) }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { complete(null); close(); return true }
        }
        web.loadDataWithBaseURL(null, shell, "text/html", "UTF-8", null)
    }
    private fun complete(value: String?) {
        val waiting = continuation
        continuation = null
        if (waiting?.isActive == true) waiting.resume(value)
    }
    override fun close() {
        if (closed) return
        closed = true; complete(null)
        view?.let { it.stopLoading(); it.webViewClient = WebViewClient(); it.destroy() }
        view = null
    }
}
