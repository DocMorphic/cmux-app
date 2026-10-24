package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.json.JSONArray
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

class NativeApplicationStateClearRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val releases=mutableListOf<CountDownLatch>();private val extraWork=mutableListOf<NativeAuthWork>()
    private val saved=NativeSignInUser("saved-user","Saved",null,"one")
    private val pairing=NativeCredentialStore.PairedMac("fixture-pairing","fixture-mac","Saved Mac")
    private fun keys()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    private fun key()=keys().getKey(alias,null) as SecretKey
    private fun token(seconds:Long)="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(
        JSONObject().put("exp",System.currentTimeMillis()/1000+seconds).toString().toByteArray())+".fixture"
    private fun client(server:MockWebServer)=NativeOAuthClient("fixture-project","fixture-key",server.url("/"))
    private fun account(server:MockWebServer,reader:NativeCredentialStore=store,browser:NativeOAuthBrowser=NativeOAuthBrowser(),work:NativeAuthWork?=null)=
        NativeAccount(reader,client(server),browser,authWorkOverride=work,tokenRefreshLeadMillis=120_000)
    private fun ciphertext()=context.getSharedPreferences(name,0).getString("state",null)
    private fun gate()=CountDownLatch(1).also{releases+=it}
    private fun clearEncryption()=Thread.currentThread().stackTrace.any{it.className==NativeCredentialStore::class.java.name && it.methodName.startsWith("clearAuthPresentation")} &&
        Thread.currentThread().stackTrace.any{it.className==NativeCredentialStore::class.java.name && it.methodName=="save"}
    private fun seed(server:MockWebServer,access:String=token(3600),refresh:String?=null) {
        store.update{it.put("access_token",access).put("refresh_token",refresh ?: "").put("task_session","login")
            .put("fixture_pairing_marker","retain").put("pairings",JSONArray().put(NativePairingRecords.encode(pairing)))
            .put("task_drafts",JSONObject().put("sentinel","retain"));it.remove(NativeSignInCompletion.PENDING)
            NativeAuthUserCache.write(it,client(server).accountEnvironment,saved)
            if(refresh!=null)NativeAccountProfileCache.writeVerifiedUser(it,"login",client(server).accountEnvironment,saved)
            NativeTeamSelectionStore.write(it,client(server).accountEnvironment,"one")}
        store.setAuthTokenHint(client(server).accountEnvironment,true)
    }
    private suspend fun drained(){withTimeout(5000){while((extraWork+store.authWork).any{it.activeCount>0})delay(5)}}
    private fun reason(result:Result<*>,expected:NativeSignInError){assertEquals(result.exceptionOrNull().toString(),expected,
        nativeSignInError(result.exceptionOrNull() as Exception))}
    private fun record(case:String,value:JSONObject){assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
        assertEquals(listOf(pairing),store.pairedMacs());assertFalse(ciphertext()!!.contains("saved-user"))
        File(context.getExternalFilesDir(null),"application-token").apply{mkdirs()}.resolve("$case.json").writeText(value
            .put("fixtureOnly",true).put("encryptedStoreUsed",true).put("pairingPreserved",true).toString(2))}
    @Before fun setup(){check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="application-token-fixture-"+UUID.randomUUID();alias="cmux_application_token_"+UUID.randomUUID();store=NativeCredentialStore.withKeyProvider(context,name,alias)}
    @After fun cleanup(){releases.forEach{it.countDown()};scope.cancel();runBlocking{scope.coroutineContext[Job]!!.join();drained()}
        store.clear();context.deleteSharedPreferences(name);keys().deleteEntry(alias)}

    @Test fun absentStoreRetiresPresentationWithoutCreatingAnEncryptionKey()=runBlocking<Unit> {
        MockWebServer().use{server->val owner=account(server);val generation=owner.sessionRestore.sessionGeneration
            reason(runCatching{owner.accessToken()},NativeSignInError.UNAUTHORIZED)
            assertEquals(generation+1,owner.sessionRestore.sessionGeneration);assertEquals(NativeAuthState(),owner.sessionRestore.state.value)
            assertNull(ciphertext());assertFalse(keys().containsAlias(alias));assertEquals(0,server.requestCount)
            File(context.getExternalFilesDir(null),"application-token").apply{mkdirs()}.resolve("absent-store.json").writeText(JSONObject()
                .put("fixtureOnly",true).put("keyNotCreated",true).put("encryptedDocumentNotCreated",true).put("clearedModel",true).toString(2))}
    }
    @Test fun proactiveAndForcedMissingRefreshClearOnlyPresentationAndKeepStoredAccessAndDrafts()=runBlocking<Unit> {
        val rows=JSONArray()
        for(force in listOf(false,true))MockWebServer().use{server->val access=token(if(force)3600 else -1);seed(server,access)
            val owner=account(server);assertTrue(owner.sessionRestore.state.value.authenticated);val generation=owner.sessionRestore.sessionGeneration
            val creation=keys().getCreationDate(alias)
            reason(runCatching{owner.accessToken(force)},NativeSignInError.UNAUTHORIZED);val root=store.requireReadable()!!
            assertEquals(NativeAuthState(),owner.sessionRestore.state.value);assertEquals(generation+1,owner.sessionRestore.sessionGeneration)
            assertFalse(root.has(NativeAuthUserCache.KEY));assertFalse(root.has(NativeAccountProfileCache.KEY));assertFalse(root.has(NativeTeamSelectionStore.KEY))
            assertEquals(access,root.getString("access_token"));assertEquals("retain",root.getJSONObject("task_drafts").getString("sentinel"))
            assertFalse(store.authTokenHint(client(server).accountEnvironment,true));assertEquals(creation,keys().getCreationDate(alias));assertEquals(0,server.requestCount)
            assertEquals(NativeAuthState(),account(server).sessionRestore.state.value)
            rows.put(JSONObject().put("forced",force).put("generationAdvanced",true).put("accessAndDraftsPreserved",true))}
        record("missing-refresh",JSONObject().put("rows",rows))
    }
    @Test fun definitiveRefreshRejectionClearsTheImmediateModelCacheAndChoice()=runBlocking<Unit> {
        val rows=JSONArray()
        for(status in listOf(400,401))MockWebServer().use{server->seed(server,token(-1),"refresh");val owner=account(server)
            server.enqueue(MockResponse().setResponseCode(status));reason(runCatching{owner.accessToken(true)},NativeSignInError.UNAUTHORIZED)
            val root=store.requireReadable()!!;assertEquals(NativeAuthState(),owner.sessionRestore.state.value)
            assertFalse(root.has(NativeAuthUserCache.KEY));assertFalse(root.has(NativeTeamSelectionStore.KEY));assertTrue(root.optString("refresh_token").isBlank())
            assertEquals(1,server.requestCount);rows.put(JSONObject().put("status",status).put("immediateModelCleared",true))}
        record("definitive-rejection",JSONObject().put("rows",rows))
    }
    @Test fun transientAndUnavailableReadOrEncryptionPreserveTheCachedSession()=runBlocking<Unit> {
        val rows=JSONArray()
        for(mode in listOf("http","read","encrypt"))MockWebServer().use{server->seed(server,token(-1),if(mode=="http")"refresh" else null)
            var fail=false;val reader=NativeCredentialStore.withKeyProvider(context,name,alias){
                if(fail && (mode=="read" || mode=="encrypt" && clearEncryption()))throw KeyStoreException("Fixture unavailable")
                key()}
            val owner=account(server,reader);val before=ciphertext();val model=owner.sessionRestore.state.value;val generation=owner.sessionRestore.sessionGeneration
            if(mode=="http")server.enqueue(MockResponse().setResponseCode(503))else fail=true
            reason(runCatching{owner.accessToken()},NativeSignInError.NETWORK_ERROR);fail=false
            assertEquals(before,ciphertext());assertEquals(model,owner.sessionRestore.state.value);assertEquals(generation,owner.sessionRestore.sessionGeneration)
            assertEquals(if(mode=="http")1 else 0,server.requestCount);rows.put(JSONObject().put("mode",mode).put("ciphertextAndModelPreserved",true))}
        record("transient-unknown",JSONObject().put("rows",rows))
    }
    @Test fun currentAndPublishedWriterLeasesProtectTheModelWhileStaleLeasesDoNot()=runBlocking<Unit> {
        val rows=JSONArray()
        for(mode in listOf("current","published","stale","capture"))MockWebServer().use{server->seed(server,token(-1));val owner=account(server)
            val generation=owner.sessionRestore.sessionGeneration;val before=ciphertext()
            val lease=if(mode!="capture")store.authWork.beginSignIn{mode!="stale"}else null
            if(mode=="published")lease!!.publish()
            val end=if(mode=="capture")store.authWork.beginSignOutCapture()else null
            try {val result=runCatching{owner.accessToken()}
                if(mode=="stale"){reason(result,NativeSignInError.UNAUTHORIZED);assertEquals(generation+1,owner.sessionRestore.sessionGeneration)}
                else {reason(result,NativeSignInError.NETWORK_ERROR);assertEquals(before,ciphertext());assertEquals(generation,owner.sessionRestore.sessionGeneration)}
                rows.put(JSONObject().put("mode",mode).put("cleared",mode=="stale"))
            }finally{lease?.finish();end?.invoke()}}
        record("transition-owners",JSONObject().put("rows",rows))
    }
    @Test fun readOnlyValidationRetainsItsCachedAccountDuringAForcedTokenMiss()=runBlocking<Unit> {
        MockWebServer().use{server->seed(server);val owner=account(server);val before=ciphertext();val generation=owner.sessionRestore.sessionGeneration
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));owner.sessionRestore.start(scope)
            assertNotNull(withContext(Dispatchers.IO){server.takeRequest(3,TimeUnit.SECONDS)})
            assertTrue(owner.sessionRestore.revalidating.value);reason(runCatching{owner.accessToken(true)},NativeSignInError.NETWORK_ERROR)
            assertEquals(before,ciphertext());assertEquals(generation,owner.sessionRestore.sessionGeneration);assertTrue(owner.sessionRestore.state.value.authenticated)
            scope.cancel();scope.coroutineContext[Job]!!.join();drained()
            record("validation-owner",JSONObject().put("revalidationProtectedPresentation",true).put("ciphertextPreserved",true))}
    }
    @Test fun aCanceledWaiterCannotCommitAfterBlockedPresentationEncryptionReturns()=runBlocking<Unit> {
        MockWebServer().use{server->seed(server);val entered=CountDownLatch(1);val release=gate()
            val reader=NativeCredentialStore.withKeyProvider(context,name,alias){if(clearEncryption()){entered.countDown();check(release.await(8,TimeUnit.SECONDS))};key()}
            val owner=account(server,reader);val before=ciphertext();val model=owner.sessionRestore.state.value
            val call=scope.async{owner.accessToken(true)}
            try {assertTrue(withContext(Dispatchers.IO){entered.await(4,TimeUnit.SECONDS)})
                call.cancel();withTimeout(1000){call.join()};assertTrue(store.authWork.activeCount>0);assertEquals(before,ciphertext())
                release.countDown();drained();assertEquals(before,ciphertext());assertEquals(model,owner.sessionRestore.state.value)
                record("canceled-encryption",JSONObject().put("callerReturnedBeforeActualWork",true).put("lateCommitRejected",true))
            }finally{release.countDown();call.cancelAndJoin()}}
    }
    @Test fun aNewSignInAnnouncesOwnershipBeforeWaitingForTheOldClearEncryption()=runBlocking<Unit> {
        MockWebServer().use{server->seed(server);val entered=CountDownLatch(1);val release=gate();val browser=NativeOAuthBrowser()
            val reader=NativeCredentialStore.withKeyProvider(context,name,alias){if(clearEncryption()){entered.countDown();check(release.await(8,TimeUnit.SECONDS))};key()}
            val owner=account(server,reader,browser);val before=ciphertext();val old=scope.async{runCatching{owner.accessToken(true)}}
            try {assertTrue(withContext(Dispatchers.IO){entered.await(4,TimeUnit.SECONDS)})
                server.enqueue(MockResponse().setBody("{\"location\":\"https://provider.example/authorize\"}"))
                server.enqueue(MockResponse().setBody(JSONObject().put("access_token",token(3600)).put("refresh_token","next-refresh").toString()))
                server.enqueue(MockResponse().setBody("{\"id\":\"next-user\"}"))
                val signing=scope.async{owner.signInWithProvider(NativeOAuthProvider.GOOGLE)}
                withTimeout(3000){while(!store.authWork.currentSignInOwnsStore)delay(2)}
                assertEquals(0,server.requestCount);assertNull(browser.state.value);assertEquals(before,ciphertext())
                release.countDown();assertTrue(withTimeout(4000){old.await()}.exceptionOrNull() is CancellationException)
                val launch=withTimeout(4000){browser.state.filterNotNull().first()};assertTrue(browser.claim(launch.id))
                val url=server.takeRequest(3,TimeUnit.SECONDS)!!.requestUrl!!
                assertTrue(browser.receive(launch.id,url.queryParameter("redirect_uri")+"&state="+url.queryParameter("state")+"&code=fixture-code"))
                withTimeout(4000){signing.await()};assertEquals("next-user",owner.sessionRestore.state.value.user?.id)
                assertEquals("next-refresh",store.requireReadable()!!.getString("refresh_token"));assertEquals(3,server.requestCount)
                record("new-writer",JSONObject().put("writerAnnouncedWhileCryptoBlocked",true).put("providerWaitedForActualRetirement",true).put("newLoginPreserved",true))
            }finally{release.countDown();old.cancelAndJoin()}}
    }
    @Test fun signOutCaptureRetiresTheOldClearWithoutAnExtraGenerationBump()=runBlocking<Unit> {
        MockWebServer().use{server->seed(server);val entered=CountDownLatch(1);val release=gate()
            val reader=NativeCredentialStore.withKeyProvider(context,name,alias){if(clearEncryption()){entered.countDown();check(release.await(8,TimeUnit.SECONDS))};key()}
            val owner=account(server,reader);val generation=owner.sessionRestore.sessionGeneration;val old=scope.async{runCatching{owner.accessToken(true)}}
            try {assertTrue(withContext(Dispatchers.IO){entered.await(4,TimeUnit.SECONDS)})
                val logout=scope.async{owner.signOut()};withTimeout(3000){while(!store.authWork.isCapturingSignOutCredentials)delay(2)}
                assertFalse(logout.isCompleted);release.countDown();assertTrue(withTimeout(4000){logout.await()})
                assertTrue(withTimeout(4000){old.await()}.exceptionOrNull() is CancellationException)
                assertEquals(generation+1,owner.sessionRestore.sessionGeneration);assertEquals(NativeAuthState(),owner.sessionRestore.state.value)
                record("signout-capture",JSONObject().put("captureAnnouncedWhileCryptoBlocked",true).put("onlySignOutAdvancedGeneration",true))
            }finally{release.countDown();old.cancelAndJoin()}}
    }
    @Test fun reentrantReplacementDuringEncryptionCannotBeErasedByTheOuterClear()=runBlocking<Unit> {
        val rows=JSONArray()
        for(advanceEpoch in listOf(false,true))MockWebServer().use{server->seed(server);val replaced=AtomicBoolean()
            val reader=NativeCredentialStore.withKeyProvider(context,name,alias){
                if(clearEncryption() && replaced.compareAndSet(false,true)) {
                    if(advanceEpoch)store.authSessionEpoch.incrementAndGet()
                    store.update{it.put("task_session","replacement").put("access_token",token(3600)).put("refresh_token","next-refresh")
                        NativeAccountProfileCache.writeVerifiedUser(it,"replacement",client(server).accountEnvironment,NativeSignInUser("next-user","Next",null,null))}
                };key()}
            val owner=account(server,reader);val generation=owner.sessionRestore.sessionGeneration
            val failure=runCatching{owner.accessToken(true)}.exceptionOrNull()
            assertTrue(replaced.get());assertTrue(failure.toString(),failure is CancellationException)
            if(advanceEpoch){owner.reconcileStoredSession();assertEquals("next-user",owner.sessionRestore.state.value.user?.id)}
            else assertEquals(generation,owner.sessionRestore.sessionGeneration)
            assertEquals("next-user",account(server).sessionRestore.state.value.user?.id)
            assertEquals("next-refresh",store.requireReadable()!!.getString("refresh_token"))
            rows.put(JSONObject().put("epochAdvanced",advanceEpoch).put("revisionFenceRetainedNewCache",true))}
        record("reentrant-replacement",JSONObject().put("rows",rows))
    }
    @Test fun aLateUnauthorizedFailureLosesToTheTokenPhaseDeadlineBeforeAnyClear()=runBlocking<Unit> {
        MockWebServer().use{server->seed(server,token(-1));val elapsed=AtomicLong();val work=NativeAuthWork(nowMillis=elapsed::get).also{extraWork+=it}
            val entered=CountDownLatch(1);val release=gate();val armed=AtomicBoolean()
            val reader=NativeCredentialStore.withKeyProvider(context,name,alias){if(armed.compareAndSet(true,false)){entered.countDown();check(release.await(8,TimeUnit.SECONDS))};key()}
            val owner=account(server,reader,work=work);val before=ciphertext();val generation=owner.sessionRestore.sessionGeneration;armed.set(true)
            val call=scope.async{runCatching{owner.accessToken()}}
            try {assertTrue(withContext(Dispatchers.IO){entered.await(4,TimeUnit.SECONDS)});elapsed.set(60_001);release.countDown()
                reason(withTimeout(4000){call.await()},NativeSignInError.TIMED_OUT)
                assertEquals(before,ciphertext());assertEquals(generation,owner.sessionRestore.sessionGeneration)
                record("late-unauthorized",JSONObject().put("injectedElapsedMillis",60001).put("lateFailureBecameTimeout",true).put("clearNotStarted",true))
            }finally{release.countDown();call.cancelAndJoin()}}
    }
    @Test fun aPendingEmailChallengeSurvivesPresentationClearAndCanCompleteSignIn()=runBlocking<Unit> {
        MockWebServer().use{server->seed(server);val owner=account(server)
            server.enqueue(MockResponse().setBody("{\"nonce\":\"fixture-challenge\"}"));owner.sendCode("fixture@example.test")
            reason(runCatching{owner.accessToken(true)},NativeSignInError.UNAUTHORIZED)
            assertFalse(store.requireReadable()!!.has(NativeAuthUserCache.KEY))
            server.enqueue(MockResponse().setBody(JSONObject().put("access_token",token(3600)).put("refresh_token","next-refresh").toString()))
            server.enqueue(MockResponse().setBody("{\"id\":\"next-user\"}"));owner.signIn("abc123")
            server.takeRequest();val exchange=server.takeRequest();assertTrue(exchange.body.readUtf8().contains("abc123fixture-challenge"))
            assertEquals("next-user",owner.sessionRestore.state.value.user?.id);assertEquals(3,server.requestCount)
            record("challenge-preserved",JSONObject().put("pendingNonceSurvivedClear",true).put("subsequentSignInSucceeded",true))}
    }
    @Test fun foreignEnvironmentPresentationIsPreservedAsAnUnknownVerdict()=runBlocking<Unit> {
        MockWebServer().use{server->seed(server,token(-1));store.update{it.getJSONObject(NativeAuthUserCache.KEY).put("environment","foreign")}
            val owner=account(server);val before=ciphertext();val generation=owner.sessionRestore.sessionGeneration
            reason(runCatching{owner.accessToken()},NativeSignInError.NETWORK_ERROR)
            assertEquals(before,ciphertext());assertEquals(generation,owner.sessionRestore.sessionGeneration);assertEquals(0,server.requestCount)
            record("foreign-environment",JSONObject().put("foreignCachePreserved",true).put("noStateClear",true))}
    }
}
