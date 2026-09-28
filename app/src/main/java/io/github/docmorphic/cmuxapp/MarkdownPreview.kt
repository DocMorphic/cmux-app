package io.github.docmorphic.cmuxapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.ContextThemeWrapper
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI

internal object MarkdownPreviewPolicy {
    const val MAX_RENDERED_BYTES = 1_500_000L
    fun isMarkdown(path: String, mime: String?): Boolean = path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mkd", "mdx") ||
        mime?.substringBefore(';')?.trim()?.lowercase() in setOf("text/markdown", "text/x-markdown")
    fun renderedAvailable(bytes: Long) = bytes in 0..MAX_RENDERED_BYTES
    fun inPage(raw: String): Boolean = runCatching { URI(raw).let { it.rawFragment != null && it.scheme in listOf(null, "about") && it.host == null } }.getOrDefault(false)
    fun external(raw: String): Boolean = runCatching { URI(raw).scheme?.lowercase() in setOf("https", "http", "mailto", "tel") }.getOrDefault(false)
    fun renderScript(markdown: String): String = "window.__cmuxRenderMarkdown(${JSONObject.quote(markdown).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")});"
}

@Composable
internal fun ArtifactTextPreview(artifact: LocalFilePreview) {
    if (!MarkdownPreviewPolicy.isMarkdown(artifact.file.name, artifact.mime)) { ArtifactRawTextPreview(artifact.file); return }
    val available = MarkdownPreviewPolicy.renderedAvailable(artifact.size)
    var rendered by remember(artifact.file) { mutableStateOf(available) }
    var failure by remember(artifact.file) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val colors = FilterChipDefaults.filterChipColors(selectedContainerColor = androidx.compose.ui.graphics.Color(0xFF30343B),
                selectedLabelColor = androidx.compose.ui.graphics.Color.White, labelColor = filesMuted)
            FilterChip(rendered, { rendered = true; failure = null }, label = { Text("Rendered") }, enabled = available, colors = colors)
            FilterChip(!rendered, { rendered = false }, label = { Text("Raw") }, colors = colors)
        }
        if (!available) Text("Rendered Markdown is available for files up to 1.5 MB.", Modifier.padding(horizontal = 16.dp), color = filesMuted)
        failure?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        Box(Modifier.weight(1f)) {
            if (rendered) {
                val text by produceState<String?>(null, artifact.file) {
                    try { value = withContext(Dispatchers.IO) { artifact.file.readText() } }
                    catch (error: Exception) { ensureActive(); failure = error.message ?: "Could not read Markdown"; rendered = false }
                }
                if (text == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else MarkdownWebPreview(text!!, onFailure = { failure = it; rendered = false })
            } else ArtifactRawTextPreview(artifact.file)
        }
    }
}

internal object MarkdownAssets {
    val libraries = mapOf("mermaid" to listOf("mermaid.min.js"), "vega-lite" to listOf("vega.min.js", "vega-lite.min.js", "vega-embed.min.js"))
    fun read(context: Context, name: String) = context.assets.open("markdown-viewer/$name").bufferedReader().use { it.readText() }
    fun shell(context: Context): String {
        var html = read(context, "shell.html")
        mapOf("githubMarkdownCSS" to "github-markdown.css", "highlightLightCSS" to "highlight-github.css", "highlightDarkCSS" to "highlight-github-dark.css",
            "markedJS" to "marked.min.js", "highlightJS" to "highlight.min.js", "viewerNavigationJS" to "viewer-navigation.js").forEach { (key, file) ->
            html = html.replace("{{$key}}", read(context, file))
        }
        // Empty translations use the original shell's English fallback strings.
        html = html.replace("{{localizedStringsJSON}}", "{}")
        val host = """
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; style-src 'unsafe-inline'; img-src data: blob: cmux-remote-image:; connect-src 'none'; base-uri 'none'; form-action 'none'">
            <script>window.webkit={messageHandlers:{cmuxLib:{postMessage:function(value){CmuxMarkdownBridge.postMessage(JSON.stringify(value));}}}};</script>
        """.trimIndent()
        return html.replace("<head>", "<head>$host")
    }
}

@Composable
internal fun MarkdownWebPreview(markdown: String, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val zoom = LocalDensity.current.fontScale
    val latestFailure by rememberUpdatedState(onFailure)
    var recovery by remember { mutableIntStateOf(0) }
    val shell by produceState<String?>(null) {
        try { value = withContext(Dispatchers.IO) { MarkdownAssets.shell(context) } }
        catch (error: Exception) { ensureActive(); latestFailure("Markdown renderer unavailable. Showing raw source.") }
    }
    if (shell == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    key(recovery) {
        val controller = remember { MarkdownWebController(context, shell!!, markdown, zoom, {
            if (recovery < 2) recovery++ else latestFailure("Markdown renderer stopped. Showing raw source.")
        }, { latestFailure(it) }) }
        DisposableEffect(controller) { onDispose { controller.close() } }
        AndroidView(factory = { controller.create() }, modifier = Modifier.fillMaxSize().semantics { contentDescription = "Rendered Markdown" },
            update = { controller.update(markdown, zoom) }, onRelease = { controller.close() })
    }
}

/** Loads only the bundled shell; activated links leave the viewer and images use the consent route. */
internal class MarkdownWebController(private val context: Context, private val shell: String, private var markdown: String,
    private var zoom: Float, private val onCrash: () -> Unit, private val onFailure: (String) -> Unit) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val images = MarkdownRemoteImages()
    private val requested = mutableSetOf<String>()
    private var view: WebView? = null
    @Volatile private var loaded = false
    @Volatile private var closed = false
    @SuppressLint("SetJavaScriptEnabled")
    fun create(): WebView = WebView(ContextThemeWrapper(context, android.R.style.Theme_Material_NoActionBar).apply {
        // Compose's dark palette does not change the Activity's legacy light theme.
        // WebView derives prefers-color-scheme from its Android context.
        applyOverrideConfiguration(Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        })
    }).also { web ->
        view = web
        web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        web.settings.apply {
            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            domStorageEnabled = false; databaseEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
        }
        web.addJavascriptInterface(object {
            @JavascriptInterface fun postMessage(raw: String) {
                if (closed || raw.length > 65_536) return
                val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
                scope.launch { handle(message) }
            }
        }, "CmuxMarkdownBridge")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (closed || loaded) return
                loaded = true; push()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val raw = request.url.toString()
                if (!loaded && request.isForMainFrame && request.url.scheme in setOf("data", "about")) return false
                if (MarkdownPreviewPolicy.inPage(raw)) return false
                if (request.isForMainFrame && request.hasGesture() && MarkdownPreviewPolicy.external(raw)) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                }
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (!closed && request.isForMainFrame && MarkdownPreviewPolicy.inPage(uri.toString())) return null
                if (!closed && (uri.scheme == "data" && !request.isForMainFrame ||
                    !loaded && request.isForMainFrame && uri.scheme in setOf("data", "about"))) return null
                val result = if (!closed && uri.scheme == "cmux-remote-image" && uri.host == "image")
                    uri.getQueryParameter("url")?.let(images::fetch) else null
                return if (result != null) WebResourceResponse(result.mime, null, ByteArrayInputStream(result.bytes))
                    else WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !closed) onFailure("Markdown renderer failed. Showing raw source.")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!closed) { close(); onCrash() }; return true
            }
        }
        web.loadDataWithBaseURL(null, shell, "text/html", "UTF-8", null)
    }
    fun update(value: String, fontScale: Float) {
        val changed = value != markdown || zoom != fontScale
        markdown = value; zoom = fontScale
        if (changed && loaded) push()
    }
    private fun push() {
        val web = view ?: return
        web.settings.textZoom = (zoom * 100).toInt().coerceIn(50, 400)
        web.evaluateJavascript(MarkdownPreviewPolicy.renderScript(markdown) + "true;") { result ->
            if (!closed && result != "true") onFailure("Markdown renderer failed. Showing raw source.")
        }
    }
    private suspend fun handle(message: JSONObject) {
        if (closed) return
        val lib = message.optString("lib")
        val specs = MarkdownAssets.libraries[lib]
        if (specs != null && requested.add(lib)) {
            try {
                val script = withContext(Dispatchers.IO) { specs.joinToString("\n;\n") { MarkdownAssets.read(context, it) } }
                currentCoroutineContext().ensureActive()
                view?.evaluateJavascript(script + "\n;window.__cmuxLibLoaded(${JSONObject.quote(lib)});", null)
            } catch (error: Exception) { currentCoroutineContext().ensureActive(); requested.remove(lib) }
        } else if (message.optString("action") == "resolveMarkdownFile") {
            val response = JSONObject().put("requestId", message.optString("requestId")).put("exists", false).put("path", "")
            view?.evaluateJavascript("window.__cmuxMarkdownFileResolved($response);", null)
        }
    }
    override fun close() {
        if (closed) return
        closed = true; images.close(); scope.cancel()
        view?.let { it.stopLoading(); it.removeJavascriptInterface("CmuxMarkdownBridge"); it.destroy() }
        view = null
    }
}
