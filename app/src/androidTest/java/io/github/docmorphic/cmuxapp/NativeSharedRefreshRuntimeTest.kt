package io.github.docmorphic.cmuxapp

import android.os.SystemClock
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

/** Real encrypted fixture documents, actual account objects and local HTTP; no live account/phone. */
class NativeSharedRefreshRuntimeTest {
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val now=1_800_000_000_000L
    private val cores=mutableSetOf<NativeTokenRefresh>()
    private fun keys()=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun token()="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject().put("exp",now/1000+90).toString().toByteArray())+".fixture"
    private fun client(server:MockWebServer)=NativeOAuthClient("fixture-project","fixture-key",server.url("/"))
    private fun owner(server:MockWebServer,reader:NativeCredentialStore=store,core:NativeTokenRefresh?=null):NativeAccount {
        cores+=core ?: reader.refreshCoordinator(client(server).accountEnvironment)
        return NativeAccount(reader,client(server),NativeOAuthBrowser(),tokenRefreshLeadMillis=120_000,tokenNowMillis={now},refreshCoordinatorOverride=core)
    }
    private fun seed() { store.update { it.put("access_token",token()).put("refresh_token","saved-refresh").put("task_session","saved-login").put("fixture_pairing_marker","retain") } }
    private fun core()=NativeTokenRefresh(now=SystemClock::elapsedRealtime).also { cores+=it }
    private suspend fun waitFor(test:()->Boolean) { withTimeout(5000) { while(!test())delay(10) } }
    private fun record(case:String,values:JSONObject) { File(target.getExternalFilesDir(null),"shared-refresh").apply { mkdirs() }
        .resolve("$case.json").writeText(values.put("fixtureOnly",true).toString(2)) }
    private fun block(server:MockWebServer,entered:CountDownLatch,release:CountDownLatch,response:MockResponse) {
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse { entered.countDown();check(release.await(15,TimeUnit.SECONDS));return response }
        }
    }
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="shared-refresh-fixture-"+UUID.randomUUID();alias="cmux_shared_refresh_fixture_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(target,name,alias,null);seed()
    }
    @After fun cleanup() { cores.forEach { it.close() };store.clear();target.deleteSharedPreferences(name);keys().deleteEntry(alias) }
    @Test fun sixForcedReadersAcrossTwoAccountAndStoreObjectsShareOneActualExchange()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val second=NativeCredentialStore.withKeyProvider(target,name,alias,null)
            val a=owner(server);val b=owner(server,second);val shared=store.refreshCoordinator(client(server).accountEnvironment)
            assertSame(shared,second.refreshCoordinator(client(server).accountEnvironment))
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            block(server,entered,release,MockResponse().setBody("{\"access_token\":\"minted\"}"))
            val readers=List(6) { index->async(Dispatchers.IO) { (if(index%2==0)a else b).resolvedAccessToken(true) } }
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));waitFor { shared.waiterCount==6 };assertEquals(1,server.requestCount)
                release.countDown();assertEquals(List(6){"minted"},readers.awaitAll());waitFor { store.authWork.activeCount==0 }
                assertEquals("minted",store.requireReadable()!!.getString("access_token"));assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
                record("shared-success",JSONObject().put("httpRequests",1).put("readers",6).put("separateObjectsShareOwner",true).put("pairingPreserved",true))
            } finally { release.countDown();readers.forEach { it.cancel() } }
        }
    }
    @Test fun oneSharedFailureKeepsProactiveFallbackWhileForcedCallerRefusesIt()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val a=owner(server);val b=owner(server);val shared=store.refreshCoordinator(client(server).accountEnvironment)
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val old=token()
            block(server,entered,release,MockResponse().setResponseCode(503))
            val normal=async(Dispatchers.IO) { a.resolvedAccessToken() };val forced=async(Dispatchers.IO) { b.resolvedAccessToken(true) }
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));waitFor { shared.waiterCount==2 };release.countDown()
                assertEquals(old,normal.await());assertNull(forced.await());assertEquals(1,server.requestCount)
                assertEquals(old,store.requireReadable()!!.getString("access_token"))
                record("shared-failure",JSONObject().put("httpRequests",1).put("callerSpecificFallback",true).put("credentialsPreserved",true))
            } finally { release.countDown();normal.cancel();forced.cancel() }
        }
    }
    @Test fun oneCancelledWaiterLeavesTheOtherAndLastCancellationDrainsHttp()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val a=owner(server);val b=owner(server);val shared=store.refreshCoordinator(client(server).accountEnvironment)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            block(server,entered,release,MockResponse().setBody("{\"access_token\":\"minted\"}"))
            val one=async(Dispatchers.IO) { a.resolvedAccessToken(true) };val two=async(Dispatchers.IO) { b.resolvedAccessToken(true) }
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));waitFor { shared.waiterCount==2 };one.cancelAndJoin()
                waitFor { shared.waiterCount==1 };assertFalse(two.isCompleted);release.countDown();assertEquals("minted",two.await())
                server.dispatcher=QueueDispatcher();server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                server.takeRequest() // First completed request.
                val last=launch(Dispatchers.IO) { a.resolvedAccessToken(true) }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3,TimeUnit.SECONDS) });last.cancelAndJoin()
                waitFor { shared.waiterCount==0 && store.authWork.activeCount==0 }
                assertEquals("minted",store.requireReadable()!!.getString("access_token"))
                record("waiter-cancellation",JSONObject().put("singleWaiterDidNotCancelPeer",true).put("lastWaiterDrainedTrackedHttp",true))
            } finally { release.countDown();one.cancel();two.cancel() }
        }
    }
    @Test fun actualDefaultThirtySecondForcedDeadlineDrainsHttpAndPreservesCiphertext()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val coordinator=core();val account=owner(server,core=coordinator)
            val before=target.getSharedPreferences(name,0).getString("state",null)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));val started=SystemClock.elapsedRealtime()
            val failure=withTimeout(40_000) { runCatching { account.resolvedAccessToken(true) }.exceptionOrNull() as Exception }
            val elapsed=SystemClock.elapsedRealtime()-started
            assertEquals(NativeSignInError.TIMED_OUT,nativeSignInError(failure));assertTrue(elapsed in 29_000..38_000)
            assertEquals(1,server.requestCount)
            waitFor { store.authWork.activeCount==0 };assertEquals(before,target.getSharedPreferences(name,0).getString("state",null))
            record("default-deadline",JSONObject().put("elapsedMillis",elapsed).put("httpRequests",server.requestCount).put("ciphertextPreserved",true).put("httpDrained",true))
        }
    }
    @Test fun signInQuiesceWaitsForActualEncryptionAndCancelledWriteCannotPublish()=cryptoFence(false)
    @Test fun elapsedDeadlineDuringEncryptionRejectsTheFinalPreferenceCommit()=cryptoFence(true)
    private fun cryptoFence(deadline:Boolean)=runBlocking<Unit> {
        MockWebServer().use { server ->
            val elapsed=AtomicLong();val coordinator=NativeTokenRefresh(now=elapsed::get).also { cores+=it }
            val entered=CountDownLatch(1);val release=CountDownLatch(1);var block=false
            val reader=NativeCredentialStore.withKeyProvider(target,name,alias) {
                if(block && Thread.currentThread().stackTrace.any { it.className==NativeCredentialStore::class.java.name && it.methodName=="save" }) {
                    entered.countDown();check(release.await(10,TimeUnit.SECONDS))
                }
                keys().getKey(alias,null) as SecretKey
            }
            val account=owner(server,reader,coordinator);val before=target.getSharedPreferences(name,0).getString("state",null)
            block=true;server.enqueue(MockResponse().setBody("{\"access_token\":\"obsolete\"}"))
            val pending=async(Dispatchers.IO) { runCatching { account.resolvedAccessToken(true) } }
            var quiesce:Deferred<Unit>?=null
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                if(deadline) elapsed.set(30_001) else {
                    quiesce=async(Dispatchers.IO) { store.authWork.quiesce(5000) };delay(100)
                    assertFalse(quiesce.isCompleted);assertTrue(store.authWork.activeCount>0)
                }
                release.countDown();val result=pending.await();quiesce?.await();waitFor { store.authWork.activeCount==0 }
                if(deadline)assertEquals(NativeSignInError.TIMED_OUT,nativeSignInError(result.exceptionOrNull() as Exception)) else assertTrue(result.isFailure)
                assertEquals(before,target.getSharedPreferences(name,0).getString("state",null))
                record(if(deadline)"encryption-deadline" else "encryption-quiesce",JSONObject().put("ciphertextPreserved",true).put("finalCommitFenced",true).put("actualWorkJoined",!deadline))
            } finally { block=false;release.countDown();pending.cancel();quiesce?.cancel() }
        }
    }
    @Test fun rateLimitCooldownIsSharedAndOnlySafeMetadataSignalsReachTheObserver()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val elapsed=AtomicLong();val signals=mutableListOf<NativeRefreshRateLimitObservation>();var recovered=0
            val previous=NativeRefreshReporting.observer
            NativeRefreshReporting.observer=object:NativeRefreshReporting.Observer {
                override fun limited(value:NativeRefreshRateLimitObservation) { signals+=value }
                override fun recovered() { recovered++ }
            }
            val coordinator=NativeTokenRefresh(now=elapsed::get).also { cores+=it }
            val a=owner(server,core=coordinator);val b=owner(server,core=coordinator)
            try {
                server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","1").setHeader("Content-Type","text/html").setBody("<private>never report</private>"))
                assertEquals(token(),a.resolvedAccessToken());assertNull(b.resolvedAccessToken(true))
                assertEquals(1,server.requestCount);assertEquals(1,signals.size);assertEquals("html",signals.single().bodyKind)
                assertEquals(1.0,signals.single().backoffSeconds,0.0);assertFalse(signals.single().toString().contains("never report"))
                elapsed.set(1000);server.enqueue(MockResponse().setBody("{\"access_token\":\"minted\"}"))
                assertEquals("minted",b.resolvedAccessToken(true));assertEquals(1,recovered);assertEquals(2,server.requestCount)
                record("rate-limit",JSONObject().put("cooldownPreventedSecondHttp",true).put("safeObservationCount",signals.size).put("recoveryCount",recovered))
            } finally { NativeRefreshReporting.observer=previous }
        }
    }
    @Test fun replacementOwnerStartsItsOwnExchangeAndOldResponseCannotChangeIt()=runBlocking<Unit> {
        MockWebServer().use { server ->
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if(request.body.readUtf8().contains("refresh_token=saved-refresh")) {
                        entered.countDown();check(release.await(10,TimeUnit.SECONDS));return MockResponse().setResponseCode(401).setBody("{}")
                    }
                    return MockResponse().setBody("{\"access_token\":\"new-minted\"}")
                }
            }
            val a=owner(server);val b=owner(server);val old=async(Dispatchers.IO) { runCatching{a.resolvedAccessToken(true)} }
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));store.update { it.put("task_session","new-login").put("refresh_token","new-refresh").put("access_token","new-old") }
                assertTrue(withTimeout(2000) { old.await() }.exceptionOrNull() is CancellationException)
                assertEquals("new-minted",b.resolvedAccessToken(true));release.countDown();waitFor { store.authWork.activeCount==0 }
                assertEquals("new-minted",store.requireReadable()!!.getString("access_token"));assertEquals("new-refresh",store.requireReadable()!!.getString("refresh_token"))
                record("owner-replacement",JSONObject().put("newOwnerMinted",true).put("oldWaiterRetired",true).put("oldRejectionCouldNotClear",true))
            } finally { release.countDown();old.cancel() }
        }
    }
}
