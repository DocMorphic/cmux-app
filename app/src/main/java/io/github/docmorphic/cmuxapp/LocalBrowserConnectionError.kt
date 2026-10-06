package io.github.docmorphic.cmuxapp

import android.webkit.WebViewClient

/** Unknown includes SOCKS failures; WebView does not expose their typed net error. */
internal fun localBrowserConnectionError(code: Int): Boolean = when (code) {
    WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT,
    WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_IO,
    WebViewClient.ERROR_UNKNOWN -> true
    else -> false
}
