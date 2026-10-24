package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Actual auth owner/account store/HTTP/UI/Activity lifecycle; browser results are controlled. */
class NativeOAuthRuntimeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val folder get() = File(context.getExternalFilesDir(null), "signin-oauth").apply { mkdirs() }
    private var scenario: ActivityScenario<NativeSignInLifecycleTestActivity>? = null
    private var scope: CoroutineScope? = null
    private var session: NativeSignInSession? = null
    private var store: NativeCredentialStore? = null
    private var account: NativeAccount? = null
    private var browser: NativeOAuthBrowser? = null
    private var server: MockWebServer? = null
    private val completions = AtomicInteger()
    private val pairing = NativeCredentialStore.PairedMac("fixture-pairing-code", "fixture-mac", "Fixture Mac")
    private class Registry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        data class Launch(val code: Int, val intent: Intent)
        val launches = CopyOnWriteArrayList<Launch>()
        override val activityResultRegistry: ActivityResultRegistry get() = this
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches += Launch(requestCode, contract.createIntent(InstrumentationRegistry.getInstrumentation().targetContext, input))
        }
    }
    private val registry = Registry()
    @Before fun emulatorOnly() { check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic")) }
    @After fun cleanup() {
        instrumentation.runOnMainSync {
            session?.close(); scope?.cancel()
            browser?.presentations?.value?.forEach { browser!!.finishPresentation(it.id) }
        }
        scenario?.close(); server?.close(); store?.clear()
        NativeSignInLifecycleTestActivity.session = null; NativeSignInLifecycleTestActivity.completed = null
        NativeSignInLifecycleTestActivity.oauthBrowser = null; NativeSignInLifecycleTestActivity.oauthRegistryOwner = null
        NativeSignInLifecycleTestActivity.oauthHostVisible.value = true
    }
    private fun start(deadline: Long = 300_000) {
        server = MockWebServer().apply { start() }
        store = NativeCredentialStore(context, "oauth-runtime-"+UUID.randomUUID()).apply { update {
            it.put("pairings", JSONArray().put(NativePairingRecords.encode(pairing))).put("fixture_sentinel", "preserve")
        } }
        browser = NativeOAuthBrowser()
        account = NativeAccount(store!!, oauthClientOverride = NativeOAuthClient("fixture-project", "fixture-public-key", server!!.url("/")), oauthBrowser = browser!!)
        instrumentation.runOnMainSync {
            scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            session = NativeSignInSession(scope!!, account!!::sendCode, account!!::signIn, account!!::cancelPendingSignIn,
                authenticateProvider = account!!::signInWithProvider, interactiveTimeoutMillis = deadline)
            NativeSignInLifecycleTestActivity.session = session
            NativeSignInLifecycleTestActivity.completed = { completions.incrementAndGet() }
            NativeSignInLifecycleTestActivity.oauthBrowser = browser
            NativeSignInLifecycleTestActivity.oauthRegistryOwner = registry
        }
        scenario = ActivityScenario.launch(NativeSignInLifecycleTestActivity::class.java)
    }
    private fun enqueueAuthorization() = server!!.enqueue(MockResponse().setBody("{\"location\":\"https://provider.example/authorize\"}"))
    private fun enqueueTokens() = server!!.enqueue(MockResponse().setBody("{\"access_token\":\"fixture-access\",\"refresh_token\":\"fixture-refresh\"}"))
    private fun tap(provider: String, count: Int) {
        compose.onNodeWithTag("signin.$provider").performScrollTo().performClick()
        compose.waitUntil(10_000) { registry.launches.size == count }
        val intent = registry.launches.last().intent
        assertTrue(intent.getBooleanExtra(AuthTabIntent.EXTRA_LAUNCH_AUTH_TAB, false))
        assertTrue(intent.getBooleanExtra(CustomTabsIntent.EXTRA_ENABLE_EPHEMERAL_BROWSING, false))
        assertEquals(NativeOAuthChallenge.SCHEME, intent.getStringExtra(AuthTabIntent.EXTRA_REDIRECT_SCHEME))
        assertEquals("https://provider.example/authorize", intent.dataString)
        compose.onNodeWithTag("signin.apple").assertIsNotEnabled()
        compose.onNodeWithTag("signin.google").assertIsNotEnabled()
        compose.onNodeWithTag("signin.github").assertIsNotEnabled()
    }
    private fun callback(request: okhttp3.mockwebserver.RecordedRequest): String {
        val url = request.requestUrl!!
        return url.queryParameter("redirect_uri")+"&state="+url.queryParameter("state")+"&code=fixture-code"
    }
    private fun dispatch(index: Int, result: Int, uri: String? = null) = instrumentation.runOnMainSync {
        assertTrue(registry.dispatchResult(registry.launches[index].code, result, uri?.let { Intent().setData(Uri.parse(it)) }))
    }
    private fun preservedPairing() {
        assertEquals(listOf(pairing), store!!.pairedMacs()); assertEquals("preserve", store!!.load()!!.getString("fixture_sentinel"))
    }
    private fun capture(name: String) {
        compose.waitForIdle(); assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(folder, "$name.png")))
    }

    @Test fun googleFallbackRedirectSurvivesTwoRecreationsAndPublishesIntoTheRealFixtureStoreOnce() {
        start(); enqueueAuthorization(); enqueueTokens(); tap("google", 1)
        val authorization = server!!.takeRequest(); assertTrue(authorization.path!!.startsWith("/api/v1/auth/oauth/authorize/google"))
        capture("google-pending")
        scenario!!.recreate(); scenario!!.recreate(); compose.waitForIdle(); assertEquals(1, registry.launches.size)
        assertEquals(1, server!!.requestCount); assertTrue(session!!.state.value.busy)
        val saved = checkNotNull(NativeSignInLifecycleTestActivity.saved)
        val parcel = Parcel.obtain(); val bytes = try { parcel.writeBundle(saved); parcel.marshall() } finally { parcel.recycle() }
        val link = callback(authorization)
        val redirect = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(context.packageName)
        assertEquals(NativeOAuthRedirectActivity::class.java.name, redirect.resolveActivity(context.packageManager)?.className)
        assertNull(Intent(Intent.ACTION_VIEW, Uri.parse(link.replace("/${BuildConfig.APPLICATION_ID}", "/other.package")))
            .setPackage(context.packageName).resolveActivity(context.packageManager))
        context.startActivity(redirect.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(10_000) { completions.get()==1 }
        dispatch(0, AuthTabIntent.RESULT_CANCELED); compose.waitForIdle()
        val exchange = server!!.takeRequest(); val form = exchange.body.readUtf8().split('&').associate {
            val pair = it.split('=', limit = 2); URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") }
        val verifier = checkNotNull(form["code_verifier"]); assertEquals(64, verifier.length)
        fun contains(pattern: ByteArray) = (0..bytes.size-pattern.size).any { start -> pattern.indices.all { bytes[start+it]==pattern[it] } }
        assertFalse(contains(verifier.toByteArray())); assertFalse(contains(verifier.toByteArray(Charsets.UTF_16LE)))
        assertTrue(account!!.isSignedIn()); assertEquals("fixture-access", store!!.load()!!.getString("access_token"))
        assertNotNull(store!!.taskSession()); assertEquals(1, completions.get()); preservedPairing()
        assertFalse(NativeOAuthRedirectCallbacks.deliver(link)); assertTrue(browser!!.presentations.value.isEmpty())
        File(folder, "recreation-result.json").writeText(JSONObject().put("actualActivityRecreations", 2).put("browserLaunches", registry.launches.size)
            .put("httpRequests", server!!.requestCount).put("completionCalls", completions.get()).put("savedStateBytes", bytes.size)
            .put("pkceAbsentFromSavedState", true).put("realFixtureStoreSessionPublished", true).put("pairingPreserved", true)
            .put("scope", "Production account store/client/session/Compose/ActivityResult contract and actual exported fallback Activity with local HTTP and controlled browser result; not a real provider/browser sign-in.").toString(2))
    }

    @Test fun oldAuthTabCancellationCannotRetireTheRetryAfterAnInteractiveTimeout() {
        start(deadline = 2_000); enqueueAuthorization(); tap("apple", 1); val first = server!!.takeRequest()
        compose.waitUntil(10_000) { session!!.state.value.error == NativeSignInError.TIMED_OUT }
        enqueueAuthorization(); enqueueTokens(); tap("github", 2); val second = server!!.takeRequest()
        dispatch(0, AuthTabIntent.RESULT_CANCELED); compose.waitForIdle()
        assertEquals(NativeOAuthProvider.GITHUB, session!!.state.value.provider); assertTrue(session!!.state.value.busy)
        assertFalse(NativeOAuthRedirectCallbacks.deliver(callback(first)))
        dispatch(1, AuthTabIntent.RESULT_OK, callback(second))
        compose.waitUntil(10_000) { completions.get()==1 }; assertTrue(account!!.isSignedIn()); preservedPairing()
        assertEquals(3, server!!.requestCount); assertTrue(browser!!.presentations.value.isEmpty())
        File(folder, "retry-result.json").writeText(JSONObject().put("browserLaunches", registry.launches.size)
            .put("fixtureDeadlineMillis", 2_000).put("httpRequests", server!!.requestCount).put("completionCalls", completions.get())
            .put("oldNativeCancelAndFallbackIgnored", true).put("pairingPreserved", true).toString(2))
    }

    @Test fun realAccountSignOutRetiresTheBrowserAndLateCallbacksWithoutRemovingPairings() {
        start(); enqueueAuthorization(); tap("google", 1); val request = server!!.takeRequest()
        instrumentation.runOnMainSync { assertTrue(account!!.signOut()) }
        compose.waitUntil(10_000) { !session!!.state.value.busy }
        dispatch(0, AuthTabIntent.RESULT_OK, callback(request)); compose.waitForIdle()
        assertFalse(account!!.isSignedIn()); assertEquals(0, completions.get()); assertEquals(1, server!!.requestCount)
        assertFalse(NativeOAuthRedirectCallbacks.deliver(callback(request))); preservedPairing()
        File(folder, "signout-result.json").writeText(JSONObject().put("httpRequests", server!!.requestCount)
            .put("completionCalls", completions.get()).put("lateCallbackIgnored", true).put("pairingPreserved", true).toString(2))
    }

    @Test fun detachedHostConsumesItsPendingResultAfterReattachmentWithoutRelaunching() {
        start(); enqueueAuthorization(); tap("google", 1); server!!.takeRequest()
        instrumentation.runOnMainSync { NativeSignInLifecycleTestActivity.oauthHostVisible.value = false }
        compose.waitForIdle(); dispatch(0, AuthTabIntent.RESULT_CANCELED)
        assertTrue(session!!.state.value.busy)
        instrumentation.runOnMainSync { NativeSignInLifecycleTestActivity.oauthHostVisible.value = true }
        compose.waitUntil(10_000) { !session!!.state.value.busy }
        assertNull(session!!.state.value.error); assertEquals(1, registry.launches.size); assertEquals(1, server!!.requestCount)
        assertFalse(account!!.isSignedIn()); assertTrue(browser!!.presentations.value.isEmpty()); preservedPairing()
        File(folder, "reattachment-result.json").writeText(JSONObject().put("browserLaunches", registry.launches.size)
            .put("httpRequests", server!!.requestCount).put("pendingResultConsumed", true).put("pairingPreserved", true).toString(2))
    }
}
