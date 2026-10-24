package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
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
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local HTTP, separate encrypted fixtures and real account/controller/Compose wiring. Never a live DELETE. */
class NativeDeletionIdentityRuntimeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val a=NativeSignInUser("account-a","Account A",null,null)
    private val b=NativeSignInUser("account-b","Account B",null,null)
    private fun token(expired:Boolean=false)="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(
        JSONObject().put("exp",System.currentTimeMillis()/1000+(if(expired)-3600 else 3600)).toString().toByteArray())+".fixture"
    private fun client(server:MockWebServer)=NativeOAuthClient("fixture-project","fixture-key",server.url("/"))
    private fun seed(server:MockWebServer,expired:Boolean=false) {store.update {root->
        root.put("task_session","same-login").put("access_token",token(expired)).put("refresh_token","saved-refresh").put("fixture_pairing_marker","retain")
        NativeAuthUserCache.write(root,client(server).accountEnvironment,a)
    }}
    private fun account(server:MockWebServer)=NativeAccount(store,client(server),NativeOAuthBrowser())
    private fun controller(server:MockWebServer,account:NativeAccount)=nativeAccountDeletionController(scope,account,store,server.url("/"))
    private suspend fun result(c:NativeAccountDeletionController)=withTimeout(7000){
        while(c.state.value?.result in listOf(null,NativeAccountDeletionResult.PROCESSING))delay(10)
        checkNotNull(c.state.value)
    }
    private fun retained()=assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
    private fun record(case:String,value:JSONObject) {File(context.getExternalFilesDir(null),"deletion-identity").apply {mkdirs()}
        .resolve("$case.json").writeText(value.put("fixtureOnly",true).toString(2))}
    private fun capture(name:String) {compose.waitForIdle();val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {File(context.getExternalFilesDir(null),"deletion-identity").apply {mkdirs()}.resolve("$name.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
        finally {image.recycle()}}
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="deletion-identity-fixture-"+UUID.randomUUID();alias="cmux_deletion_identity_fixture_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(context,name,alias)
    }
    @After fun cleanup() {scope.cancel();store.clear();context.deleteSharedPreferences(name)
        KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.deleteEntry(alias)}

    @Test fun confirmedDeletionResolvesTheLivePairBeforeSendingExactlyOneDelete()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server,true);val fresh=token();server.enqueue(MockResponse().setBody(JSONObject().put("access_token",fresh).toString()))
            server.enqueue(MockResponse().setResponseCode(204));val account=account(server);val c=controller(server,account)
            assertTrue(c.begin("same-login"));assertEquals(NativeAccountDeletionResult.COMPLETED,result(c).result)
            val refresh=server.takeRequest(3,TimeUnit.SECONDS)!!;val deletion=server.takeRequest(3,TimeUnit.SECONDS)!!
            assertEquals("/api/v1/auth/oauth/token",refresh.path);assertEquals("DELETE",deletion.method);assertEquals("/api/account",deletion.path)
            assertEquals("Bearer $fresh",deletion.getHeader("Authorization"));assertEquals("saved-refresh",deletion.getHeader("X-Stack-Refresh-Token"))
            assertEquals(2,server.requestCount);retained();record("resolved-pair",JSONObject().put("refreshRequests",1).put("deleteRequests",1).put("coherentHeaders",true).put("pairingPreserved",true))}
    }
    @Test fun orphanedPendingCredentialsCannotPresentOrDispatchDeletionForACachedUser() {
        MockWebServer().use {server->seed(server);store.update {it.put(NativeSignInCompletion.PENDING,"same-login")}
            val account=account(server);val c=controller(server,account)
            assertNull(c.confirmationOwner("same-login"));assertFalse(c.begin("same-login"))
            assertEquals(0,server.requestCount);assertNull(NativeAccountDeletionRecord.read(store.requireReadable()));retained()
            record("orphan-pending",JSONObject().put("unverifiedTokensCannotAuthorizeConfirmation",true).put("deleteRequests",0).put("pairingPreserved",true))}
    }
    @Test fun replacementDuringRefreshCannotSendDeleteForTheNewPublishedUser()=runBlocking<Unit> {
        MockWebServer().use {server->val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                entered.countDown();check(release.await(10,TimeUnit.SECONDS));return MockResponse().setBody(JSONObject().put("access_token",token()).toString())}}
            seed(server,true);val account=account(server);val c=controller(server,account);assertTrue(c.begin("same-login"))
            try {assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                account.sessionRestore.publishedSignIn(b);c.reconcile();assertNull(c.state.value);release.countDown()
                withTimeout(5000){while(store.authWork.activeCount>0)delay(10)}
                assertEquals(1,server.requestCount);assertEquals(b.id,account.sessionRestore.identity()?.userId);retained()
                record("refresh-replacement",JSONObject().put("deleteRequests",0).put("replacementStillAuthenticated",true).put("oldResultNotPublished",true).put("pairingPreserved",true))
            } finally {release.countDown()}}
    }
    @Test fun definitiveRefreshRejectionRetainsItsBoundOutcomeUntilOnlyThatModelIsSignedOut()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server,true);server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            val account=account(server);val c=controller(server,account);assertTrue(c.begin("same-login"));val receipt=result(c)
            assertEquals(NativeAccountDeletionResult.UNAUTHORIZED,receipt.result);assertNotNull(NativeAccountDeletionRecord.read(store.requireReadable()))
            assertTrue(c.signOut(receipt){owner->account.signOutDeletion(checkNotNull(owner))});c.reconcile()
            assertNull(account.sessionRestore.identity());assertNull(c.state.value);assertNull(NativeAccountDeletionRecord.read(store.requireReadable()))
            assertEquals(1,server.requestCount);retained();record("definitive-rejection",JSONObject().put("deleteRequests",0).put("boundUnauthorizedOutcomePreserved",true)
                .put("oldModelRetired",true).put("pairingPreserved",true))}
    }
    @Test fun lateDeleteResponseAndDirectOldSignOutCannotRetireASameUserNewGeneration()=runBlocking<Unit> {
        MockWebServer().use {server->val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                entered.countDown();check(release.await(10,TimeUnit.SECONDS));return MockResponse().setResponseCode(204)}}
            seed(server);val account=account(server);val old=checkNotNull(account.deletionOwner("same-login"));val c=controller(server,account)
            assertTrue(c.begin("same-login"))
            try {assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                account.sessionRestore.publishedSignIn(a);val fresh=account.sessionRestore.identity();release.countDown()
                withTimeout(5000){while(c.state.value!=null)delay(10)}
                assertFalse(account.signOutDeletion(old));assertEquals(fresh,account.sessionRestore.identity());assertTrue(account.sessionRestore.state.value.authenticated)
                assertEquals(1,server.requestCount);retained();record("late-delete",JSONObject().put("deleteRequests",1).put("lateResultRejected",true)
                    .put("atomicOldSignOutRejected",true).put("sameUserNewGenerationPreserved",true).put("pairingPreserved",true))
            } finally {release.countDown()}}
    }
    @Test fun restoredEncryptedSuccessWaitsForVerifiedUserAndNeverResendsDelete()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server)
            store.update {NativeAccountDeletionRecord.write(it,NativeAccountDeletionReceipt(UUID.randomUUID().toString(),"same-login",NativeAccountDeletionResult.COMPLETED,
                NativeDeletionBinding(a.id,77,3,UUID.randomUUID().toString())))}
            val account=account(server);val c=controller(server,account);assertNull(c.state.value);assertFalse(c.begin("same-login"));assertEquals(0,server.requestCount)
            server.enqueue(MockResponse().setBody("{\"id\":\"account-a\"}"));account.sessionRestore.start(scope);withTimeout(5000){account.sessionRestore.awaitBootstrapped()};c.reconcile()
            val receipt=checkNotNull(c.state.value);assertEquals(NativeAccountDeletionResult.COMPLETED,receipt.result)
            assertEquals(1,server.requestCount);assertEquals("GET",server.takeRequest(3,TimeUnit.SECONDS)!!.method)
            assertTrue(c.signOut(receipt){account.signOutDeletion(checkNotNull(it))});assertNull(account.sessionRestore.identity());retained()
            record("restored-success",JSONObject().put("waitedForUserVerification",true).put("userRequests",1).put("deleteRequests",0).put("matchingModelSignedOut",true).put("pairingPreserved",true))}
    }
    @Test fun staleDialogCallbackCannotDeleteTheReplacementBeforeRecomposition() {
        MockWebServer().use {server->seed(server);val account=account(server);val c=controller(server,account);val old=account.sessionRestore.identity()
            compose.setContent {CmuxTheme {NativeAccountDeletionButton("same-login",null,c::begin,old,c::confirmationOwner,true)}}
            compose.onNodeWithText("Delete Account").performClick();compose.onNodeWithText("Delete Account?").assertIsDisplayed();capture("old-confirmation")
            compose.runOnIdle {account.sessionRestore.publishedSignIn(b)}
            compose.onNode(hasText("Delete Account") and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithText("Delete Account?").assertDoesNotExist();assertEquals(0,server.requestCount);assertNull(c.state.value)
            assertEquals(b.id,account.sessionRestore.identity()?.userId);retained()
            record("stale-dialog",JSONObject().put("deleteRequests",0).put("oldCallbackRejectedBeforeRecomposition",true).put("replacementPreserved",true))}
    }
    @Test fun actualIdentityStreamDismissesSameUserOldConfirmationWithoutSending() {
        MockWebServer().use {server->seed(server);val account=account(server);val c=controller(server,account)
            compose.setContent {val identity by account.sessionRestore.identities.collectAsState();CmuxTheme {Column {
                Text("Fixture account");NativeAccountDeletionButton("same-login",null,c::begin,identity,c::confirmationOwner,true)}}}
            compose.onNodeWithText("Delete Account").performClick();compose.onNodeWithText("Delete Account?").assertIsDisplayed()
            compose.runOnIdle {account.sessionRestore.publishedSignIn(a)}
            compose.onNodeWithText("Delete Account?").assertDoesNotExist();capture("retired-confirmation")
            assertEquals(0,server.requestCount);retained();record("identity-dialog",JSONObject().put("sameUserGenerationDismissedDialog",true).put("deleteRequests",0))}
    }
    @Test fun deletionJoinsBootstrapOutsideTokenWorkThenSendsAfterSameUserValidation()=runBlocking<Unit> {
        MockWebServer().use {server->val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.method=="GET"){entered.countDown();check(release.await(10,TimeUnit.SECONDS));return MockResponse().setBody("{\"id\":\"account-a\"}")}
                return MockResponse().setResponseCode(204)}}
            seed(server);val account=account(server);val c=controller(server,account);account.sessionRestore.start(scope)
            try {assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)});assertTrue(c.begin("same-login"));assertEquals(1,server.requestCount)
                release.countDown();assertEquals(NativeAccountDeletionResult.COMPLETED,result(c).result)
                assertEquals(2,server.requestCount);retained();record("bootstrap-join",JSONObject().put("joinedReadOnlyBootstrapWithoutDeadlock",true)
                    .put("userRequests",1).put("deleteRequests",1).put("pairingPreserved",true))
            } finally {release.countDown()}}
    }
}
