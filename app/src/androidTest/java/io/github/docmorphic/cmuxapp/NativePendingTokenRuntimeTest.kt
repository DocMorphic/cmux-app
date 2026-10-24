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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativePendingTokenRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val cores=mutableListOf<NativeTokenRefresh>()
    @Before fun setup(){check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="pending-token-fixture-"+UUID.randomUUID();alias="cmux_pending_token_"+UUID.randomUUID();store=NativeCredentialStore.withKeyProvider(context,name,alias)}
    @After fun cleanup(){scope.cancel();runBlocking{scope.coroutineContext[Job]!!.join();withTimeout(5000){while(store.authWork.activeCount>0)delay(5)}}
        cores.forEach{it.close()};store.clear();context.deleteSharedPreferences(name);KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}
    private fun token(seconds:Long,label:String="old")="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject()
        .put("exp",System.currentTimeMillis()/1000+seconds).put("label",label).toString().toByteArray())+".fixture"
    private fun seed(access:String){store.update{it.put("task_session","pending").put(NativeSignInCompletion.PENDING,"pending")
        .put("access_token",access).put("refresh_token","fixture-refresh").put("fixture_pairing_marker","preserve")
        it.remove(NativeAuthUserCache.KEY);it.remove(NativeAccountProfileCache.KEY)}}
    private fun client(server:MockWebServer)=NativeOAuthClient("p","k",server.url("/"))
    private fun account(server:MockWebServer,reader:NativeCredentialStore=store,core:NativeTokenRefresh?=null,exchange:((String)->String)?=null)=
        NativeAccount(reader,client(server),NativeOAuthBrowser(),refreshOverride=exchange,tokenRefreshLeadMillis=120_000,refreshCoordinatorOverride=core)
    private fun user()=MockResponse().setBody("{\"id\":\"user\"}")
    private fun refreshed(access:String)=MockResponse().setBody(JSONObject().put("access_token",access).toString())
    private fun record(case:String,value:JSONObject){val cold=NativeCredentialStore.withKeyProvider(context,name,alias).requireReadable()!!
        assertEquals("preserve",cold.getString("fixture_pairing_marker"));assertFalse(context.getSharedPreferences(name,0).getString("state",null)!!.contains("fixture-refresh"))
        File(context.getExternalFilesDir(null),"pending-token").apply{mkdirs()}.resolve("$case.json")
            .writeText(value.put("fixtureOnly",true).put("encryptedStoreUsed",true).put("pairingMarkerPreserved",true).toString(2))}

    @Test fun expiredOrphanRefreshesBeforeUserHttpAndMetadataWritesDoNotRetireItsPendingOwner()=runBlocking<Unit> {
        MockWebServer().use{server->val old=token(-1);val next=token(3600,"new");seed(old)
            val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path=="/api/v1/auth/oauth/token"){entered.complete(Unit);check(release.await(5,TimeUnit.SECONDS));return refreshed(next)}
                return user()
            }}
            val owner=account(server);val restore=async(Dispatchers.IO){owner.restorePendingSignIn()}
            try {withTimeout(4000){entered.await()};assertNull(owner.accessToken());assertFalse(owner.isSignedIn());assertNull(store.taskSession())
                assertNull(store.refreshView().owner);assertTrue(store.pendingRefreshView("pending",store.authSessionEpoch.get()).owner!!.pending)
                store.update{it.put("fixture_metadata","retained")};release.countDown();withTimeout(4000){restore.await()}
                assertEquals(next,store.requireReadable()!!.getString("access_token"));assertEquals("retained",store.requireReadable()!!.getString("fixture_metadata"))
                val first=server.takeRequest();val second=server.takeRequest();assertEquals("/api/v1/auth/oauth/token",first.path)
                assertEquals("/api/v1/users/me",second.path);assertEquals(next,second.getHeader("x-hexclave-access-token"));assertTrue(owner.isSignedIn())
                record("orphan-preflight",JSONObject().put("refreshBeforeUserHttp",true).put("metadataDidNotRetireOwner",true).put("pendingWorkerAccessDenied",true))
            }finally{release.countDown();restore.cancelAndJoin()}}
    }
    @Test fun interactiveExpiredExchangeRefreshesBeforeItsFirstUserRequest()=runBlocking<Unit> {
        MockWebServer().use{server->val next=token(3600,"new");store.update{it.put("fixture_pairing_marker","preserve")}
            server.enqueue(MockResponse().setBody("{\"nonce\":\"challenge\"}"))
            server.enqueue(MockResponse().setBody(JSONObject().put("access_token",token(-1)).put("refresh_token","fixture-refresh").toString()))
            server.enqueue(refreshed(next));server.enqueue(user());val owner=account(server)
            owner.sendCode("fixture@example.test");owner.signIn("abc123")
            val requests=List(4){server.takeRequest()};assertEquals("/api/v1/auth/oauth/token",requests[2].path)
            assertEquals("/api/v1/users/me",requests[3].path);assertEquals(next,requests[3].getHeader("x-hexclave-access-token"))
            assertTrue(owner.isSignedIn());record("interactive-preflight",JSONObject().put("expiredAccessNeverSentToUser",true).put("httpRequests",4))}
    }
    @Test fun proactiveFailureMayUseUnexpiredAccessButForcedRejectionCannot()=runBlocking<Unit> {
        for(forced in listOf(false,true))MockWebServer().use{server->val old=token(if(forced)3600 else 30);seed(old)
            if(forced)server.enqueue(MockResponse().setResponseCode(401).setHeader("x-hexclave-known-error","invalid_access_token"))
            server.enqueue(MockResponse().setResponseCode(503));if(!forced)server.enqueue(user())
            val owner=account(server);val result=runCatching{owner.restorePendingSignIn()}
            assertEquals(!forced,result.isSuccess);assertEquals(!forced,owner.isSignedIn());assertEquals(2,server.requestCount)
            assertEquals(old,store.requireReadable()!!.getString("access_token"))
            assertEquals(forced,NativeSignInCompletion.isPending(store.requireReadable()))
            record(if(forced)"forced-no-fallback" else "proactive-fallback",JSONObject().put("forced",forced).put("published",!forced).put("httpRequests",2))}
    }
    @Test fun refreshCooldownSurvivesReconstructedPendingAccountObjects()=runBlocking<Unit> {
        MockWebServer().use{server->seed(token(-1));server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After","60"))
            repeat(2){val reader=NativeCredentialStore.withKeyProvider(context,name,alias)
                assertTrue(runCatching{account(server,reader).restorePendingSignIn()}.isFailure)}
            assertEquals(1,server.requestCount);assertTrue(NativeSignInCompletion.isPending(store.requireReadable()));assertNull(store.taskSession())
            record("shared-cooldown",JSONObject().put("accountObjects",2).put("refreshRequests",1).put("pendingPreserved",true))}
    }
    @Test fun timedOutProactiveRefreshUsesValidFallbackAndItsLateReplyCannotWriteAfterPublication()=runBlocking<Unit> {
        MockWebServer().use{server->val old=token(30);seed(old);server.enqueue(user());val release=CountDownLatch(1)
            val core=NativeTokenRefresh(timeoutMillis=150).also{cores+=it};val entered=CompletableDeferred<Unit>()
            val owner=account(server,core=core,exchange={entered.complete(Unit);check(release.await(5,TimeUnit.SECONDS));"late-access"})
            val operation=async(Dispatchers.IO){owner.restorePendingSignIn()}
            try {withTimeout(4000){entered.await()};withTimeout(4000){operation.await()};assertTrue(owner.isSignedIn())
                assertEquals(old,store.requireReadable()!!.getString("access_token"));assertTrue(store.authWork.activeCount>0)
                release.countDown();store.authWork.quiesce(5000);assertEquals(old,store.requireReadable()!!.getString("access_token"))
                record("timeout-fallback",JSONObject().put("publishedWithValidFallback",true).put("lateAccessDropped",true).put("timeoutMillis",150))
            }finally{release.countDown();operation.cancelAndJoin()}}
    }
    @Test fun definitivePendingRefreshRejectionClearsOnlyItsCredentialsAndNeverFetchesAUser()=runBlocking<Unit> {
        MockWebServer().use{server->seed(token(-1));server.enqueue(MockResponse().setResponseCode(401));val owner=account(server)
            assertTrue(runCatching{owner.restorePendingSignIn()}.isFailure);val root=store.requireReadable()!!
            assertFalse(NativeSignInCompletion.isPending(root));assertTrue(root.optString("refresh_token").isBlank());assertNull(store.taskSession())
            assertEquals(1,server.requestCount);assertEquals("/api/v1/auth/oauth/token",server.takeRequest().path)
            record("definitive-rejection",JSONObject().put("matchingPendingCleared",true).put("userRequests",0))}
    }
    @Test fun independentPendingBootstrapOwnersShareOneExchangeAndCannotPublishEachOthersReceipt()=runBlocking<Unit> {
        MockWebServer().use{server->val next=token(3600,"new");seed(token(-1))
            val refreshEntered=CompletableDeferred<Unit>();val release=CountDownLatch(1);val bothUsers=CountDownLatch(2)
            val refreshes=AtomicInteger()
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path=="/api/v1/auth/oauth/token"){refreshes.incrementAndGet();refreshEntered.complete(Unit);check(release.await(5,TimeUnit.SECONDS));return refreshed(next)}
                assertEquals(next,request.getHeader("x-hexclave-access-token"));bothUsers.countDown();check(bothUsers.await(5,TimeUnit.SECONDS));return user()
            }}
            val a=account(server);val reader=NativeCredentialStore.withKeyProvider(context,name,alias);val b=account(server,reader)
            val core=store.refreshCoordinator(client(server).accountEnvironment)
            val first=async(Dispatchers.IO){runCatching{a.sessionRestore.restoreIdentityIfNeeded()}}
            val second=async(Dispatchers.IO){runCatching{b.sessionRestore.restoreIdentityIfNeeded()}}
            try {withTimeout(4000){refreshEntered.await();while(core.waiterCount<2)delay(5)}
                assertNull(a.accessToken());assertNull(b.accessToken());release.countDown()
                val results=withTimeout(5000){listOf(first.await(),second.await())}
                assertTrue(results.any{it.getOrNull()?.id=="user"});assertEquals(1,refreshes.get());assertEquals(0L,bothUsers.count)
                val root=store.requireReadable()!!;assertFalse(NativeSignInCompletion.isPending(root));assertEquals(next,root.getString("access_token"))
                record("pending-single-flight",JSONObject().put("owners",2).put("refreshRequests",1).put("userRequests",2).put("publishedReceiptProtected",true))
            }finally{release.countDown();first.cancelAndJoin();second.cancelAndJoin()}}
    }

    @Test fun reconstructedBackgroundOwnerCannotValidateAnInteractivePendingReceipt()=runBlocking<Unit> {
        MockWebServer().use{server->val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
            store.update{it.put("fixture_pairing_marker","preserve")}
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=when(request.path){
                "/api/v1/auth/otp/send-sign-in-code"->MockResponse().setBody("{\"nonce\":\"challenge\"}")
                "/api/v1/auth/otp/sign-in"->MockResponse().setBody(JSONObject().put("access_token",token(3600))
                    .put("refresh_token","fixture-refresh").toString())
                "/api/v1/users/me"->{entered.complete(Unit);check(release.await(5,TimeUnit.SECONDS));user()}
                else->MockResponse().setResponseCode(599)
            }}
            val interactive=account(server);interactive.sendCode("fixture@example.test")
            val signing=async(Dispatchers.IO){interactive.signIn("abc123")}
            try {withTimeout(4000){entered.await()}
                val background=account(server,NativeCredentialStore.withKeyProvider(context,name,alias))
                assertNull(withTimeout(2000){background.sessionRestore.restoreIdentityIfNeeded()})
                assertNull(background.accessToken());assertEquals(3,server.requestCount)
                assertTrue(NativeSignInCompletion.isPending(store.requireReadable()))
                release.countDown();withTimeout(4000){signing.await()};assertTrue(interactive.isSignedIn())
                record("interactive-owner",JSONObject().put("backgroundUserRequests",0).put("interactivePublished",true))
            }finally{release.countDown();signing.cancelAndJoin()}}
    }

}
