package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

class NativeOrdinaryTokenRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val cores=mutableListOf<NativeTokenRefresh>()
    private fun token(seconds:Long)="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(
        JSONObject().put("exp",System.currentTimeMillis()/1000+seconds).toString().toByteArray())+".fixture"
    private fun client(server:MockWebServer)=NativeOAuthClient("fixture-project","fixture-key",server.url("/"))
    private fun account(server:MockWebServer,core:NativeTokenRefresh?=null,credentials:NativeCredentialStore=store)=NativeAccount(credentials,client(server),NativeOAuthBrowser(),
        tokenRefreshLeadMillis=120_000,refreshCoordinatorOverride=core)
    private fun seed(access:String,refresh:String?="refresh")=store.update {
        it.put("access_token",access).put("refresh_token",refresh ?: "").put("task_session","login").put("fixture_pairing_marker","retain")
    }
    @Before fun setup(){check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="ordinary-token-fixture-"+UUID.randomUUID();alias="cmux_ordinary_token_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(context,name,alias)}
    @After fun cleanup(){runBlocking{withTimeout(5000){while(store.authWork.activeCount>0)delay(5)}}
        cores.forEach{it.close()};store.clear();context.deleteSharedPreferences(name);KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}
    private fun record(case:String,extra:JSONObject){val root=store.requireReadable()!!;assertEquals("retain",root.getString("fixture_pairing_marker"))
        assertFalse(context.getSharedPreferences(name,0).getString("state",null)!!.contains("fixture_pairing_marker"))
        File(context.getExternalFilesDir(null),"ordinary-token").apply{mkdirs()}.resolve("$case.json").writeText(extra
            .put("fixtureOnly",true).put("encryptedStoreUsed",true).put("pairingMarkerPreserved",true).toString(2))}

    @Test fun transientRefreshReturnsMissingAccessForBothAdaptersAndPreservesCredentials()=runBlocking<Unit> {
        MockWebServer().use{server->val old=token(-1);seed(old);val owner=account(server)
            server.enqueue(MockResponse().setResponseCode(503));assertNull(owner.sdkAccessToken())
            server.enqueue(MockResponse().setResponseCode(503));assertNull(owner.resolvedAccessToken(true))
            assertEquals("refresh",store.requireReadable()!!.getString("refresh_token"));assertEquals(old,store.requireReadable()!!.getString("access_token"))
            assertEquals(2,server.requestCount);record("transient-nil",JSONObject().put("optionalAndResolvedReturnedNil",true).put("httpRequests",2))}
    }
    @Test fun sharedDeadlineIsNilForSdkAndTypedTimeoutForResolvedCaller()=runBlocking<Unit> {
        MockWebServer().use{server->seed(token(-1));val core=NativeTokenRefresh(timeoutMillis=500).also{cores+=it}
            val owner=account(server,core);server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val sdk=async(Dispatchers.IO){owner.sdkAccessToken()};val resolved=async(Dispatchers.IO){runCatching{owner.resolvedAccessToken(true)}}
            try {withTimeout(4000){while(core.waiterCount<2)delay(2)}
                assertNull(withTimeout(4000){sdk.await()});val failure=withTimeout(4000){resolved.await()}.exceptionOrNull() as NativeSignInFailure
                assertEquals(NativeSignInError.TIMED_OUT,failure.reason);assertEquals(1,server.requestCount)
                assertEquals("refresh",store.requireReadable()!!.getString("refresh_token"))
                record("adapter-timeout",JSONObject().put("sdkNil",true).put("resolvedTimeout",true).put("sharedRequests",1).put("configuredMillis",500))
            }finally{sdk.cancelAndJoin();resolved.cancelAndJoin()}}
    }
    @Test fun sessionReplacementIsNilForSdkAndCancellationForResolvedCaller()=runBlocking<Unit> {
        MockWebServer().use{server->seed(token(-1));val core=store.refreshCoordinator(client(server).accountEnvironment);val owner=account(server)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val sdk=async(Dispatchers.IO){owner.sdkAccessToken()};val resolved=async(Dispatchers.IO){runCatching{owner.resolvedAccessToken(true)}}
            try {withTimeout(4000){while(core.waiterCount<2)delay(2)}
                store.update{it.put("task_session","replacement").put("access_token",token(3600)).put("refresh_token","replacement-refresh")}
                assertNull(withTimeout(4000){sdk.await()});assertTrue(withTimeout(4000){resolved.await()}.exceptionOrNull() is CancellationException)
                assertEquals("replacement-refresh",store.requireReadable()!!.getString("refresh_token"));assertEquals(1,server.requestCount)
                record("adapter-replacement",JSONObject().put("sdkNil",true).put("resolvedCancellation",true).put("replacementPreserved",true))
            }finally{sdk.cancelAndJoin();resolved.cancelAndJoin()}}
    }
    @Test fun tokenOnlyReadDoesNotMintRefreshOwnershipOrAnAuthenticatedPair()=runBlocking<Unit> {
        MockWebServer().use{server->val access=token(3600);seed(access,null);store.update{it.remove("task_session")};val owner=account(server)
            val before=store.requireReadable().toString();assertEquals(access,owner.sdkAccessToken());assertEquals(access,owner.resolvedAccessToken())
            assertNull(owner.sdkAccessToken(true));assertNull(owner.resolvedAccessToken(true));assertNull(store.taskSession());assertNull(store.refreshView().owner)
            assertTrue(runCatching{owner.authenticatedSessionSnapshot()}.isFailure)
            assertEquals(before,store.requireReadable().toString());assertEquals(0,server.requestCount)
            record("token-only",JSONObject().put("tokenReadWithoutReceiptMutation",true).put("authenticatedPairDenied",true).put("httpRequests",0))}
    }
    @Test fun restoredUserPreflightUsesSdkNilRatherThanEndingAtTheTokenProbeFailure()=runBlocking<Unit> {
        MockWebServer().use{server->seed(token(-1));store.update{NativeAccountProfileCache.writeVerifiedUser(it,"login",client(server).accountEnvironment,
                NativeSignInUser("cached-user","Saved",null,null))}
            val owner=account(server);server.enqueue(MockResponse().setResponseCode(503));server.enqueue(MockResponse().setResponseCode(503))
            assertNull(owner.sessionRestore.restoreIdentityIfNeeded());assertEquals("cached-user",owner.sessionRestore.state.value.user?.id)
            assertNull(owner.sessionRestore.verifiedUser());assertEquals(2,server.requestCount)
            repeat(2){assertEquals("/api/v1/auth/oauth/token",server.takeRequest(2,TimeUnit.SECONDS)!!.path)}
            assertEquals("refresh",store.requireReadable()!!.getString("refresh_token"))
            record("restore-preflight",JSONObject().put("probeAndUserPreflightRan",true).put("userHttpRequests",0).put("cachedPresentationPreserved",true))}
    }
    @Test fun applicationConnectionAccessorRetainsItsRetryableTransientFailure()=runBlocking<Unit> {
        MockWebServer().use{server->seed(token(-1));val owner=account(server)
            server.enqueue(MockResponse().setResponseCode(503))
            val failure=runCatching{owner.accessToken(true)}.exceptionOrNull() as NativeSignInFailure
            assertEquals(NativeSignInError.NETWORK_ERROR,failure.reason)
            assertEquals("refresh",store.requireReadable()!!.getString("refresh_token"));assertEquals(1,server.requestCount)
            record("application-retry",JSONObject().put("applicationRetryClassification",true).put("sdkNilNotTreatedAsPermanentSignInFailure",true))}
    }

    @Test fun rejectionFollowedByReplacementCannotMasqueradeAsTheOriginalClearedOwner()=runBlocking<Unit> {
        MockWebServer().use{server->seed(token(-1));val replaced=java.util.concurrent.atomic.AtomicBoolean(false)
            val reader=NativeCredentialStore.withKeyProvider(context,name,alias){
                val current=store.requireReadable()
                if(current?.optString("refresh_token").isNullOrBlank() && replaced.compareAndSet(false,true)) {
                    store.authSessionEpoch.incrementAndGet()
                    store.update{it.put("task_session","replacement").put("access_token",token(3600)).put("refresh_token","replacement-refresh")}
                }
                KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey(alias,null) as javax.crypto.SecretKey
            }
            val owner=account(server,credentials=reader);server.enqueue(MockResponse().setResponseCode(401))
            val failure=runCatching{owner.resolvedAccessToken(true)}.exceptionOrNull()
            assertTrue(replaced.get());assertTrue(failure.toString(),failure is CancellationException)
            assertEquals("replacement-refresh",store.requireReadable()!!.getString("refresh_token"));assertEquals("replacement",store.taskSession())
            assertEquals(1,server.requestCount)
            record("definitive-replacement",JSONObject().put("replacementDuringPostRejectionRead",true).put("typedCancellationPreserved",true).put("replacementPreserved",true))}
    }

}
