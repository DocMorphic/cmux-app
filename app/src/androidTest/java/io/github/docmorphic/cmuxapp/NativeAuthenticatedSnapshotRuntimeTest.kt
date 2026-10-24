package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

/** Actual account/store/HTTP ownership; provider authorization and server responses are local fixtures. */
class NativeAuthenticatedSnapshotRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val a=NativeSignInUser("account-a","Account A",null,null)
    private val b=NativeSignInUser("account-b","Account B",null,null)
    private fun token(expired:Boolean=false)="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(
        JSONObject().put("exp",System.currentTimeMillis()/1000+(if(expired)-3600 else 3600)).toString().toByteArray())+".fixture"
    private fun client(server:MockWebServer)=NativeOAuthClient("fixture-project","fixture-key",server.url("/"))
    private fun seed(server:MockWebServer,access:String?=token()) {store.update {root->
        root.put("task_session","same-login").put("access_token",access.orEmpty()).put("refresh_token","saved-refresh")
            .put("fixture_pairing_marker","retain")
        NativeAuthUserCache.write(root,client(server).accountEnvironment,a)
    }}
    private fun account(server:MockWebServer,reader:NativeCredentialStore=store,browser:NativeOAuthBrowser=NativeOAuthBrowser())=
        NativeAccount(reader,client(server),browser)
    private fun record(case:String,values:JSONObject) {File(context.getExternalFilesDir(null),"authenticated-snapshot").apply {mkdirs()}
        .resolve("$case.json").writeText(values.put("fixtureOnly",true).toString(2))}
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="authenticated-snapshot-fixture-"+UUID.randomUUID();alias="cmux_authenticated_snapshot_fixture_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(context,name,alias)
    }
    @After fun cleanup() {scope.cancel();store.clear();context.deleteSharedPreferences(name)
        KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.deleteEntry(alias)}

    @Test fun completeStaleStoredPairReturnsWhileLaunchRefreshIsStillBlocked()=runBlocking<Unit> {
        MockWebServer().use {server->
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val stale=token(true);val fresh=token()
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path=="/api/v1/auth/oauth/token") {entered.countDown();check(release.await(10,TimeUnit.SECONDS))
                    return MockResponse().setBody(JSONObject().put("access_token",fresh).toString())}
                return MockResponse().setBody("{\"id\":\"account-a\"}")
            }}
            seed(server,stale);val account=account(server);account.sessionRestore.start(scope)
            try {
                assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                val started=android.os.SystemClock.elapsedRealtime();val snapshot=withTimeout(2000){account.authenticatedSessionSnapshot()}
                val elapsed=android.os.SystemClock.elapsedRealtime()-started
                assertEquals(stale,snapshot.accessToken);assertEquals("saved-refresh",snapshot.refreshToken)
                assertEquals(a.id,snapshot.owner.identity.userId);assertEquals(1,server.requestCount);assertTrue(account.sessionRestore.revalidating.value)
                release.countDown();account.sessionRestore.awaitBootstrapped();assertEquals(2,server.requestCount)
                assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
                record("stored-pair-during-launch",JSONObject().put("milliseconds",elapsed).put("returnedBeforeRefreshResponse",true)
                    .put("additionalSnapshotHttpRequests",0).put("pairingPreserved",true))
            } finally {release.countDown()}
        }
    }
    @Test fun refreshOnlyAccountCaptureDoesNotMintOrRequireAnAccessToken()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server,null);val account=account(server)
            val snapshot=account.authenticatedRefreshTokenSnapshot();assertEquals(a.id,snapshot.owner.identity.userId)
            assertEquals("saved-refresh",snapshot.refreshToken);assertEquals(0,server.requestCount)
            assertEquals("",store.requireReadable()!!.getString("access_token"))
            record("refresh-only",JSONObject().put("httpRequests",0).put("accessTokenNotRequired",true))}
    }
    @Test fun restoringALegacySessionWithoutALoginReceiptPublishesASnapshotOwner()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server);store.update {it.remove("task_session")}
            val account=account(server);server.enqueue(MockResponse().setBody("{\"id\":\"account-a\"}"))
            account.sessionRestore.start(scope);withTimeout(5000){account.sessionRestore.awaitBootstrapped()}
            val snapshot=account.authenticatedSessionSnapshot();assertEquals(a.id,snapshot.owner.identity.userId)
            assertEquals(store.taskSession(),snapshot.owner.login);assertTrue(snapshot.owner.login.isNotBlank())
            assertEquals(1,server.requestCount);assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
            record("legacy-login-receipt",JSONObject().put("restoredReceiptBoundToPublishedIdentity",true).put("pairingPreserved",true).put("httpRequests",1))}
    }
    @Test fun orphanedPendingExchangeCannotExposeItsUnverifiedPairOrClearStoredCredentials()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server);store.update {it.put(NativeSignInCompletion.PENDING,"same-login")}
            val account=account(server);val cipher=context.getSharedPreferences(name,0).getString("state",null)
            assertEquals(NativeSignInError.NETWORK_ERROR,(runCatching {account.authenticatedSessionSnapshot()}.exceptionOrNull() as NativeSignInFailure).reason)
            assertEquals(cipher,context.getSharedPreferences(name,0).getString("state",null));assertEquals(0,server.requestCount)
            record("orphan-pending",JSONObject().put("retryableWithoutLiveWriter",true).put("ciphertextPreserved",true).put("httpRequests",0))}
    }
    @Test fun anotherAccountObjectCannotCaptureDuringProviderPreflightUserFetchOrAfterReplacement()=runBlocking<Unit> {
        MockWebServer().use {server->
            val userEntered=CountDownLatch(1);val userRelease=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when {
                request.path!!.startsWith("/api/v1/auth/oauth/authorize/")->MockResponse().setBody("{\"location\":\"https://provider.example/authorize\"}")
                request.path=="/api/v1/auth/oauth/token"->MockResponse().setBody(JSONObject().put("access_token",token()).put("refresh_token","new-refresh").toString())
                else->{userEntered.countDown();check(userRelease.await(10,TimeUnit.SECONDS));MockResponse().setBody("{\"id\":\"account-b\"}")}
            }}
            seed(server);val old=account(server);val oldSnapshot=old.authenticatedSessionSnapshot()
            val browser=NativeOAuthBrowser();val replacement=account(server,browser=browser)
            val signingIn=scope.async {replacement.signInWithProvider(NativeOAuthProvider.GOOGLE)}
            try {
                val launch=withTimeout(5000){while(browser.state.value==null)delay(10);checkNotNull(browser.state.value)}
                val authorization=server.takeRequest(5,TimeUnit.SECONDS)!!.requestUrl!!
                assertEquals(NativeSignInError.NETWORK_ERROR,(runCatching {old.authenticatedSessionSnapshot()}.exceptionOrNull() as NativeSignInFailure).reason)
                assertEquals("saved-refresh",store.requireReadable()!!.getString("refresh_token"))
                assertFalse(old.isAuthenticatedSessionCurrent(oldSnapshot.owner))
                assertTrue(browser.claim(launch.id));assertTrue(browser.receive(launch.id,
                    authorization.queryParameter("redirect_uri")+"&state="+authorization.queryParameter("state")+"&code=fixture-code"))
                assertTrue(withContext(Dispatchers.IO){userEntered.await(5,TimeUnit.SECONDS)})
                assertTrue(NativeSignInCompletion.isPending(store.requireReadable()))
                assertEquals(NativeSignInError.NETWORK_ERROR,(runCatching {old.authenticatedSessionSnapshot()}.exceptionOrNull() as NativeSignInFailure).reason)
                userRelease.countDown();withTimeout(5000){signingIn.await()}
                assertEquals(b.id,replacement.authenticatedSessionSnapshot().owner.identity.userId)
                assertFalse(old.isAuthenticatedSessionCurrent(oldSnapshot.owner))
                assertEquals(NativeSignInError.UNAUTHORIZED,(runCatching {old.authenticatedSessionSnapshot()}.exceptionOrNull() as NativeSignInFailure).reason)
                old.reconcileStoredSession();assertEquals(b.id,old.authenticatedSessionSnapshot().owner.identity.userId)
                assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
                record("shared-writer-transition",JSONObject().put("providerPreflightBlocked",true).put("unverifiedTokenWriteBlocked",true)
                    .put("oldAccountObjectFencedAfterPublication",true).put("explicitReconciliationPublishesReplacement",true).put("pairingPreserved",true))
            } finally {userRelease.countDown();signingIn.cancelAndJoin()}
        }
    }
    @Test fun handoffResponseCannotInstallCookiesAfterSameUserGenerationReplacement()=runBlocking<Unit> {
        MockWebServer().use {server->
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                entered.countDown();check(release.await(10,TimeUnit.SECONDS))
                return MockResponse().setResponseCode(204).addHeader("X-Cmux-App-Session-Handoff","ready")
                    .addHeader("Set-Cookie","stack-access=fixture; Path=/; HttpOnly")
                    .addHeader("Set-Cookie","stack-refresh-fixture-project=fixture; Path=/; HttpOnly")
            }}
            seed(server);val account=account(server)
            val broker=NativeWebSessionBroker(WhatsNewWebPolicy(server.url("/").toString()),"fixture-project",
                {account.webSessionSnapshot("same-login")},account::isWebSessionCurrent)
            val cookies=scope.async {broker.cookies(server.url("/news").toString())}
            try {
                assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                account.sessionRestore.publishedSignIn(a);release.countDown();assertTrue(withTimeout(5000){cookies.await()}.isEmpty())
                assertEquals(1,server.requestCount);record("handoff-generation-fence",JSONObject().put("sameUserNewGenerationCookiesRejected",true).put("httpRequests",1))
            } finally {release.countDown();cookies.cancelAndJoin()}
        }
    }
    @Test fun signOutCaptureImmediatelyFencesSnapshotsAndTokenRotationKeepsTheSameIdentity()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server);val account=account(server);val snapshot=account.authenticatedSessionSnapshot()
            store.update {it.put("access_token",token()).put("refresh_token","rotated-refresh")}
            assertTrue(account.isAuthenticatedSessionCurrent(snapshot.owner))
            val finish=store.authWork.beginSignOutCapture()
            try {assertFalse(account.isAuthenticatedSessionCurrent(snapshot.owner))
                assertEquals(NativeSignInError.NETWORK_ERROR,(runCatching {account.authenticatedSessionSnapshot()}.exceptionOrNull() as NativeSignInFailure).reason)
            } finally {finish()}
            assertTrue(account.isAuthenticatedSessionCurrent(snapshot.owner));assertEquals(0,server.requestCount)
            record("capture-and-rotation",JSONObject().put("captureStartFencesBeforeLocalClear",true).put("sameIdentityRotationPreserved",true).put("httpRequests",0))}
    }
    @Test fun unavailableEncryptedStoragePreservesCiphertextAndCannotProduceASnapshot()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server);var unavailable=false
            val reader=NativeCredentialStore.withKeyProvider(context,name,alias){if(unavailable)throw KeyStoreException("Fixture unavailable")
                KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.getKey(alias,null) as SecretKey}
            val account=account(server,reader);val owner=account.authenticatedSessionSnapshot().owner
            val cipher=context.getSharedPreferences(name,0).getString("state",null);unavailable=true
            assertTrue(runCatching {reader.requireReadable()}.exceptionOrNull() is NativeCredentialReadException)
            assertEquals(NativeSignInError.NETWORK_ERROR,(runCatching {account.authenticatedSessionSnapshot()}.exceptionOrNull() as NativeSignInFailure).reason)
            assertEquals(NativeSignInError.NETWORK_ERROR,(runCatching {account.authenticatedRefreshTokenSnapshot()}.exceptionOrNull() as NativeSignInFailure).reason)
            assertFalse(account.isAuthenticatedSessionCurrent(owner));assertEquals(cipher,context.getSharedPreferences(name,0).getString("state",null))
            unavailable=false;assertEquals(owner,account.authenticatedSessionSnapshot().owner);assertEquals(0,server.requestCount)
            record("unavailable-storage",JSONObject().put("noSnapshotWhileUnavailable",true).put("strictStoreFailureRetained",true)
                .put("applicationPairAndRefreshRetryable",true).put("ciphertextPreserved",true).put("restoredSameIdentity",true))}
    }
    @Test fun completeOriginalReleaseSnapshotsRunOnArt()=runBlocking<Unit> {
        val asset=InstrumentationRegistry.getInstrumentation().context.assets.open("native-snapshot-reference.json").bufferedReader().use{it.readText()}
        record("original-reference",JSONObject().put("cases",checkNativeSnapshotReference(asset)))
    }
    @Test fun unavailableRefreshReadKeepsItsCapturedClassificationWhenATransitionStarts()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server);var unavailable=false
            val reader=NativeCredentialStore.withKeyProvider(context,name,alias){if(unavailable)throw KeyStoreException("Fixture unavailable")
                KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey(alias,null) as SecretKey}
            val account=account(server,reader);val owner=account.authenticatedSessionSnapshot().owner
            val cipher=context.getSharedPreferences(name,0).getString("state",null);unavailable=true
            var finish:()->Unit={}
            val snapshots=NativeAuthenticatedSnapshots(read={reader.requireReadable();error("Unavailable read cannot return tokens")},
                storedCaptureBlocked={false},transitionActive={store.authWork.isCapturingSignOutCredentials},
                awaitBootstrapped={},accessToken={error("Refresh-only capture cannot resolve access")},publishedOwner={owner},availability={
                    assertTrue(runCatching{reader.requireReadable()}.exceptionOrNull() is NativeCredentialReadException)
                    finish=store.authWork.beginSignOutCapture();false
                })
            try {
                assertEquals(NativeSignInError.NETWORK_ERROR,(runCatching{snapshots.refresh()}.exceptionOrNull() as NativeSignInFailure).reason)
                assertTrue(store.authWork.isCapturingSignOutCredentials)
                assertEquals(owner.identity,account.sessionRestore.identity());assertEquals(cipher,context.getSharedPreferences(name,0).getString("state",null))
                assertEquals(0,server.requestCount)
            }finally{finish();unavailable=false}
            record("unavailable-transition",JSONObject().put("capturedUnavailableReadWinsBeforeFinalTransitionGuard",true)
                .put("strictStoreFailureRetained",true).put("ciphertextPreserved",true).put("identityPreserved",true).put("httpRequests",0))
        }
    }
    @Test fun absentIdentityWinsOverUnavailableStorageAndSignOutCapture()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server);var unavailable=false
            val reader=NativeCredentialStore.withKeyProvider(context,name,alias){if(unavailable)throw KeyStoreException("Fixture unavailable")
                KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey(alias,null) as SecretKey}
            val account=account(server,reader);account.sessionRestore.clear(clearCachedUser=false)
            val cipher=context.getSharedPreferences(name,0).getString("state",null);unavailable=true
            val finish=store.authWork.beginSignOutCapture()
            try {
                for(refreshOnly in listOf(false,true)) {
                    val failure=runCatching{if(refreshOnly)account.authenticatedRefreshTokenSnapshot() else account.authenticatedSessionSnapshot()}.exceptionOrNull()
                    assertEquals(NativeSignInError.UNAUTHORIZED,(failure as NativeSignInFailure).reason)
                }
                assertEquals(cipher,context.getSharedPreferences(name,0).getString("state",null));assertEquals(0,server.requestCount)
            }finally{finish();unavailable=false}
            record("absent-identity",JSONObject().put("identityGuardBeforeTransitionAndStorage",true).put("ciphertextPreserved",true).put("httpRequests",0))
        }
    }
    @Test fun oneCaptureDeadlineReturnsBeforeTheRetainedSharedHttpRefresh()=runBlocking<Unit> {
        val rows=org.json.JSONArray()
        for(budget in listOf(1000L,60_000L))MockWebServer().use {server->
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val fresh=token()
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                check(request.path=="/api/v1/auth/oauth/token");entered.countDown();check(release.await(75,TimeUnit.SECONDS))
                return MockResponse().setBody(JSONObject().put("access_token",fresh).toString())
            }}
            seed(server,null)
            // Keep the fixture's transport and SDK attempt alive beyond the production capture budget.
            val http=okhttp3.OkHttpClient.Builder().callTimeout(80,TimeUnit.SECONDS).readTimeout(80,TimeUnit.SECONDS).build()
            val sdk=NativeTokenRefresh(timeoutMillis=90_000)
            try {
                val account=NativeAccount(store,NativeOAuthClient("fixture-project","fixture-key",server.url("/"),http),NativeOAuthBrowser(),refreshCoordinatorOverride=sdk)
                if(budget!=60_000L)account.snapshotTimeoutMillis=budget
                val identity=account.sessionRestore.identity();val started=android.os.SystemClock.elapsedRealtime()
                val capture=scope.async{runCatching{account.authenticatedSessionSnapshot()}.exceptionOrNull()}
                var keeper:Deferred<String?>?=null
                try {
                    assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                    keeper=scope.async{account.sdkAccessToken()}
                    withTimeout(1000){while(sdk.waiterCount<2)delay(1)}
                    assertEquals(NativeSignInError.TIMED_OUT,(withTimeout(budget+3000){capture.await()} as NativeSignInFailure).reason)
                    val elapsed=android.os.SystemClock.elapsedRealtime()-started
                    assertTrue(elapsed>=budget);assertTrue(elapsed<budget+3000)
                    assertTrue(store.authWork.activeCount>0);assertEquals(identity,account.sessionRestore.identity())
                    assertEquals("",store.requireReadable()!!.getString("access_token"))
                    release.countDown();assertEquals(fresh,withTimeout(5000){checkNotNull(keeper).await()})
                    withTimeout(5000){while(store.authWork.activeCount>0)delay(5)}
                    // A separate SDK waiter keeps this shared attempt alive after the capture waiter times out.
                    assertEquals(fresh,store.requireReadable()!!.getString("access_token"))
                    assertEquals(identity,account.sessionRestore.identity());assertEquals(1,server.requestCount)
                    assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
                    assertEquals(fresh,account.authenticatedSessionSnapshot().accessToken)
                    rows.put(JSONObject().put("configuredMillis",budget).put("elapsedMillis",elapsed)
                        .put("waiterReturnedBeforeActualSdkWork",true).put("lateSnapshotNotPublished",true)
                        .put("anotherSdkWaiterRetainedAttempt",true)
                        .put("sharedRefreshPersistedForSurvivingOwner",true).put("httpRequests",1))
                }finally{release.countDown();capture.cancelAndJoin();keeper?.cancelAndJoin();withTimeout(5000){while(store.authWork.activeCount>0)delay(5)}}
            }finally{sdk.close();http.dispatcher.executorService.shutdown();http.connectionPool.evictAll()}
        }
        record("aggregate-deadline",JSONObject().put("rows",rows).put("encryptedStoreUsed",true).put("pairingPreserved",true))
    }
    @Test fun cancelingTheLastSnapshotWaiterRetiresTheSdkAttemptWithoutClearingTheAccount()=runBlocking<Unit> {
        MockWebServer().use {server->
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val fresh=token()
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                entered.countDown();check(release.await(10,TimeUnit.SECONDS));return MockResponse().setBody(JSONObject().put("access_token",fresh).toString())}}
            seed(server,null);val account=account(server);val identity=account.sessionRestore.identity()
            val cipher=context.getSharedPreferences(name,0).getString("state",null)
            val task=scope.async{account.authenticatedSessionSnapshot()}
            try {
                assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                task.cancel();withTimeout(1000){task.join()}
                withTimeout(5000){while(store.authWork.activeCount>0)delay(5)}
                assertEquals(identity,account.sessionRestore.identity());release.countDown()
                assertEquals("",store.requireReadable()!!.getString("access_token"));assertEquals(identity,account.sessionRestore.identity())
                assertEquals(cipher,context.getSharedPreferences(name,0).getString("state",null))
                assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"));assertEquals(1,server.requestCount)
                record("canceled-http",JSONObject().put("lastWaiterRetiredActualSdkWork",true).put("ciphertextPreserved",true)
                    .put("lateHttpResultCannotWrite",true).put("identityPreserved",true).put("pairingPreserved",true))
            }finally{release.countDown();task.cancelAndJoin();withTimeout(5000){while(store.authWork.activeCount>0)delay(5)}}
        }
    }
    @Test fun identityReplacementDuringRealRefreshCannotReturnTheOldSnapshot()=runBlocking<Unit> {
        MockWebServer().use {server->
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                entered.countDown();check(release.await(10,TimeUnit.SECONDS));return MockResponse().setBody(JSONObject().put("access_token",token()).toString())}}
            seed(server,null);val account=account(server);val old=account.sessionRestore.identity()
            val task=scope.async{runCatching{account.authenticatedSessionSnapshot()}.exceptionOrNull()}
            try {
                assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                account.sessionRestore.publishedSignIn(b);release.countDown()
                assertEquals(NativeSignInError.UNAUTHORIZED,(withTimeout(5000){task.await()} as NativeSignInFailure).reason)
                assertNotEquals(old,account.sessionRestore.identity());assertEquals(b.id,account.sessionRestore.identity()!!.userId)
                assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"));assertEquals(1,server.requestCount)
                record("identity-during-http",JSONObject().put("oldSnapshotRejected",true).put("replacementIdentityPreserved",true).put("pairingPreserved",true))
            }finally{release.countDown();task.cancelAndJoin();withTimeout(5000){while(store.authWork.activeCount>0)delay(5)}}
        }
    }
}
