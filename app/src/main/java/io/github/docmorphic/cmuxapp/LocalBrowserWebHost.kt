package io.github.docmorphic.cmuxapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.*
import android.widget.FrameLayout
import kotlinx.coroutines.*

/** Owns only this pane's WebView. It has no JavaScript bridge, file access or Mac credentials. */
@SuppressLint("SetJavaScriptEnabled")
internal class LocalBrowserWebHost(context: Context, private val surface: LocalBrowserSurface,
    private val chooseFiles: (Any, WebChromeClient.FileChooserParams, ValueCallback<Array<Uri>>) -> Boolean,
    private val cancelFiles: (Any) -> Unit,
    private val beforeNavigation: (suspend (String?) -> Unit)? = null) : FrameLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var navigation: Job? = null
    private var policyRefresh: Job? = null
    private var preparedUrl: String? = null
    private var browser: WebView? = null
    private var token = 0L
    private var released = false
    private var rendererGone = false
    private var stopped = false
    private var failed = false
    private var navigatingUrl: String? = null

    private fun current(view: WebView, ticket: Long) = !released && browser === view && token == ticket && !surface.state.value.closed
    private fun isWeb(url: String?) = url?.let { Uri.parse(it).scheme?.lowercase() in setOf("http", "https") } == true
    private fun location(view: WebView, ticket: Long) {
        val url = view.url?.takeIf(::isWeb) ?: surface.state.value.url
        surface.location(ticket, url, view.title, view.canGoBack(), view.canGoForward())
    }
    @Suppress("DEPRECATION")
    private fun createBrowser() {
        val ticket = surface.attach(); token = ticket
        val view = WebView(context)
        browser = view; rendererGone = false; stopped = false; failed = false
        view.tag = "LocalBrowserWebView"; view.contentDescription = "Browser page"
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false
            safeBrowsingEnabled = true; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true; loadWithOverviewMode = true
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            // Android treats target=_blank/window.open as navigation in this view.
            setSupportMultipleWindows(false)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(web: WebView, request: WebResourceRequest): Boolean {
                if (!current(web, ticket)) return true
                if (!isWeb(request.url.toString())) return true
                if (request.isForMainFrame) { stopped = false; failed = false }
                return false
            }
            override fun onPageStarted(web: WebView, url: String?, favicon: Bitmap?) {
                if (!current(web, ticket) || stopped || !isWeb(url)) return
                navigatingUrl = url; failed = false
                surface.started(ticket); location(web, ticket)
                if (preparedUrl == url) preparedUrl = null
                else if (beforeNavigation != null) {
                    // Browser-owned redirects/forms retain their original navigation and request body.
                    // All destinations already use the immutable proxy; refresh policy without replaying them.
                    policyRefresh?.cancel()
                    policyRefresh = scope.launch { runCatching { beforeNavigation.invoke(url) } }
                }
            }
            override fun doUpdateVisitedHistory(web: WebView, url: String?, isReload: Boolean) {
                if (current(web, ticket) && isWeb(url)) location(web, ticket)
            }
            override fun onPageFinished(web: WebView, url: String?) {
                if (!current(web, ticket) || !isWeb(url) || url != web.url) return
                location(web, ticket)
                if (!stopped && !failed) surface.finished(ticket)
                CookieManager.getInstance().flush()
            }
            override fun onReceivedError(web: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!current(web, ticket) || !request.isForMainFrame || stopped) return
                val url = request.url.toString()
                if (url != navigatingUrl && url != web.url) return
                failed = true; surface.failed(ticket, "Couldn’t load this page. Check the address or your connection.")
            }
            override fun onReceivedSslError(web: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (current(web, ticket) && (error.url == navigatingUrl || error.url == web.url)) {
                    failed = true; surface.failed(ticket, "This site’s secure connection couldn’t be verified.")
                }
            }
            override fun onRenderProcessGone(web: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (!current(web, ticket)) return true
                surface.failed(ticket, "The browser page stopped. Reload to continue.")
                surface.detach(ticket); cancelFiles(web)
                browser = null; rendererGone = true; removeView(web); web.destroy()
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(web: WebView, value: Int) {
                if (current(web, ticket) && !stopped && !failed) surface.progress(ticket, value / 100f)
            }
            override fun onReceivedTitle(web: WebView, title: String?) { if (current(web, ticket)) location(web, ticket) }
            override fun onShowFileChooser(web: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                current(web, ticket) && chooseFiles(web, params, callback)
            override fun onConsoleMessage(message: ConsoleMessage?) = true
        }
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun navigate(view: WebView, url: String?, action: () -> Unit) {
        navigation?.cancel(); policyRefresh?.cancel()
        if (beforeNavigation == null) { action(); return }
        val ticket = token
        stopped = false; failed = false; surface.started(ticket)
        navigation = scope.launch {
            try {
                beforeNavigation.invoke(url)
                ensureActive()
                if (current(view, ticket) && !stopped) { preparedUrl = url; action() }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (current(view, ticket)) surface.failed(ticket, failure.message ?: "Could not connect this browser to its computer.")
            }
        }
    }

    fun applyPendingWork() {
        if (released) return
        if (surface.state.value.closed) { release(); return }
        var work = surface.takeWork()
        if (browser == null) {
            if (rendererGone && work.url == null && work.command != LocalBrowserCommand.RELOAD) return
            createBrowser()
            val restored = surface.takeWork()
            work = LocalBrowserWork(work.url ?: restored.url, work.command ?: restored.command)
        }
        val view = checkNotNull(browser)
        work.url?.takeIf(::isWeb)?.let {
            stopped = false; failed = false; navigatingUrl = it
            navigate(view, it) { view.loadUrl(it) }
        }
        when (work.command) {
            LocalBrowserCommand.BACK -> if (view.canGoBack()) {
                val history = view.copyBackForwardList()
                navigate(view, history.getItemAtIndex(history.currentIndex - 1)?.url) { stopped = false; failed = false; view.goBack() }
            }
            LocalBrowserCommand.FORWARD -> if (view.canGoForward()) {
                val history = view.copyBackForwardList()
                navigate(view, history.getItemAtIndex(history.currentIndex + 1)?.url) { stopped = false; failed = false; view.goForward() }
            }
            LocalBrowserCommand.RELOAD -> if (work.url == null) navigate(view, view.url) { stopped = false; failed = false; view.reload() }
            LocalBrowserCommand.STOP -> { navigation?.cancel(); policyRefresh?.cancel(); stopped = true; view.stopLoading(); surface.stopped(token, false) }
            null -> Unit
        }
    }
    fun foreground(active: Boolean) { browser?.let { if (active) it.onResume() else it.onPause() } }
    fun release() {
        if (released) return
        released = true; surface.detach(token)
        scope.cancel()
        browser?.let { view ->
            cancelFiles(view); view.stopLoading(); view.webChromeClient = null; view.webViewClient = WebViewClient()
            removeView(view); view.destroy()
        }
        browser = null
    }
}
