package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Actual account, Keystore and Settings component; backend/provider responses are local fixtures. */
class NativeTeamMutationRuntimeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val owners=mutableListOf<NativeAccountTeams>()
    private val user=NativeSignInUser("user","Account User",null,"one")
    private fun client(server:MockWebServer)=NativeOAuthClient("fixture-project","fixture-key",server.url("/"))
    private fun profile(flag:String?=null)=MockResponse().setBody(JSONObject().put("id","user").put("display_name","Account User")
        .put("selected_team",JSONObject().put("id","one")).apply{if(flag!=null)put(flag,true)}.toString())
    private fun members()=MockResponse().setBody("{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"},{\"id\":\"two\",\"display_name\":\"Two\"}]}")
    private fun created()=MockResponse().setBody("{\"team\":{\"id\":\"new\",\"name\":\"New team\"}}")
    private fun teams(server:MockWebServer,account:NativeAccount)=NativeAccountTeams(account,store,server.url("/api/v1/"),
        backendOrigin=server.url("/")).also{owners+=it}
    private suspend fun binding(server:MockWebServer,failList:Boolean=false):Pair<NativeAccount,NativeAccountTeams> {
        val token="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject().put("exp",System.currentTimeMillis()/1000+3600).toString().toByteArray())+".fixture"
        store.update{it.put("task_session","login").put("access_token",token).put("refresh_token","fixture-refresh")
            .put("fixture_pairing_marker","retain");NativeAuthUserCache.write(it,client(server).accountEnvironment,user)}
        val account=NativeAccount(store,client(server),NativeOAuthBrowser());val teams=teams(server,account)
        account.onSignedIn={login,value->teams.refreshAfterSignIn(login,value)}
        server.enqueue(profile());server.enqueue(if(failList)MockResponse().setResponseCode(503) else members())
        account.sessionRestore.start(scope);withTimeout(5000){account.sessionRestore.awaitBootstrapped()}
        return account to teams
    }
    private fun record(case:String,value:JSONObject) {
        assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
        File(context.getExternalFilesDir(null),"team-mutation").apply{mkdirs()}.resolve("$case.json")
            .writeText(value.put("pairingPreserved",true).put("fixtureOnly",true).toString(2))
    }
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="team-mutation-fixture-"+UUID.randomUUID();alias="cmux_team_mutation_fixture_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(context,name,alias)
    }
    @After fun cleanup(){scope.cancel();runBlocking{scope.coroutineContext[Job]!!.join()};owners.forEach{it.close()}
        store.clear();context.deleteSharedPreferences(name);KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}
    @Test fun freshIneligibleUsersCannotPatchDespitePreviouslyLoadedMembership()=runBlocking<Unit> {
        MockWebServer().use{server->val(_,teams)=binding(server);val before=checkNotNull(teams.state.value.scope);val proof=checkNotNull(teams.captureContinuation(before))
            for(flag in listOf("is_anonymous","is_restricted")){server.enqueue(profile(flag));assertTrue(runCatching{teams.select("two")}.exceptionOrNull() is NativeSessionUserUnavailable)}
            assertEquals(4,server.requestCount);assertTrue(teams.isCurrent(before));assertTrue(teams.allowsContinuation(proof))
            assertEquals(listOf("GET","GET","GET","GET"),(1..4).map{server.takeRequest().method})
            record("ineligible",JSONObject().put("freshReads",2).put("patchRequests",0).put("loadedProofPreserved",true))}
    }
    @Test fun successfulEmptyPatchResponsePersistsSelectionInEncryptedDisplayCache()=runBlocking<Unit> {
        MockWebServer().use{server->val(account,teams)=binding(server);server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204))
            assertEquals("two",teams.select("two").scope?.teamId)
            val cipher=context.getSharedPreferences(name,0).getString("state",null)!!;assertFalse(cipher.contains("Account User"))
            val cold=teams(server,account);assertTrue(cold.state.value.cached);assertEquals("two",cold.state.value.selectedTeamId);assertNull(cold.state.value.scope)
            record("empty-patch",JSONObject().put("httpRequests",4).put("encryptedCacheRestored",true).put("coldAuthorityNotGranted",true))}
    }
    @Test fun createdTeamAfterFailedInitialMembershipIsVisibleWithoutInventingScope()=runBlocking<Unit> {
        MockWebServer().use{server->val(_,teams)=binding(server,failList=true);assertFalse(teams.hasLoadedMembership)
            server.enqueue(created());server.enqueue(profile());server.enqueue(members());server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204))
            val state=teams.create("New team");assertEquals("new",state.selectedTeamId);assertTrue(state.teams.any{it.id=="new"})
            assertNull(state.scope);assertFalse(teams.hasLoadedMembership);assertEquals(7,server.requestCount)
            record("created-before-loaded",JSONObject().put("createdAndSelected",true).put("unloadedMembershipPreserved",true).put("httpRequests",7))}
    }
    @Test fun failedPostCreateMembershipStillSelectsAndNeverRepeatsThePost()=runBlocking<Unit> {
        MockWebServer().use{server->val(_,teams)=binding(server)
            server.enqueue(created());server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(503));server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204))
            assertEquals("new",teams.create("New team").scope?.teamId)
            val methods=(1..7).map{server.takeRequest().method};assertEquals(1,methods.count{it=="POST"});assertEquals(1,methods.count{it=="PATCH"})
            record("create-list-failure",JSONObject().put("createPosts",1).put("selectionPatches",1).put("httpRequests",7))}
    }
    @Test fun aDelayedEligibilityResponseCannotPatchOrPublishIntoANewSameUserGeneration()=runBlocking<Unit> {
        MockWebServer().use{server->val(account,teams)=binding(server);repeat(2){server.takeRequest()};val before=checkNotNull(teams.state.value.scope)
            server.enqueue(profile().setBodyDelay(200,TimeUnit.MILLISECONDS));val pending=scope.async{runCatching{teams.select("two")}}
            assertEquals("GET",withContext(Dispatchers.IO){server.takeRequest(3,TimeUnit.SECONDS)!!.method})
            account.sessionRestore.publishedSignIn(user.copy(name="Replacement User"));teams.reconcileLogin()
            assertTrue(withTimeout(5000){pending.await()}.isFailure);assertEquals(3,server.requestCount);assertFalse(teams.isCurrent(before));assertNull(teams.state.value.scope)
            assertEquals("Replacement User",account.sessionRestore.state.value.user?.name)
            record("replacement",JSONObject().put("patchRequests",0).put("replacementProfilePreserved",true).put("oldScopeRetired",true))}
    }
    @Test fun newerSelectionCompletesBeforeAStalledOldPatchAndItsLateReplyCannotOverwriteTheCache()=runBlocking<Unit> {
        MockWebServer().use{server->val(account,teams)=binding(server)
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val patches=AtomicInteger()
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.method=="GET")return profile()
                if(patches.incrementAndGet()==1){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
                return MockResponse().setResponseCode(204)
            }}
            val old=scope.async{runCatching{teams.select("two")}}
            try {
                assertTrue(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
                assertEquals("one",withTimeout(2500){teams.select("one")}.selectedTeamId);assertFalse(old.isCompleted)
                assertTrue(runCatching{teams.create("Blocked")}.isFailure)
                release.countDown();assertTrue(withTimeout(5000){old.await()}.isFailure)
                assertEquals("one",teams.state.value.selectedTeamId);assertFalse(teams.state.value.loading)
                assertEquals("one",teams(server,account).state.value.selectedTeamId)
                record("parallel-selection",JSONObject().put("newerFinishedBeforeOldReply",true).put("lateCacheOverwritePrevented",true).put("selectionPatches",2))
            } finally {release.countDown();old.cancelAndJoin()}}
    }
    @Test fun settingsCanFinishCreationAfterVerifiedUserPublicationWithFailedMembership() {
        MockWebServer().use{server->val(_,teams)=runBlocking{binding(server,failList=true)}
            compose.setContent{CmuxTheme{Surface{Column(Modifier.statusBarsPadding()){
                val state by teams.state.collectAsState()
                NativeAccountTeamSection(state,onRefresh={},onSelect={},onCreate={teams.create(it);true})
            }}}}
            compose.onNodeWithText("Create Team").assertIsEnabled().performClick()
            compose.onNodeWithText("Team name").performTextInput("New team")
            server.enqueue(created());server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(503));server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204))
            compose.onNodeWithText("Create",useUnmergedTree=false).performClick()
            compose.waitUntil(5000){teams.state.value.selectedTeamId=="new" && !teams.state.value.loading}
            compose.onNodeWithText("Team name").assertDoesNotExist();assertNull(teams.state.value.scope);assertFalse(teams.hasLoadedMembership)
            record("settings-created",JSONObject().put("dialogCompleted",true).put("unloadedScopeNotPromoted",true).put("httpRequests",server.requestCount))}
    }
    @Test fun aPrimedColdAccountCanCreateThroughBackendBeforeBootstrapWithoutConnectionAuthority()=runBlocking<Unit> {
        MockWebServer().use{server->binding(server);repeat(2){server.takeRequest()}
            val account=NativeAccount(store,client(server),NativeOAuthBrowser());val cold=teams(server,account)
            assertTrue(account.sessionRestore.state.value.authenticated);assertTrue(account.sessionRestore.needsIdentityValidation)
            assertTrue(cold.state.value.cached);assertFalse(cold.hasLoadedMembership)
            server.enqueue(created());server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(503));server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204))
            assertEquals("new",cold.create("New team").selectedTeamId);assertNull(cold.state.value.scope);assertFalse(cold.hasLoadedMembership)
            assertTrue(account.sessionRestore.needsIdentityValidation);assertEquals("POST",server.takeRequest().method)
            record("cached-create",JSONObject().put("backendBeforeBootstrap",true).put("membershipAuthorityNotInvented",true).put("httpRequests",server.requestCount))}
    }
    private fun showPicker(teams:NativeAccountTeams) {compose.setContent{CmuxTheme{Surface{Column(Modifier.statusBarsPadding()){
        val state by teams.state.collectAsState()
        NativeAccountTeamSection(state,onRefresh={},onSelect={teams.select(it);Unit},onCreate={error("No creation expected")})
    }}}}}
    private fun capture(case:String) {
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder=File(context.getExternalFilesDir(null),"team-mutation").apply{mkdirs()}
        try{folder.resolve("$case.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    @Test fun pickerShowsPendingChoiceAndAllowsANewerChoiceBeforeTheOldPatchFinishes() {
        MockWebServer().use{server->val(_,teams)=runBlocking{binding(server)};val original=checkNotNull(teams.state.value.scope)
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val patches=AtomicInteger()
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.method=="GET")return profile()
                if(patches.incrementAndGet()==1){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
                return MockResponse().setResponseCode(204)
            }}
            showPicker(teams)
            try {
                compose.onNodeWithTag("settings.team:two").performClick()
                compose.waitUntil(4000){entered.count==0L}
                compose.onNodeWithTag("settings.team:two").assertIsSelected()
                compose.onNodeWithTag("settings.team:one").assertIsEnabled()
                assertEquals(original,teams.state.value.scope)
                compose.onNodeWithTag("settings.team:one").performClick()
                compose.waitUntil(5000){patches.get()==2 && !teams.state.value.loading}
                compose.onNodeWithTag("settings.team:one").assertIsSelected();assertEquals(original,teams.state.value.scope)
                release.countDown();compose.waitForIdle();compose.onNodeWithTag("settings.team.error").assertDoesNotExist()
                capture("picker-latest-choice")
                record("picker-pending",JSONObject().put("pendingChoiceSelectedBeforeCommit",true).put("pickerRemainedEnabled",true)
                    .put("latestChoicePreserved",true).put("scopeUnchangedUntilSuccess",true))
            }finally{release.countDown()}}
    }
    @Test fun pickerFailureRestoresTheCommittedChoiceAndLocalizedRetryClearsTheError() {
        MockWebServer().use{server->val(_,teams)=runBlocking{binding(server)};showPicker(teams)
            server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(403))
            compose.onNodeWithTag("settings.team:two").performClick()
            compose.waitUntil(5000){teams.state.value.error!=null && !teams.state.value.loading}
            compose.onNodeWithTag("settings.team.error").assertTextEquals(context.getString(R.string.cmux_team_selection_failed))
            compose.onNodeWithTag("settings.team:one").assertIsSelected();assertEquals("one",teams.state.value.scope?.teamId)
            capture("picker-failure")
            server.enqueue(profile());server.enqueue(MockResponse().setResponseCode(204))
            compose.onNodeWithTag("settings.team:two").performClick();compose.waitUntil(5000){teams.state.value.scope?.teamId=="two" && !teams.state.value.loading}
            compose.onNodeWithTag("settings.team:two").assertIsSelected();compose.onNodeWithTag("settings.team.error").assertDoesNotExist()
            record("picker-retry",JSONObject().put("failureRestoredCommittedChoice",true).put("localizedFailureVisible",true).put("successfulRetryClearedFailure",true))}
    }
}
