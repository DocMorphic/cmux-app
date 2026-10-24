package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey

/** Real encrypted state and HTTP; no physical devices, providers or production account files. */
class NativeAccessOnlySessionRuntimeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val controllers=mutableListOf<NativeAccountTeams>();private val releases=mutableListOf<CountDownLatch>()
    private val user=NativeSignInUser("access-account","Access Account",null,"one")
    private fun keys()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    private fun token()="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(
        JSONObject().put("exp",System.currentTimeMillis()/1000+3600).toString().toByteArray())+".fixture"
    private fun client(server:MockWebServer)=NativeOAuthClient(NativeAccount.PROJECT_ID,"fixture-key",server.url("/"))
    private fun account(server:MockWebServer,reader:NativeCredentialStore=store)=NativeAccount(reader,client(server),NativeOAuthBrowser(),tokenRefreshLeadMillis=120_000)
    private fun teams(server:MockWebServer,account:NativeAccount)=NativeAccountTeams(account,store,server.url("/api/v1/")).also{controllers+=it}
    private fun seed(login:String?=null):String=token().also{access->store.update{root->
        root.put("access_token",access).put("refresh_token","").put("fixture_pairing_marker","retain")
        root.remove(NativeAuthUserCache.KEY);root.remove(NativeAccountProfileCache.KEY);root.remove(NativeSignInCompletion.PENDING)
        if(login==null)root.remove("task_session") else root.put("task_session",login)
    }}
    private fun userReply()=MockResponse().setBody(JSONObject().put("id",user.id).put("display_name",user.name)
        .put("selected_team",JSONObject().put("id","one")).toString())
    private fun serve(server:MockWebServer,beforeUser:()->Unit={}) {server.dispatcher=object:Dispatcher(){
        override fun dispatch(request:RecordedRequest):MockResponse=when {
            request.path=="/api/v1/users/me" && request.method=="GET"->{beforeUser();userReply()}
            request.path=="/api/v1/users/me" && request.method=="PATCH"->MockResponse().setResponseCode(204)
            request.path=="/api/v1/teams?user_id=me"->MockResponse().setBody("{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"},{\"id\":\"two\",\"display_name\":\"Two\"}]}")
            else->MockResponse().setResponseCode(500)
        }
    }}
    private fun gate()=CountDownLatch(1).also{releases+=it}
    private fun verifyingEncryption()=Thread.currentThread().stackTrace.any{it.className==NativeCredentialStore::class.java.name && it.methodName.startsWith("verifyAccessOnlySession")} &&
        Thread.currentThread().stackTrace.any{it.className==NativeCredentialStore::class.java.name && it.methodName=="save"}
    private fun cipher()=context.getSharedPreferences(name,0).getString("state",null)
    private fun assertAccessOnlyRequests(server:MockWebServer,count:Int,access:String){
        assertEquals(count,server.requestCount)
        repeat(count){val request=checkNotNull(server.takeRequest(2,TimeUnit.SECONDS))
            assertEquals(access,request.getHeader("x-hexclave-access-token"));assertNull(request.getHeader("x-hexclave-refresh-token"))
            assertFalse(request.path!!.contains("oauth/token"))}
    }
    private fun record(case:String,values:JSONObject){assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
        File(context.getExternalFilesDir(null),"access-only").apply{mkdirs()}.resolve("$case.json").writeText(values
            .put("fixtureOnly",true).put("encryptedStoreUsed",true).put("pairingMarkerPreserved",true).toString(2))}
    private suspend fun drain(){withTimeout(5000){while(store.authWork.activeCount>0)delay(2)}}
    @Before fun setup(){check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="access-only-fixture-"+UUID.randomUUID();alias="cmux_access_only_"+UUID.randomUUID();store=NativeCredentialStore.withKeyProvider(context,name,alias)}
    @After fun cleanup(){releases.forEach{it.countDown()};controllers.forEach{it.close()};scope.cancel()
        runBlocking{scope.coroutineContext[Job]!!.join();drain()};store.clear();context.deleteSharedPreferences(name);keys().deleteEntry(alias)}

    @Test fun liveVerificationEstablishesAStableLocalIncarnationAndMembershipWithoutRefreshOwnership()=runBlocking<Unit> {
        val rows=JSONArray()
        for(existing in listOf<String?>(null,"existing-login"))MockWebServer().use{server->
            val access=seed(existing);serve(server);val owner=account(server);val teams=teams(server,owner)
            owner.onSignedIn={login,user->teams.refreshAfterSignIn(login,user)}
            val before=cipher();val keyCreated=keys().getCreationDate(alias);assertEquals(access,owner.sdkAccessToken());assertEquals(before,cipher())
            assertNull(store.taskSession());assertFalse(owner.isSignedIn());assertNull(teams.state.value.scope)
            assertEquals(user,withTimeout(5000){owner.sessionRestore.restoreIdentityIfNeeded()})
            val login=checkNotNull(store.taskSession());if(existing!=null)assertEquals(existing,login)
            assertTrue(owner.isSignedIn());assertEquals(user.id,owner.sessionRestore.identity()?.userId)
            val team=checkNotNull(teams.state.value.scope);assertTrue(teams.isCurrent(team));assertNotNull(teams.captureContinuation(team))
            assertEquals(4,server.requestCount);assertNull(store.refreshView().owner);assertEquals(access,owner.resolvedAccessToken())
            assertTrue(runCatching{owner.authenticatedSessionSnapshot()}.isFailure);assertTrue(runCatching{owner.authenticatedRefreshTokenSnapshot()}.isFailure)
            val key=NativeWorkspaceTabKey(user.id,"one","fixture-mac","workspace-${existing ?: "new"}")
            assertTrue(store.rememberWorkspaceTab(login,key,NativeWorkspaceTab.LocalBrowser));assertEquals(NativeWorkspaceTab.LocalBrowser,store.lastWorkspaceTab(login,key))
            assertEquals("",store.requireReadable()!!.getString("refresh_token"));assertTrue(store.requireReadable()!!.has(NativeAccountProfileCache.KEY))
            assertEquals(keyCreated,keys().getCreationDate(alias));assertAccessOnlyRequests(server,4,access)
            rows.put(JSONObject().put("existingReceipt",existing!=null).put("localIncarnationEstablished",true).put("membershipVerified",true)
                .put("fullPairDenied",true).put("noRefreshOwner",true).put("workspacePreferenceSaved",true))
        }
        record("verified-incarnation",JSONObject().put("rows",rows))
    }
    @Test fun coldReconstructionRevalidatesTheUserBeforeAdmittingMembershipAndKeepsTheIncarnation()=runBlocking<Unit> {
        MockWebServer().use{server->val access=seed();serve(server);val first=account(server)
            assertEquals(user,first.sessionRestore.restoreIdentityIfNeeded());val login=checkNotNull(store.taskSession())
            val second=account(server);val teams=teams(server,second)
            assertTrue(second.sessionRestore.state.value.authenticated);assertNull(second.sessionRestore.verifiedUser());assertNull(teams.state.value.scope)
            assertNotNull(withTimeout(5000){teams.refresh().scope});assertEquals(login,store.taskSession())
            assertEquals(user,second.sessionRestore.verifiedUser());assertNull(store.refreshView().owner);assertEquals(5,server.requestCount)
            assertAccessOnlyRequests(server,5,access)
            record("cold-reconstruction",JSONObject().put("incarnationStable",true).put("liveRevalidationRequired",true).put("membershipVerified",true))}
    }
    @Test fun pendingAccessAndCachedProofStayExcludedFromOrdinaryLocalAndRefreshAuthority()=runBlocking<Unit> {
        MockWebServer().use{server->seed("pending-login");store.update{
                NativeAuthUserCache.write(it,client(server).accountEnvironment,user);it.put(NativeSignInCompletion.PENDING,"pending-login")}
            val owner=account(server);val before=cipher()
            assertNull(store.taskSession());assertFalse(owner.isSignedIn());assertNull(owner.sdkAccessToken());assertNull(store.refreshView().owner)
            assertFalse(store.rememberWorkspaceTab("pending-login",NativeWorkspaceTabKey(user.id,"one","mac","workspace"),NativeWorkspaceTab.LocalBrowser))
            val failure=runCatching{owner.accessToken()}.exceptionOrNull() as NativeSignInFailure
            assertEquals(NativeSignInError.NETWORK_ERROR,failure.reason);assertEquals(before,cipher());assertEquals(0,server.requestCount)
            record("pending-exclusion",JSONObject().put("ordinaryAndLocalAuthorityDenied",true).put("noRefreshOwner",true).put("ciphertextPreserved",true))}
    }
    @Test fun controlCredentialsKeepTheOriginalFullPairAndForcedRefreshContracts()=runBlocking<Unit> {
        MockWebServer().use{server->val access=seed();serve(server);val owner=account(server)
            assertEquals(user,owner.sessionRestore.restoreIdentityIfNeeded());assertTrue(owner.isSignedIn())
            assertEquals(access,owner.accessToken());assertNull(store.refreshView().owner)
            val missing=runCatching{owner.controlAccessToken(false){true}}.exceptionOrNull() as NativeSignInFailure
            assertEquals(NativeSignInError.UNAUTHORIZED,missing.reason);assertTrue(owner.sessionRestore.state.value.authenticated)
            val forced=runCatching{owner.controlAccessToken(true){true}}.exceptionOrNull() as NativeSignInFailure
            assertEquals(NativeSignInError.UNAUTHORIZED,forced.reason);assertFalse(owner.sessionRestore.state.value.authenticated)
            val pairAccess=seed("pair-login");store.update{it.put("refresh_token","pair-refresh");NativeAuthUserCache.write(it,client(server).accountEnvironment,user)}
            store.setAuthTokenHint(client(server).accountEnvironment,true);val pair=account(server)
            assertEquals(pairAccess,pair.controlAccessToken(false){true})
            assertTrue(runCatching{pair.controlAccessToken(false){false}}.exceptionOrNull() is IllegalStateException)
            var checks=0;assertTrue(runCatching{pair.controlAccessToken(false){++checks==1}}.exceptionOrNull() is IllegalStateException)
            assertEquals(2,checks);assertAccessOnlyRequests(server,1,access)
            record("control-credentials",JSONObject().put("accessOnlyInitialControlDenied",true).put("ordinaryMissPreservedPresentation",true)
                .put("forcedMissingRefreshClearedPresentation",true).put("storedPairUsedWithoutHttp",true).put("scopeCheckedBeforeAndAfter",true))}
    }
    @Test fun failedMissingOrIneligibleUserCannotEstablishAnAccessOnlyIncarnation()=runBlocking<Unit> {
        val rows=JSONArray()
        for(body in listOf<String?>(null,"not-json","{}","{\"id\":\"anonymous\",\"is_anonymous\":true}","{\"id\":\"restricted\",\"is_restricted\":true}"))MockWebServer().use{server->
            seed();server.enqueue(if(body==null)MockResponse().setResponseCode(503) else MockResponse().setBody(body))
            val owner=account(server);owner.sessionRestore.restoreIdentityIfNeeded()
            // An empty successful SDK object is a source user with id=""; it cannot grant a team identity.
            if(body=="{}")assertEquals("",owner.sessionRestore.state.value.user?.id) else assertFalse(owner.sessionRestore.state.value.authenticated)
            assertNull(owner.sessionRestore.identity());assertNull(store.refreshView().owner)
            if(body!="{}")assertNull(store.taskSession())
            assertEquals(1,server.requestCount);rows.put(JSONObject().put("sdkEmptyUser",body=="{}").put("noAuthenticatedIdentity",true))
        }
        record("invalid-user",JSONObject().put("rows",rows))
    }
    @Test fun replacementDuringTheRealUserRequestPreventsOldLocalProofAndPublication()=runBlocking<Unit> {
        MockWebServer().use{server->seed();val entered=gate();val release=gate();serve(server){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
            val owner=account(server);val restore=scope.async{owner.sessionRestore.restoreIdentityIfNeeded()}
            try {assertTrue(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
                store.authSessionEpoch.incrementAndGet();store.update{it.put("task_session","replacement").put("access_token",token()).put("refresh_token","new-refresh")}
                release.countDown();withTimeout(5000){restore.await()};assertNull(owner.sessionRestore.identity())
                assertEquals("replacement",store.taskSession());assertFalse(store.requireReadable()!!.has(NativeAuthUserCache.KEY))
                record("request-replacement",JSONObject().put("replacementPreserved",true).put("staleUserNotPublished",true))
            }finally{release.countDown();restore.cancelAndJoin()}}
    }
    @Test fun cancellationDuringEncryptionReturnsBeforeActualWorkAndRejectsItsLateCommit()=runBlocking<Unit> {
        MockWebServer().use{server->seed();serve(server);val entered=gate();val release=gate();val blocked=AtomicBoolean()
            val reader=NativeCredentialStore.withKeyProvider(context,name,alias){if(verifyingEncryption() && blocked.compareAndSet(false,true)){
                entered.countDown();check(release.await(8,TimeUnit.SECONDS))};keys().getKey(alias,null) as SecretKey}
            val owner=account(server,reader);val before=cipher();val restore=scope.launch{owner.sessionRestore.restoreIdentityIfNeeded()}
            try {assertTrue(withContext(Dispatchers.IO){entered.await(4,TimeUnit.SECONDS)});restore.cancel();withTimeout(1000){restore.join()}
                assertTrue(store.authWork.activeCount>0);release.countDown();drain()
                assertEquals(before,cipher());assertNull(store.taskSession());assertNull(owner.sessionRestore.identity())
                record("canceled-encryption",JSONObject().put("waiterReturnedBeforeActualWork",true).put("lateCommitRejected",true))
            }finally{release.countDown();restore.cancelAndJoin()}}
    }
    @Test fun newSignInOwnershipAnnouncedDuringEncryptionPreventsTheOldPrincipalCommit()=runBlocking<Unit> {
        MockWebServer().use{server->seed();serve(server);val entered=gate();val release=gate();val blocked=AtomicBoolean()
            val reader=NativeCredentialStore.withKeyProvider(context,name,alias){if(verifyingEncryption() && blocked.compareAndSet(false,true)){
                entered.countDown();check(release.await(8,TimeUnit.SECONDS))};keys().getKey(alias,null) as SecretKey}
            val owner=account(server,reader);val before=cipher();val restore=scope.async{owner.sessionRestore.restoreIdentityIfNeeded()}
            var lease:NativeAuthWork.SignInLease?=null
            try {assertTrue(withContext(Dispatchers.IO){entered.await(4,TimeUnit.SECONDS)});lease=store.authWork.beginSignIn()
                release.countDown();withTimeout(5000){restore.await()};drain();assertEquals(before,cipher())
                assertNull(store.taskSession());assertNull(owner.sessionRestore.identity())
                record("writer-encryption",JSONObject().put("newWriterRetainedOwnership",true).put("lateCommitRejected",true))
            }finally{release.countDown();lease?.finish();restore.cancelAndJoin()}}
    }
    @Test fun sameEpochReentrantDocumentReplacementCannotBeOverwrittenByVerification()=runBlocking<Unit> {
        MockWebServer().use{server->seed();serve(server);val replaced=AtomicBoolean()
            val reader=NativeCredentialStore.withKeyProvider(context,name,alias){if(verifyingEncryption() && replaced.compareAndSet(false,true))
                store.update{it.put("task_session","replacement").put("access_token",token()).put("refresh_token","new-refresh")}
                keys().getKey(alias,null) as SecretKey}
            val owner=account(server,reader);val epoch=store.authSessionEpoch.get();owner.sessionRestore.restoreIdentityIfNeeded()
            assertTrue(replaced.get());assertEquals(epoch,store.authSessionEpoch.get());assertEquals("replacement",store.taskSession())
            assertFalse(store.requireReadable()!!.has(NativeAuthUserCache.KEY));assertNull(owner.sessionRestore.identity())
            record("reentrant-replacement",JSONObject().put("sameEpochReplacement",true).put("revisionFenceRejectedLateCommit",true))}
    }
    @Test fun mountedTeamSelectionUsesRealAccessOnlyRequestsAndTerminalInputAuthorityRetiresOnLogout()=runBlocking<Unit> {
        MockWebServer().use{server->val access=seed();serve(server);val owner=account(server);val teams=teams(server,owner)
            owner.onSignedIn={login,user->teams.refreshAfterSignIn(login,user)};owner.sessionRestore.restoreIdentityIfNeeded()
            val team=checkNotNull(teams.state.value.scope);val mac=NativeCredentialStore.PairedMac(
                PairingCodeParser.computer(IrohV2Computer("record","peer","fixture-mac","default","Fixture Mac",emptyList()),team),
                "fixture-mac","Fixture Mac","default",accountUserId=user.id,accountTeamId="one")
            val remembered=store.rememberAuthenticatedMac(mac,team){teams.isCurrent(team)}
            lateinit var feed:NativeFeedSession
            compose.runOnIdle{feed=ViewModelProvider(compose.activity,NativeFeedSession.Factory(
                NativeConnector{_,_->error("No dial in this UI/input-admission fixture")},owner,store,teams))[NativeFeedSession::class.java]}
            try {
                val input=checkNotNull(nativeTerminalInputOwner(store.visiblePairedMacs().single(),store.taskSession()))
                assertTrue(feed.allowsTerminalInput(input))
                compose.setContent{CmuxTheme{Surface{Column{NativeAccountTeamSection(teams.state.collectAsState().value,{},
                    {teams.select(it);Unit},onCreate={false})}}}}
                compose.onNodeWithTag("settings.team:two").assertIsDisplayed().assertHasClickAction().performClick()
                compose.waitUntil(5000){teams.state.value.selectedTeamId=="two" && !teams.state.value.loading}
                compose.onNodeWithTag("settings.team:two").assertIsSelected()
                val file=File(context.getExternalFilesDir(null),"access-only/mounted-team.png");file.parentFile!!.mkdirs()
                file.outputStream().use{compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                assertAccessOnlyRequests(server,6,access);assertNull(store.refreshView().owner);assertTrue(owner.signOut())
                assertFalse(feed.allowsTerminalInput(input));assertNull(store.taskSession());assertNull(owner.sessionRestore.identity())
                assertEquals(listOf(remembered),store.pairedMacs())
                record("mounted-team",JSONObject().put("realTeamPatch",true).put("selectedSemantics",true).put("terminalInputAdmissionVerified",true)
                    .put("logoutRetiredInput",true).put("wireTerminalIOProven",false))
            }finally{compose.runOnIdle{compose.activity.viewModelStore.clear()}}
        }
    }
}
