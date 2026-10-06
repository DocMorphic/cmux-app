package io.github.docmorphic.cmuxapp

import android.webkit.WebViewClient
import org.junit.Assert.*
import org.junit.Test

class LocalBrowserConnectionErrorTest {
    @Test fun connectionFailuresUsePublicCodesWithoutDependingOnLocalizedCopy() {
        for (code in listOf(WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_IO, WebViewClient.ERROR_UNKNOWN)) {
            assertTrue(localBrowserConnectionError(code))
        }
    }
    @Test fun certificateAuthAndSafeBrowsingErrorsAreNotConnectionRetries() {
        for (code in listOf(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, WebViewClient.ERROR_AUTHENTICATION,
            WebViewClient.ERROR_PROXY_AUTHENTICATION, WebViewClient.ERROR_UNSAFE_RESOURCE)) {
            assertFalse(localBrowserConnectionError(code))
        }
    }
    @Test fun invalidDestinationsRedirectLoopsAndResourceLimitsAreNotConnectionRetries() {
        for (code in listOf(WebViewClient.ERROR_BAD_URL, WebViewClient.ERROR_UNSUPPORTED_SCHEME,
            WebViewClient.ERROR_REDIRECT_LOOP, WebViewClient.ERROR_TOO_MANY_REQUESTS,
            WebViewClient.ERROR_FILE, WebViewClient.ERROR_FILE_NOT_FOUND, 42)) {
            assertFalse(localBrowserConnectionError(code))
        }
    }
}
