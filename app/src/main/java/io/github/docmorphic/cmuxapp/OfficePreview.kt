package io.github.docmorphic.cmuxapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

@Composable
internal fun OfficeFilePreview(artifact: LocalFilePreview, kind: OfficePreviewKind) {
    val context = LocalContext.current
    val state = rememberSaveable(artifact.file.absolutePath, kind, saver = listSaver<OfficeReaderState, Any>(
        save = { it.viewport.capture?.invoke(); it.save() }, restore = { OfficeReaderState().apply { restore(it) } }
    )) { OfficeReaderState() }
    var prepared by remember(artifact.file, kind) { mutableStateOf<OfficePreviewPackage?>(null) }
    var failure by remember(artifact.file, kind) { mutableStateOf<String?>(null) }
    var ready by remember(artifact.file, kind) { mutableStateOf(false) }
    var recovery by remember(artifact.file, kind) { mutableIntStateOf(0) }
    LaunchedEffect(artifact.file, kind) {
        var owned: OfficePreviewPackage? = null
        try {
            // Assign ownership inside IO so cancellation at the dispatch boundary cannot leak the lease.
            withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                val root = File(context.cacheDir, "office-previews")
                owned = if (kind == OfficePreviewKind.RICH_TEXT) OfficePreviewPackage.prepareRichText(artifact.file, root) { job.ensureActive() }
                    else OfficePreviewPackage.prepare(artifact.file, root) { job.ensureActive() }
            }
            prepared = owned
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = "This document can’t be previewed. Use Viewer actions to open, share or save it." }
        finally { withContext(NonCancellable + Dispatchers.IO) { owned?.close() } }
    }
    Box(Modifier.fillMaxSize()) {
        if (failure != null) ChangesNotice("Preview unavailable", failure!!)
        else prepared?.let { document -> key(document, recovery, kind) {
            val controller = remember { OfficeWebController(context, document, kind, state,
                onReady = { ready = true }, onFailure = {
                    failure = "This document can’t be previewed. Use Viewer actions to open, share or save it."
                }, onCrash = {
                    ready = false
                    if (recovery < 1) recovery++ else failure = "The document viewer stopped. Reopen the preview to try again."
                }) }
            DisposableEffect(controller) { onDispose { controller.close() } }
            AndroidView(factory = { controller.create() }, modifier = Modifier.fillMaxSize().semantics {
                contentDescription = kind.description
            }, onRelease = { controller.close() })
        } }
        if (failure == null && !ready) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Every request terminates here; only the bundled shell and one owned snapshot are served. */
internal class OfficeWebController(
    private val context: Context, private val document: OfficePreviewPackage, private val kind: OfficePreviewKind, private val state: OfficeReaderState,
    private val onReady: () -> Unit, private val onFailure: () -> Unit, private val onCrash: () -> Unit,
) : AutoCloseable {
    private val origin = "https://office-${UUID.randomUUID()}.invalid"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: MarkdownViewportWebView? = null
    private var binding: MarkdownViewportBinding? = null
    @Volatile private var closed = false
    private var ready = false
    private val assets = kind.assets
    @SuppressLint("SetJavaScriptEnabled")
    fun create(): WebView = MarkdownViewportWebView(context).also { web ->
        view = web; binding = MarkdownViewportBinding(web, state.viewport); NativeViewHaptics(web)
        web.setBackgroundColor(android.graphics.Color.rgb(32, 33, 36))
        web.settings.apply {
            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            domStorageEnabled = false; databaseEnabled = false
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true; loadWithOverviewMode = kind == OfficePreviewKind.WORD || kind == OfficePreviewKind.RICH_TEXT
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface fun postMessage(raw: String) {
                if (closed || raw.length > 4096) return
                val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
                scope.launch {
                    if (closed) return@launch
                    when (message.optString("action")) {
                        "officeReady" -> if (!ready) { ready = true; binding?.rendered(); onReady() }
                        "officeFailed" -> onFailure()
                        "markdownViewport" -> if (ready) binding?.geometry(message)
                        "workbookState" -> if (kind == OfficePreviewKind.WORKBOOK) state.readWorkbookState(message)
                        "presentationState" -> if (kind == OfficePreviewKind.PRESENTATION) state.readPresentationState(message)
                    }
                }
            }
        }, "CmuxMarkdownBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                val name = uri.path?.removePrefix("/")
                if (!closed && request.method == "GET" && uri.scheme == "https" &&
                    uri.authority == Uri.parse(origin).authority && uri.query == null) {
                    try {
                        val mime = assets[name]
                        val input = when {
                            name == kind.documentName && !request.isForMainFrame -> document.file.inputStream()
                            mime != null && (name == "shell.html") == request.isForMainFrame -> context.assets.open("${kind.assetDirectory}/$name")
                            else -> null
                        }
                        if (input != null) return WebResourceResponse(mime ?: kind.documentMime, if (mime != null) "UTF-8" else null,
                            200, "OK", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), input)
                    } catch (_: Exception) { scope.launch { if (!closed) onFailure() } }
                }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (request.isForMainFrame && uri.fragment != null && uri.toString().substringBefore('#') == "$origin/shell.html") return false
                if (!closed && request.isForMainFrame && request.hasGesture() && MarkdownPreviewPolicy.external(uri.toString())) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }
                return true
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !closed) onFailure()
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!closed) { close(); onCrash() }; return true
            }
        }
        web.loadUrl("$origin/shell.html#sheet=${state.sheet}&row=${state.row}&col=${state.column}&slide=${state.slide}")
        scope.launch { delay(30_000); if (!closed && !ready) onFailure() }
    }
    override fun close() {
        if (closed) return
        binding?.close(); binding = null; closed = true; scope.cancel()
        view?.let { it.stopLoading(); it.removeJavascriptInterface("CmuxMarkdownBridge"); it.destroy() }; view = null
    }
}
