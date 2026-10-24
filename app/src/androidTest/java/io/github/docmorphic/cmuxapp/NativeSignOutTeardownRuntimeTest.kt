package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey

/** Production account teardown with real encrypted fixture documents and local-only HTTP. */
class NativeSignOutTeardownRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String
    private lateinit var alias:String
    private lateinit var store:NativeCredentialStore
    private lateinit var writer:NativeCredentialStore
    private val unavailable=AtomicBoolean(false)
    private val pairing=NativeCredentialStore.PairedMac("fixture-pairing","fixture-mac","Saved Mac")
    private fun keys()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun fresh()="header."+Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"exp\":${System.currentTimeMillis()/1000+3600}}".toByteArray())+".signature"
    private val http=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
        .callTimeout(30,TimeUnit.SECONDS).build()
    private fun client(server:MockWebServer)=NativeOAuthClient("fixture-project","fixture-public",server.url("/").newBuilder().host("127.0.0.1").build(),http)
    private fun account(server:MockWebServer,browser:NativeOAuthBrowser=NativeOAuthBrowser())=
        NativeAccount(store,client(server),browser,refreshOverride={error("Teardown cannot use live token refresh")})
    private fun seed(server:MockWebServer,access:String?=fresh()) {
        writer.update { root ->
            root.put("access_token",access ?: "").put("refresh_token","captured-refresh").put("task_session","captured-login")
                .put("pairings",JSONArray().put(NativePairingRecords.encode(pairing)))
            NativeAccountProfileCache.writeVerifiedUser(root,"captured-login",client(server).accountEnvironment,
                NativeSignInUser("captured-user","Saved Person",null,null))
        }
        store.setAuthTokenHint(client(server).accountEnvironment,true)
    }
    private fun retired() {
        val root=store.requireReadable()!!
        assertEquals("",root.getString("refresh_token"));assertEquals("",root.getString("access_token"))
        assertFalse(root.has("task_session"));assertFalse(root.has(NativeAuthUserCache.KEY));assertEquals(listOf(pairing),store.pairedMacs())
    }
    private fun record(case:String,body:JSONObject) {
        File(context.getExternalFilesDir(null),"signout-teardown").apply { mkdirs() }.resolve("$case.json")
            .writeText(body.put("fixtureOnly",true).toString(2))
    }
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="signout-teardown-fixture-"+UUID.randomUUID();alias="cmux_teardown_fixture_"+UUID.randomUUID()
        writer=NativeCredentialStore.withKeyProvider(context,name,alias,null)
        store=NativeCredentialStore.withKeyProvider(context,name,alias) {
            if(unavailable.get()) throw KeyStoreException("Fixture provider unavailable")
            keys().getKey(alias,null) as SecretKey
        }
    }
    @After fun cleanup()=runBlocking {
        store.authWork.cancelAll(includingSignOut=true);withTimeout(8000) { store.authWork.awaitSignOutTeardown() }
        unavailable.set(false);writer.clear();context.deleteSharedPreferences(name);keys().deleteEntry(alias)
        http.dispatcher.executorService.shutdown();http.connectionPool.evictAll()
    }
    @Test fun defaultFiveSecondDeadlineCancelsOfflineDeleteAfterLocalLogoutAndHook()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val access=fresh();seed(server,access);val owner=account(server);var hooks=0
            owner.onSignedOut={a,r->retired();assertEquals(access,a);assertEquals("captured-refresh",r);hooks++}
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val start=System.nanoTime();assertTrue(owner.signOutAndRevoke())
            assertEquals(NativeAuthState(),owner.sessionRestore.state.value);retired()
            val request=withContext(Dispatchers.IO) { server.takeRequest(3,TimeUnit.SECONDS) }!!
            assertEquals("DELETE",request.method);assertEquals(access,request.getHeader("x-hexclave-access-token"))
            withTimeout(8000) { owner.awaitSignOutTeardown() }
            val millis=(System.nanoTime()-start)/1_000_000
            assertTrue("Actual source default deadline: $millis",millis in 4_000..8_000)
            withTimeout(1000) { while(http.dispatcher.runningCallsCount()!=0)delay(10) }
            assertEquals(1,hooks);assertEquals(1,server.requestCount);retired()
            record("offline-deadline",JSONObject().put("localLogoutBeforeRequest",true).put("hookBeforeDelete",true)
                .put("sourceDefaultDeadlineMillis",5000).put("observedMillis",millis).put("httpCallsDrained",true))
        }
    }
    @Test fun refreshOnlyCaptureMintsEphemerallyAndNeverRestoresTheLiveEncryptedSession()=runBlocking<Unit> {
        MockWebServer().use { server ->
            seed(server,null);val owner=account(server);var hooks=0
            owner.onSignedOut={a,r->retired();assertEquals("minted",a);assertEquals("captured-refresh",r);hooks++}
            server.enqueue(MockResponse().setBody("{\"access_token\":\"minted\"}"));server.enqueue(MockResponse().setResponseCode(204))
            assertTrue(owner.signOutAndRevoke());withTimeout(5000) { owner.awaitSignOutTeardown() }
            assertEquals(1,hooks);retired();assertFalse(owner.isSignedIn())
            val mint=server.takeRequest();assertEquals("/api/v1/auth/oauth/token",mint.path)
            assertTrue(mint.body.readUtf8().contains("refresh_token=captured-refresh"))
            val revoke=server.takeRequest();assertEquals("minted",revoke.getHeader("x-hexclave-access-token"))
            assertEquals("captured-refresh",revoke.getHeader("x-hexclave-refresh-token"))
            record("ephemeral-mint",JSONObject().put("requests",2).put("liveSessionStayedRetired",true).put("hookSawCapturedPair",true))
        }
    }
    @Test fun freshSignInJoinsTheOldDeleteDeadlineBeforeItsBrowserExchange()=runBlocking<Unit> {
        MockWebServer().use { server ->
            seed(server);val browser=NativeOAuthBrowser();val owner=account(server,browser)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));assertTrue(owner.signOutAndRevoke())
            assertEquals("DELETE",withContext(Dispatchers.IO) { server.takeRequest(3,TimeUnit.SECONDS) }!!.method)
            server.enqueue(MockResponse().setBody("{\"location\":\"https://provider.example/authorize\"}"))
            server.enqueue(MockResponse().setBody(JSONObject().put("access_token",fresh()).put("refresh_token","fresh-refresh").toString()))
            server.enqueue(MockResponse().setBody("{\"id\":\"fresh-user\"}"))
            val joiningStarted=System.nanoTime()
            val signingIn=async(Dispatchers.Default) { owner.signInWithProvider(NativeOAuthProvider.GOOGLE) }
            val launch=withTimeout(8000) { browser.state.filterNotNull().first() };assertTrue(browser.claim(launch.id))
            val joinedMillis=(System.nanoTime()-joiningStarted)/1_000_000
            assertTrue("Fresh login must retain the bounded revoke attempt: $joinedMillis",joinedMillis in 4_000..8_000)
            withTimeout(1000) { owner.awaitSignOutTeardown() }
            val authorize=server.takeRequest(3,TimeUnit.SECONDS)!!.requestUrl!!
            assertTrue(browser.receive(launch.id,authorize.queryParameter("redirect_uri")+"&state="+authorize.queryParameter("state")+"&code=fixture-code"))
            withTimeout(5000) { signingIn.await() }
            assertEquals("fresh-refresh",store.requireReadable()!!.getString("refresh_token"))
            assertEquals("fresh-user",owner.sessionRestore.state.value.user?.id);assertEquals(4,server.requestCount)
            assertEquals(listOf(pairing),store.pairedMacs())
            record("fresh-login",JSONObject().put("oldTeardownJoinedBeforeExchange",true).put("joinedMillis",joinedMillis).put("freshLoginPreserved",true).put("requests",4))
        }
    }
    @Test fun unknownCaptureNeverFallsBackToCredentialsWrittenAfterStorageRecovery()=runBlocking<Unit> {
        MockWebServer().use { server ->
            seed(server);val owner=account(server);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            owner.onSignedOut={a,r->assertNull(a);assertNull(r);entered.complete(Unit);release.await()}
            unavailable.set(true);assertTrue(owner.signOutAndRevoke());withTimeout(3000) { entered.await() }
            unavailable.set(false)
            writer.update { it.put("access_token",fresh()).put("refresh_token","other-refresh").put("task_session","other-login") }
            release.complete(Unit);withTimeout(3000) { owner.awaitSignOutTeardown() }
            assertEquals("other-refresh",store.requireReadable()!!.getString("refresh_token"));assertEquals(0,server.requestCount)
            record("no-live-fallback",JSONObject().put("capturedPairUnavailable",true).put("newCredentialsNeverUsedForOldLogout",true))
        }
    }
    @Test fun mismatchedConditionalLogoutCannotStartAHookOrRevokeAnotherAccount()=runBlocking<Unit> {
        MockWebServer().use { server ->
            seed(server);val owner=account(server);var hooks=0;owner.onSignedOut={_,_->hooks++}
            assertFalse(owner.signOutAndRevoke("another-login"));owner.awaitSignOutTeardown()
            assertEquals(0,hooks);assertEquals(0,server.requestCount);assertEquals("captured-refresh",store.requireReadable()!!.getString("refresh_token"))
            assertTrue(owner.sessionRestore.state.value.authenticated)
            record("conditional-owner",JSONObject().put("otherAccountUntouched",true).put("requests",0))
        }
    }
}
