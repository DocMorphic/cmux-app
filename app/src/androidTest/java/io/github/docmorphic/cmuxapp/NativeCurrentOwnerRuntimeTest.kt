package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey

/** Disposable encrypted store and local HTTP only; never deletes a real account. */
class NativeCurrentOwnerRuntimeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    @Volatile private var unavailable=false
    @Volatile private var failAfterCipher:String?=null
    private val keyReads=AtomicInteger()
    private val user=NativeSignInUser("account-a","Account A",null,null)
    private fun client(server:MockWebServer)=NativeOAuthClient("fixture-project","fixture-key",server.url("/"))
    private fun token(expired:Boolean=false)="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(
        JSONObject().put("exp",System.currentTimeMillis()/1000+(if(expired)-3600 else 3600)).toString().toByteArray())+".fixture"
    private fun seed(server:MockWebServer,expired:Boolean=false):NativeAccount {
        store.update {it.put("task_session","login").put("access_token",token(expired)).put("refresh_token","refresh").put("pairing_marker","retain")
            NativeAuthUserCache.write(it,client(server).accountEnvironment,user)}
        store=NativeCredentialStore.withKeyProvider(context,name,alias) {
            failAfterCipher?.let{if(cipher()!=it)unavailable=true}
            keyReads.incrementAndGet();if(unavailable)throw KeyStoreException("Fixture unavailable")
            KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey(alias,null) as SecretKey
        }
        return NativeAccount(store,client(server),NativeOAuthBrowser())
    }
    private fun controller(server:MockWebServer,account:NativeAccount)=nativeAccountDeletionController(scope,account,store,server.url("/"))
    private suspend fun completed(c:NativeAccountDeletionController)=withTimeout(7000) {
        while(c.state.value?.result in listOf(null,NativeAccountDeletionResult.PROCESSING))delay(10)
        checkNotNull(c.state.value)
    }
    private fun cipher()=context.getSharedPreferences(name,0).getString("state",null)
    private fun record(name:String,values:JSONObject) {
        assertEquals("retain",store.requireReadable()!!.getString("pairing_marker"))
        File(context.getExternalFilesDir(null),"current-owner").apply{mkdirs()}.resolve("$name.json").writeText(
            values.put("fixtureOnly",true).put("encryptedStoreUsed",true).put("pairingPreserved",true).toString(2))
    }
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="current-owner-fixture-"+UUID.randomUUID();alias="cmux_current_owner_fixture_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(context,name,alias)
    }
    @After fun cleanup() {
        failAfterCipher=null;unavailable=false;scope.cancel();store.clear();context.deleteSharedPreferences(name)
        KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)
    }
    @Test fun originalCurrentSessionAndTransitionDeclarationsRunOnArt() {
        val asset=InstrumentationRegistry.getInstrumentation().context.assets.open("native-current-owner-reference.json").bufferedReader().use{it.readText()}
        MockWebServer().use {server->seed(server);record("original-reference",JSONObject().put("cases",checkNativeCurrentOwnerReference(asset)))}
    }
    @Test fun publishedOwnerChecksDoNoKeyReadsAndStorageFailureHasAVisibleUnsentOutcome()=runBlocking<Unit> {
        MockWebServer().use {server->val account=seed(server);val snapshot=account.authenticatedSessionSnapshot()
            val c=controller(server,account);val original=c.confirmationOwner("login");assertNotNull(original)
            val before=cipher();unavailable=true;keyReads.set(0)
            assertTrue(account.isAuthenticatedSessionCurrent(snapshot.owner));assertEquals(snapshot.owner,account.deletionOwner("login"))
            assertTrue(account.isDeletionOwnerCurrent(snapshot.owner));assertEquals(original,c.confirmationOwner("login"));assertEquals(0,keyReads.get())
            assertFalse(c.begin("login",original));assertEquals(NativeAccountDeletionResult.STORAGE,c.state.value!!.result)
            c.reconcile();assertEquals(NativeAccountDeletionResult.STORAGE,c.state.value!!.result)
            assertEquals(before,cipher());assertEquals(0,server.requestCount);assertTrue(account.sessionRestore.state.value.authenticated)
            unavailable=false;assertTrue(c.acknowledge(c.state.value!!));assertNull(c.state.value)
            record("unsent-storage",JSONObject().put("ownerKeyReads",0).put("visibleStorageOutcome",true).put("backendRequests",0)
                .put("ciphertextPreserved",true).put("accountPreserved",true))
        }
    }
    @Test fun storageUnavailableAtCaptureStartRetainsGenericFailureWithoutSendingDelete()=runBlocking<Unit> {
        MockWebServer().use {server->val account=seed(server);val c=controller(server,account)
            // The key provider fails on the first read after the durable receipt commit,
            // rather than relying on a thread schedule or a particular number of key calls.
            failAfterCipher=cipher()
            try {
                assertTrue(c.begin("login"));assertEquals(NativeAccountDeletionResult.REJECTED,completed(c).result)
                assertTrue(unavailable);assertEquals(0,server.requestCount);c.reconcile()
                assertEquals(NativeAccountDeletionResult.REJECTED,c.state.value!!.result);assertTrue(account.sessionRestore.state.value.authenticated)
                failAfterCipher=null;unavailable=false;c.reconcile();assertEquals(NativeAccountDeletionResult.REJECTED,c.state.value!!.result)
                assertEquals(NativeAccountDeletionResult.UNKNOWN,controller(server,account).state.value!!.result)
                assertTrue(c.acknowledge(c.state.value!!))
                record("capture-start-storage",JSONObject().put("backendRequests",0).put("retryableCaptureUsesGenericFailure",true)
                    .put("durableReceiptPrecedesFailure",true).put("accountPreserved",true))
            }finally{failAfterCipher=null;unavailable=false}
        }
    }
    @Test fun availabilityCapturedBeforeRefreshKeepsUnauthorizedWhenStorageLaterBecomesUnreadable()=runBlocking<Unit> {
        MockWebServer().use {server->val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                assertEquals("/api/v1/auth/oauth/token",request.path);entered.countDown();check(release.await(10,TimeUnit.SECONDS))
                return MockResponse().setBody(JSONObject().put("access_token",token()).toString())}}
            val account=seed(server,true);val c=controller(server,account);assertTrue(c.begin("login"))
            try {
                assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)});val before=cipher();unavailable=true
                c.reconcile();assertEquals(NativeAccountDeletionResult.PROCESSING,c.state.value!!.result);release.countDown()
                assertEquals(NativeAccountDeletionResult.UNAUTHORIZED,completed(c).result);c.reconcile()
                assertEquals(NativeAccountDeletionResult.UNAUTHORIZED,c.state.value!!.result);assertFalse(c.begin("login"));assertEquals(before,cipher())
                assertEquals(1,server.requestCount);assertTrue(account.sessionRestore.state.value.authenticated)
                unavailable=false;c.reconcile();assertEquals(NativeAccountDeletionResult.UNAUTHORIZED,c.state.value!!.result)
                assertEquals(NativeAccountDeletionResult.UNKNOWN,controller(server,account).state.value!!.result)
                assertTrue(c.acknowledge(c.state.value!!));assertNull(c.state.value)
                record("capture-storage",JSONObject().put("sdkRequests",1).put("deleteRequests",0).put("capturedAvailabilityPreservesUnauthorized",true)
                    .put("sameProcessResultPreservedAfterRecovery",true).put("newControllerRestoresUncertainty",true).put("ciphertextPreserved",true))
            }finally{unavailable=false;release.countDown()}
        }
    }
    @Test fun receivedDeleteSuccessSurvivesStorageLockAndLocalSignOutRetriesWithoutASecondDelete()=runBlocking<Unit> {
        MockWebServer().use {server->val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                assertEquals("DELETE",request.method);entered.countDown();check(release.await(10,TimeUnit.SECONDS));return MockResponse().setResponseCode(204)}}
            val account=seed(server);val c=controller(server,account);assertTrue(c.begin("login"))
            try {
                assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)});val before=cipher();unavailable=true
                c.reconcile();assertEquals(NativeAccountDeletionResult.PROCESSING,c.state.value!!.result);release.countDown()
                assertEquals(NativeAccountDeletionResult.COMPLETED,completed(c).result);assertEquals(before,cipher())
                compose.setContent {val receipt by c.state.collectAsState();CmuxTheme {NativeAccountDeletionAlerts(receipt,
                    {r->c.signOut(r){owner->runCatching{account.signOutDeletion(checkNotNull(owner))}.getOrDefault(false)}.also{c.reconcile()}},c::acknowledge)}}
                compose.onNodeWithText("Retry sign out").assertIsDisplayed();assertTrue(account.sessionRestore.state.value.authenticated)
                assertEquals(1,server.requestCount);assertFalse(c.begin("login"))
                unavailable=false;compose.onNodeWithText("Retry sign out").performClick();compose.waitForIdle()
                compose.onNodeWithText("Retry sign out").assertDoesNotExist();assertNull(account.sessionRestore.identity());assertNull(c.state.value)
                assertEquals(1,server.requestCount)
                record("received-success",JSONObject().put("deleteRequests",1).put("receivedSuccessRetained",true).put("localSignOutRetryVisible",true)
                    .put("localSignOutSucceededAfterRecovery",true).put("noSecondDelete",true).put("ciphertextUnchangedWhileLocked",true))
            }finally{unavailable=false;release.countDown()}
        }
    }
    @Test fun unreadableStorageCannotPublishOldDeleteSuccessOverASameUserNewGeneration()=runBlocking<Unit> {
        MockWebServer().use {server->val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                entered.countDown();check(release.await(10,TimeUnit.SECONDS));return MockResponse().setResponseCode(204)}}
            val account=seed(server);val old=checkNotNull(account.deletionOwner("login"));val c=controller(server,account);assertTrue(c.begin("login"))
            try {
                assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)});unavailable=true
                account.sessionRestore.publishedSignIn(user);val replacement=account.sessionRestore.identity();c.reconcile();assertNull(c.state.value)
                release.countDown();withTimeout(5000){while(store.authWork.activeCount>0)delay(10)};c.reconcile();assertNull(c.state.value)
                assertFalse(account.isDeletionOwnerCurrent(old));assertFalse(account.isAuthenticatedSessionCurrent(old));assertEquals(replacement,account.sessionRestore.identity())
                unavailable=false;c.reconcile();assertNull(c.state.value);assertFalse(account.signOutDeletion(old));assertEquals(1,server.requestCount)
                record("locked-replacement",JSONObject().put("deleteRequests",1).put("oldOutcomeDiscarded",true).put("sameUserNewGenerationPreserved",true))
            }finally{unavailable=false;release.countDown()}
        }
    }
}
