package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeOAuthBrowserTest {
    private fun callback(flow: String) = "stack-auth-mobile-oauth-url://success/${BuildConfig.APPLICATION_ID}?cmux_flow=$flow&state=$flow&code=fixture"
    @Test fun fallbackRedirectBeatsTheOldCustomTabCanceledResultAndOnlyCompletesOnce() = runTest {
        val browser = NativeOAuthBrowser(); val flow = "A".repeat(32)
        val result = async { browser.authorize("https://provider.example/", flow) }; runCurrent()
        val id = browser.state.value!!.id; assertTrue(browser.claim(id)); assertFalse(browser.claim(id))
        browser.hostResumed(false); browser.closed(id)
        assertTrue(NativeOAuthRedirectCallbacks.deliver(callback(flow))); assertFalse(NativeOAuthRedirectCallbacks.deliver(callback(flow)))
        browser.hostResumed(true); assertEquals(callback(flow), result.await()); assertNull(browser.state.value)
        browser.finishPresentation(id); assertTrue(browser.presentations.value.isEmpty())
    }
    @Test fun closingTheBrowserWithoutRedirectEndsSilentlyWhenTheHostResumes() = runTest {
        val browser = NativeOAuthBrowser(); val result = async { runCatching { browser.authorize("https://provider.example/", "A".repeat(32)) } }; runCurrent()
        val id = browser.state.value!!.id; browser.claim(id); browser.hostResumed(false); browser.closed(id); assertFalse(result.isCompleted)
        browser.hostResumed(true); val failure = result.await().exceptionOrNull() as NativeAccountRequestFailure
        assertNull(nativeSignInError(failure)); browser.finishPresentation(id); assertNull(browser.state.value)
    }
    @Test fun lateOldNativeResultsAndFallbackLinksCannotCancelOrCompleteANewerFlow() = runTest {
        val browser = NativeOAuthBrowser(); val oldFlow = "A".repeat(32); val newFlow = "B".repeat(32)
        val old = async { browser.authorize("https://provider.example/", oldFlow) }; runCurrent(); val oldId = browser.state.value!!.id; browser.claim(oldId)
        old.cancel(); runCurrent()
        val next = async { browser.authorize("https://provider.example/", newFlow) }; runCurrent(); val newId = browser.state.value!!.id; browser.claim(newId)
        browser.hostResumed(true); browser.closed(oldId); browser.failed(oldId); browser.finishPresentation(oldId)
        assertFalse(browser.receive(oldId, callback(oldFlow))); assertFalse(NativeOAuthRedirectCallbacks.deliver(callback(oldFlow)))
        assertFalse(next.isCompleted); assertEquals(listOf(newId), browser.presentations.value.map { it.id })
        assertTrue(browser.receive(newId, callback(newFlow))); assertEquals(callback(newFlow), next.await()); browser.finishPresentation(newId)
    }
    @Test fun recreationCanReattachWithoutLaunchingAgainAndUnknownProcessLinksDoNothing() = runTest {
        val browser = NativeOAuthBrowser(); val flow = "C".repeat(32)
        assertFalse(NativeOAuthRedirectCallbacks.deliver(callback(flow)))
        val result = async { browser.authorize("https://provider.example/", flow) }; runCurrent(); val id = browser.state.value!!.id
        assertTrue(browser.claim(id)); browser.hostResumed(false); browser.hostResumed(true); assertFalse(browser.claim(id))
        assertFalse(browser.receive(id, callback("D".repeat(32)))); assertTrue(browser.receive(id, callback(flow)))
        assertEquals(callback(flow), result.await()); browser.finishPresentation(id)
        assertFalse(NativeOAuthRedirectCallbacks.deliver(callback(flow))); assertTrue(browser.presentations.value.isEmpty())
    }
}
