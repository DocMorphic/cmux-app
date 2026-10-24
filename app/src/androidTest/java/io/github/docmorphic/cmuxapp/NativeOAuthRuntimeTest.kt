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
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
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
    private var storeName: String? = null
    private var account: NativeAccount? = null
    private var browser: NativeOAuthBrowser? = null
    private var server: MockWebServer? = null
    private var authWork: NativeAuthWork? = null
    private var refreshRelease: java.util.concurrent.CountDownLatch? = null
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
        refreshRelease?.countDown()
        instrumentation.runOnMainSync {
            session?.close(); scope?.cancel()
            browser?.presentations?.value?.forEach { browser!!.finishPresentation(it.id) }
        }
        scenario?.close(); server?.close(); store?.clear()
        NativeSignInLifecycleTestActivity.session = null; NativeSignInLifecycleTestActivity.completed = null
        NativeSignInLifecycleTestActivity.oauthBrowser = null; NativeSignInLifecycleTestActivity.oauthRegistryOwner = null
        NativeSignInLifecycleTestActivity.oauthHostVisible.value = true
    }
    private fun start(deadline: Long = 300_000, refresh: ((String) -> String)? = null) {
        server = MockWebServer().apply { start() }
        storeName = "oauth-runtime-"+UUID.randomUUID()
        store = NativeCredentialStore(context, storeName!!).apply { update {
            it.put("pairings", JSONArray().put(NativePairingRecords.encode(pairing))).put("fixture_sentinel", "preserve")
        } }
        browser = NativeOAuthBrowser()
        authWork = store!!.authWork
        account = NativeAccount(store!!, oauthClientOverride = NativeOAuthClient("fixture-project", "fixture-public-key", server!!.url("/")), oauthBrowser = browser!!,
            refreshOverride = refresh, authWorkOverride = authWork)
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
    private fun enqueueUser() = server!!.enqueue(MockResponse().setBody("{\"id\":\"fixture-user\",\"primary_email\":\"fixture@example.test\",\"display_name\":\"Fixture\"}"))
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

    @Test fun providerPreflightJoinsAParkedOrdinaryRefreshBeforeStartingItsActualHttpExchange() = refreshPreflight(false)
    @Test fun cancelledForcedRefreshRejectionCannotClearTheSessionDuringProviderPreflight() = refreshPreflight(true)
    private fun refreshPreflight(rejected: Boolean) {
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1); refreshRelease = release
        val refresher: (String) -> String = { entered.countDown(); check(release.await(15, TimeUnit.SECONDS));
            if (rejected) throw NativeAccount.InvalidRefreshToken()
            "stale-refreshed-access" }
        start()
        val backgroundStore = NativeCredentialStore(context, storeName!!)
        val backgroundAccount = NativeAccount(backgroundStore, NativeOAuthClient("fixture-project", "fixture-public-key", server!!.url("/")),
            NativeOAuthBrowser(), refreshOverride = refresher)
        assertSame(authWork, backgroundStore.authWork)
        store!!.update { it.put("access_token", "old-access").put("refresh_token", "old-refresh").put("task_session", "old-login") }
        val old = scope!!.launch(Dispatchers.IO) { runCatching { backgroundAccount.accessToken(forceRefresh = rejected) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS)); enqueueAuthorization(); enqueueTokens(); enqueueUser()
        compose.onNodeWithTag("signin.google").performScrollTo().performClick()
        compose.waitUntil(10_000) { authWork!!.cancelledCount > 0 }
        assertTrue(session!!.state.value.busy); assertEquals(1L, release.count); assertEquals(0, server!!.requestCount)
        // The public waiter may detach promptly; the real shared exchange must remain tracked until it returns.
        assertTrue(authWork!!.activeCount>0); assertEquals("old-access", store!!.load()!!.getString("access_token"))
        release.countDown(); compose.waitUntil(10_000) { registry.launches.size == 1 }
        val authorization = server!!.takeRequest(); assertEquals("old-access", store!!.load()!!.getString("access_token"))
        dispatch(0, AuthTabIntent.RESULT_OK, callback(authorization)); compose.waitUntil(10_000) { completions.get() == 1 }
        runBlocking { withTimeout(5000) { old.join() } }
        compose.waitUntil(5000) { authWork!!.activeCount == 0 }
        assertEquals("fixture-access", store!!.load()!!.getString("access_token")); assertNotEquals("old-login", store!!.taskSession())
        assertEquals(3, server!!.requestCount); assertTrue(account!!.isSignedIn()); preservedPairing()
        File(folder, if (rejected) "refresh-rejection-preflight-result.json" else "refresh-preflight-result.json").writeText(JSONObject().put("newHttpWaitedForActualRefreshTermination", true)
            .put("separateAccountAndStoreObjectsShareWorkOwnership", true)
            .put("cancelledRefreshDidNotWriteStaleAccess", true).put("cancelledRejectionDidNotClearSession", rejected).put("httpRequests", server!!.requestCount)
            .put("completionCalls", completions.get()).put("pairingPreserved", true).toString(2))
    }

    @Test fun restrictedAndAnonymousHttpUsersCannotPublishAStoredCredentialSession() {
        start()
        for ((index, flag) in listOf("is_restricted", "is_anonymous").withIndex()) {
            enqueueAuthorization(); enqueueTokens(); server!!.enqueue(MockResponse().setBody("{\"id\":\"fixture-user\",\"$flag\":true}"))
            tap(if (index == 0) "apple" else "google", index + 1); val authorization = server!!.takeRequest()
            dispatch(index, AuthTabIntent.RESULT_OK, callback(authorization))
            compose.waitUntil(10_000) { !session!!.state.value.busy }
            assertEquals(NativeSignInError.UNAUTHORIZED, session!!.state.value.error)
            assertFalse(account!!.isSignedIn()); assertNull(store!!.taskSession()); assertEquals(0, completions.get())
            assertTrue(NativeSignInCompletion.isPending(store!!.load())); assertFalse(store!!.load()!!.has(NativeAccountProfileCache.KEY))
            server!!.takeRequest(); server!!.takeRequest(); preservedPairing()
            instrumentation.runOnMainSync { assertTrue(account!!.signOut()) }
        }
        assertEquals(6, server!!.requestCount)
        File(folder, "restricted-users-result.json").writeText(JSONObject().put("anonymousAndRestrictedRejected", true)
            .put("httpRequests", server!!.requestCount).put("completionCalls", completions.get()).put("pairingPreserved", true).toString(2))
    }

    @Test fun googleFallbackRedirectSurvivesTwoRecreationsAndPublishesIntoTheRealFixtureStoreOnce() {
        start(); enqueueAuthorization(); enqueueTokens(); enqueueUser(); tap("google", 1)
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
        val verification = server!!.takeRequest(); assertEquals("/api/v1/users/me", verification.requestUrl!!.encodedPath)
        assertEquals("fixture-access", verification.getHeader("x-hexclave-access-token")); assertNull(verification.getHeader("Authorization"))
        assertEquals(3, server!!.requestCount); assertFalse(NativeSignInCompletion.isPending(store!!.load()))
        assertEquals("fixture-user", store!!.load()!!.getJSONObject(NativeAccountProfileCache.KEY).getString("user"))
        assertFalse(NativeOAuthRedirectCallbacks.deliver(link)); assertTrue(browser!!.presentations.value.isEmpty())
        File(folder, "recreation-result.json").writeText(JSONObject().put("actualActivityRecreations", 2).put("browserLaunches", registry.launches.size)
            .put("httpRequests", server!!.requestCount).put("completionCalls", completions.get()).put("savedStateBytes", bytes.size)
            .put("pkceAbsentFromSavedState", true).put("realFixtureStoreSessionPublished", true).put("pairingPreserved", true)
            .put("scope", "Production account store/client/session/Compose/ActivityResult contract and actual exported fallback Activity with local HTTP and controlled browser result; not a real provider/browser sign-in.").toString(2))
    }

    @Test fun oldAuthTabCancellationCannotRetireTheRetryAfterAnInteractiveTimeout() {
        start(deadline = 2_000); enqueueAuthorization(); tap("apple", 1); val first = server!!.takeRequest()
        compose.waitUntil(10_000) { session!!.state.value.error == NativeSignInError.TIMED_OUT }
        enqueueAuthorization(); enqueueTokens(); enqueueUser(); tap("github", 2); val second = server!!.takeRequest()
        dispatch(0, AuthTabIntent.RESULT_CANCELED); compose.waitForIdle()
        assertEquals(NativeOAuthProvider.GITHUB, session!!.state.value.provider); assertTrue(session!!.state.value.busy)
        assertFalse(NativeOAuthRedirectCallbacks.deliver(callback(first)))
        dispatch(1, AuthTabIntent.RESULT_OK, callback(second))
        compose.waitUntil(10_000) { completions.get()==1 }; assertTrue(account!!.isSignedIn()); preservedPairing()
        assertEquals(4, server!!.requestCount); assertTrue(browser!!.presentations.value.isEmpty())
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

    @Test fun aStoredTokenPairCannotPublishOrAuthorizeWorkWhileUserVerificationIsPendingAndSignOutWins() {
        start(); enqueueAuthorization(); enqueueTokens(); server!!.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        tap("google", 1); val authorization = server!!.takeRequest()
        dispatch(0, AuthTabIntent.RESULT_OK, callback(authorization))
        assertEquals("/api/v1/auth/oauth/token", server!!.takeRequest(10, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath)
        assertEquals("/api/v1/users/me", server!!.takeRequest(10, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath)
        assertEquals("fixture-refresh", store!!.load()!!.getString("refresh_token"))
        assertTrue(NativeSignInCompletion.isPending(store!!.load())); assertFalse(account!!.isSignedIn()); assertNull(store!!.taskSession())
        runBlocking { assertNull(account!!.accessToken()) }
        assertEquals(0, completions.get()); assertTrue(session!!.state.value.busy); preservedPairing()
        instrumentation.runOnMainSync { assertTrue(account!!.signOut()) }
        compose.waitUntil(10_000) { !session!!.state.value.busy }
        assertFalse(account!!.isSignedIn()); assertFalse(NativeSignInCompletion.isPending(store!!.load()))
        assertFalse(store!!.load()!!.optString("refresh_token").isNotBlank()); assertEquals(0, completions.get()); preservedPairing()
        File(folder, "pending-verification-signout-result.json").writeText(JSONObject()
            .put("storedTokensDidNotPublish", true).put("pendingTokensDidNotAuthorizeBackgroundWork", true)
            .put("signOutWonDuringActualUserRequest", true).put("httpRequests", server!!.requestCount)
            .put("completionCalls", completions.get()).put("pairingPreserved", true).toString(2))
    }

    @Test fun failedUserVerificationStaysSignedOutAndAFreshAccountOwnerRestoresTheStoredPendingSession() = restoreAfterFailedUserFetch(false)
    @Test fun aFreshOwnerRefreshesARejectedPendingAccessTokenBeforeRestoringTheVerifiedUser() = restoreAfterFailedUserFetch(true)
    private fun restoreAfterFailedUserFetch(expired: Boolean) {
        start(); enqueueAuthorization(); enqueueTokens(); server!!.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        tap("github", 1); val authorization = server!!.takeRequest()
        dispatch(0, AuthTabIntent.RESULT_OK, callback(authorization))
        compose.waitUntil(10_000) { !session!!.state.value.busy }
        assertEquals(NativeSignInError.SERVER_ERROR, session!!.state.value.error)
        assertFalse(account!!.isSignedIn()); assertNull(store!!.taskSession()); assertEquals(0, completions.get())
        assertTrue(NativeSignInCompletion.isPending(store!!.load())); assertFalse(store!!.load()!!.has(NativeAccountProfileCache.KEY))
        server!!.takeRequest(); server!!.takeRequest()
        if (expired) {
            server!!.enqueue(MockResponse().setResponseCode(401).setHeader("x-hexclave-known-error","invalid_access_token").setBody("{}"))
            server!!.enqueue(MockResponse().setBody("{\"access_token\":\"refreshed-access\"}"))
        }
        enqueueUser()
        val restored = NativeAccount(store!!, NativeOAuthClient("fixture-project", "fixture-public-key", server!!.url("/")), NativeOAuthBrowser())
        runBlocking { restored.restorePendingSignIn() }
        val request = server!!.takeRequest(); assertEquals("/api/v1/users/me", request.requestUrl!!.encodedPath)
        assertEquals("fixture-access", request.getHeader("x-hexclave-access-token"))
        if (expired) {
            val refresh = server!!.takeRequest(); assertEquals("/api/v1/auth/oauth/token", refresh.requestUrl!!.encodedPath)
            val form = refresh.body.readUtf8().split('&').associate { field ->
                val pair = field.split('=', limit = 2); URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") }
            assertEquals("refresh_token", form["grant_type"]); assertEquals("fixture-refresh", form["refresh_token"])
            assertEquals("fixture-project", form["client_id"])
            val verified = server!!.takeRequest(); assertEquals("/api/v1/users/me", verified.requestUrl!!.encodedPath)
            assertEquals("refreshed-access", verified.getHeader("x-hexclave-access-token"))
            assertEquals(request.getHeader("x-hexclave-random-nonce"),verified.getHeader("x-hexclave-random-nonce"))
            assertEquals("fixture-refresh",verified.getHeader("x-hexclave-refresh-token"))
        }
        assertEquals(if (expired) 6 else 4, server!!.requestCount)
        assertTrue(restored.isSignedIn()); assertNotNull(store!!.taskSession()); preservedPairing()
        assertEquals("fixture-user", store!!.load()!!.getJSONObject(NativeAccountProfileCache.KEY).getString("user"))
        File(folder, if (expired) "orphan-refresh-restoration-result.json" else "orphan-restoration-result.json").writeText(JSONObject().put("failedUserFetchDidNotPublish", true)
            .put("freshOwnerRestoredStoredPendingSession", true).put("noSecondCredentialExchange", true)
            .put("refreshedExpiredAccessBeforePublication", expired)
            .put("httpRequests", server!!.requestCount).put("pairingPreserved", true).toString(2))
    }

}
