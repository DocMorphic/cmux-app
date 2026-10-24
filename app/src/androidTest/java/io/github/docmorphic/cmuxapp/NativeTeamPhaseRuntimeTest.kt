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

/** Real account/store/HTTP. A token-read seam is used only for explicit cancellation-ignoring faults. */
class NativeTeamPhaseRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val owners=mutableListOf<NativeAccountTeams>()
    private val gates=mutableListOf<CompletableDeferred<Unit>>()
    private var token:suspend (Boolean,NativeAccount)->String?={force,account->account.accessToken(force)}
    private val user=NativeSignInUser("user","Original",null,"one")
    private fun client(server:MockWebServer)=NativeOAuthClient("fixture-project","fixture-key",server.url("/"))
    private fun profile()=MockResponse().setBody("{\"id\":\"user\",\"display_name\":\"Original\",\"selected_team\":{\"id\":\"one\"}}")
    private fun members(ids:String="one,two")=MockResponse().setBody(JSONObject().put("items",org.json.JSONArray(ids.split(',').map{
        JSONObject().put("id",it).put("display_name",it)})).toString())
    private fun created()=MockResponse().setBody("{\"team\":{\"id\":\"new\",\"name\":\"New\"}}")
    private fun controller(server:MockWebServer,account:NativeAccount,timeout:Long=NativeAccountTeams.NETWORK_TIMEOUT_MILLIS,fault:Boolean=false):NativeAccountTeams {
        val result=if(!fault)NativeAccountTeams(account,store,server.url("/api/v1/"),backendOrigin=server.url("/"),networkTimeoutMillis=timeout)
        else NativeAccountTeams({token(it,account)},store::taskSession,server.url("/api/v1/"),refreshCredential={store.load()?.optString("refresh_token")},
            backendOrigin=server.url("/"),cache=NativeAccountProfileCache(store::load,store::update),lock=store.accountStateLock,
            sessionIdentity=account.sessionRestore::identity,restoreUser=account.sessionRestore::restoreIdentityIfNeeded,
            needsRestoration={account.sessionRestore.needsIdentityValidation},phaseNowMillis={android.os.SystemClock.elapsedRealtime()},
            phaseWork=store.authWork,networkTimeoutMillis=timeout,accountUser={account.sessionRestore.state.value.user})
        return result.also{owners+=it}
    }
    private suspend fun binding(server:MockWebServer,timeout:Long=NativeAccountTeams.NETWORK_TIMEOUT_MILLIS,fault:Boolean=false):Pair<NativeAccount,NativeAccountTeams> {
        val access="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject().put("exp",System.currentTimeMillis()/1000+3600).toString().toByteArray())+".fixture"
        store.update{it.put("task_session","login").put("access_token",access).put("refresh_token","fixture-refresh").put("fixture_pairing_marker","retain")
            NativeAuthUserCache.write(it,client(server).accountEnvironment,user)}
        val account=NativeAccount(store,client(server),NativeOAuthBrowser());val teams=controller(server,account,timeout,fault)
        account.onSignedIn={login,value->teams.refreshAfterSignIn(login,value)}
        server.teamUsers(3){profile()};server.enqueue(members());account.sessionRestore.start(scope)
        withTimeout(5000){account.sessionRestore.awaitBootstrapped()};assertTrue(teams.hasLoadedMembership)
        return account to teams
    }
    private fun gate()=CompletableDeferred<Unit>().also{gates+=it}
    private suspend fun drained()=withTimeout(5000){while(store.authWork.activeCount!=0)delay(5)}
    private fun timedOut(error:Throwable?) {assertTrue(error.toString(),error is NativeSignInFailure)
        assertEquals(NativeSignInError.TIMED_OUT,(error as NativeSignInFailure).reason)}
    private fun record(case:String,value:JSONObject){assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
        File(context.getExternalFilesDir(null),"team-phase").apply{mkdirs()}.resolve("$case.json")
            .writeText(value.put("pairingPreserved",true).put("fixtureOnly",true).toString(2))}
    @Before fun setup(){check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="team-phase-fixture-"+UUID.randomUUID();alias="cmux_team_phase_fixture_"+UUID.randomUUID();store=NativeCredentialStore.withKeyProvider(context,name,alias)}
    @After fun cleanup(){gates.forEach{it.complete(Unit)};scope.cancel();runBlocking{scope.coroutineContext[Job]!!.join();drained()}
        owners.forEach{it.close()};store.clear();context.deleteSharedPreferences(name);KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}

    @Test fun selectionBudgetCoversFreshUserAndPatchTogetherWithoutChangingEncryptedAccountOnTimeout()=runBlocking<Unit> {
        MockWebServer().use{server->val(_,teams)=binding(server,timeout=300);val before=checkNotNull(teams.state.value.scope)
            val cipher=context.getSharedPreferences(name,0).getString("state",null)
            server.teamUsers{profile().setBodyDelay(190,TimeUnit.MILLISECONDS)};server.enqueue(MockResponse().setBody("{}").setBodyDelay(190,TimeUnit.MILLISECONDS))
            timedOut(runCatching{teams.select("two")}.exceptionOrNull());assertFalse(teams.state.value.loading);assertEquals(before,teams.state.value.scope)
            assertEquals(cipher,context.getSharedPreferences(name,0).getString("state",null));drained()
            record("selection-budget",JSONObject().put("configuredPhaseMillis",300).put("httpRequests",server.requestCount).put("ciphertextPreserved",true))}
    }
    @Test fun creationUsesThreeIndependentPhaseBudgetsAndNeverRepeatsItsPost()=runBlocking<Unit> {
        MockWebServer().use{server->val(_,teams)=binding(server,timeout=300)
            server.enqueue(created().setBodyDelay(180,TimeUnit.MILLISECONDS));server.teamUsers{profile().setBodyDelay(80,TimeUnit.MILLISECONDS)}
            server.enqueue(members().setBodyDelay(80,TimeUnit.MILLISECONDS));server.teamUsers{profile().setBodyDelay(80,TimeUnit.MILLISECONDS)}
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(80,TimeUnit.MILLISECONDS))
            val started=android.os.SystemClock.elapsedRealtime();assertEquals("new",teams.create("New").scope?.teamId)
            val elapsed=android.os.SystemClock.elapsedRealtime()-started;assertTrue(elapsed>300)
            assertEquals(1,(1..server.requestCount).count{server.takeRequest().method=="POST"})
            record("three-budgets",JSONObject().put("configuredEachMillis",300).put("elapsedMillis",elapsed).put("createPosts",1))}
    }
    @Test fun cancellationIgnoringSelectionReadReleasesUiAndANewerSelectionWinsBeforeActualRetirement()=runBlocking<Unit> {
        MockWebServer().use{server->val(_,teams)=binding(server,timeout=250,fault=true);val entered=CompletableDeferred<Unit>();val release=gate()
            token={_,_->entered.complete(Unit);withContext(NonCancellable){release.await()};"retired-fixture"}
            val old=scope.async{runCatching{teams.select("two")}}
            try{entered.await();timedOut(withTimeout(3000){old.await()}.exceptionOrNull());assertFalse(teams.state.value.loading)
                assertEquals(1,store.authWork.activeCount);token={force,account->account.accessToken(force)}
                server.teamUsers{profile()};server.enqueue(MockResponse().setResponseCode(204));assertEquals("two",teams.select("two").scope?.teamId)
                val requests=server.requestCount;release.complete(Unit);drained();assertEquals(requests,server.requestCount)
                record("plain-retirement",JSONObject().put("actualWorkRetainedAfterPublicTimeout",true).put("newSelectionCompleted",true).put("lateHttpRequests",0))
            }finally{release.complete(Unit);old.cancelAndJoin()}}
    }
    @Test fun membershipCountEndsAtDeadlineWhileActualTokenWorkStillGatesNewReads()=runBlocking<Unit> {
        MockWebServer().use{server->val(_,teams)=binding(server,timeout=250,fault=true);val release=gate();val entered=CompletableDeferred<Unit>()
            token={_,_->entered.complete(Unit);withContext(NonCancellable){release.await()};"retired-fixture"}
            val old=scope.async{runCatching{teams.refresh()}}
            try{entered.await();timedOut(withTimeout(3000){old.await()}.exceptionOrNull());assertEquals(0,teams.activeMembershipReads)
                assertEquals(1,store.authWork.activeCount);val requests=server.requestCount
                token={force,account->account.accessToken(force)};timedOut(runCatching{teams.refresh()}.exceptionOrNull());assertEquals(requests,server.requestCount)
                release.complete(Unit);drained();assertEquals(requests,server.requestCount);server.teamUsers(2){profile()};server.enqueue(members())
                assertNotNull(teams.refresh().scope)
                record("token-retirement",JSONObject().put("publicMembershipReads",0).put("actualWorkRetained",true).put("gatedRetryHttp",0).put("lateHttpRequests",0))
            }finally{release.complete(Unit);old.cancelAndJoin()}}
    }
    @Test fun postCreateListTimeoutStillSelectsTheConfirmedTeamAndRetiredReadCannotResumeHttp()=runBlocking<Unit> {
        MockWebServer().use{server->val(_,teams)=binding(server,timeout=250,fault=true);val release=gate();val tokens=AtomicInteger()
            token={force,account->if(tokens.incrementAndGet()==2)withContext(NonCancellable){release.await()};account.accessToken(force)}
            server.enqueue(created());server.teamUsers{profile()};server.enqueue(MockResponse().setResponseCode(204))
            val create=scope.async{teams.create("New")}
            try{assertEquals("new",withTimeout(5000){create.await()}.scope?.teamId);assertEquals(1,store.authWork.activeCount)
                val requests=server.requestCount;release.complete(Unit);drained();assertEquals(requests,server.requestCount)
                assertEquals(1,(1..requests).count{server.takeRequest().method=="POST"})
                record("list-fallback",JSONObject().put("selectedAfterListTimeout",true).put("createPosts",1).put("lateHttpRequests",0))
            }finally{release.complete(Unit);create.cancelAndJoin()}}
    }
    @Test fun selectionAfterFreshMembershipRemovalKeepsTheNewProfileAndCannotReviveOldProof()=runBlocking<Unit> {
        MockWebServer().use{server->val(account,teams)=binding(server,timeout=2000)
            server.teamUsers{profile()};server.enqueue(MockResponse().setResponseCode(204));val previous=checkNotNull(teams.select("two").scope)
            val proof=checkNotNull(teams.captureContinuation(previous));val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.method=="PATCH"){entered.countDown();check(release.await(5,TimeUnit.SECONDS));return MockResponse().setResponseCode(204)}
                return if(request.path!!.startsWith("/api/v1/teams"))members("one") else profile().setBody(JSONObject().put("id","user").put("display_name","Updated").put("selected_team",JSONObject().put("id","one")).toString())
            }}
            val selection=scope.async{teams.select("two")}
            try{assertTrue(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
                account.sessionRestore.revalidate();assertFalse(teams.allowsContinuation(proof))
                release.countDown();val result=withTimeout(3000){selection.await()}
                assertEquals(listOf("one"),result.teams.map{it.id});assertEquals("Updated",result.displayName)
                assertEquals("one",result.scope?.teamId);assertFalse(teams.allowsContinuation(proof))
                record("fresh-membership",JSONObject().put("removedProofRetired",true).put("newProfilePreserved",true).put("oldMembershipNotRestored",true))
            }finally{release.countDown();selection.cancelAndJoin()}}
    }
    @Test fun cachedOptionalSelectionPersistsNilWithoutVerifyingRowsOrTheNewAccountOwner()=runBlocking<Unit> {
        MockWebServer().use{server->binding(server);val account=NativeAccount(store,client(server),NativeOAuthBrowser());val cold=controller(server,account)
            assertTrue(cold.state.value.cached);assertTrue(account.sessionRestore.needsIdentityValidation)
            server.teamUsers{profile()};server.enqueue(MockResponse().setResponseCode(204));val result=cold.select(null)
            assertTrue(result.cached);assertNull(result.error);assertNull(result.scope);assertFalse(cold.hasLoadedMembership)
            assertTrue(account.sessionRestore.needsIdentityValidation);val restored=controller(server,account)
            assertTrue(restored.state.value.cached);assertNull(restored.state.value.selectedTeamId);assertEquals(2,restored.state.value.teams.size)
            val requests=server.requestCount;assertTrue(runCatching{cold.select("one")}.isFailure);assertEquals(requests,server.requestCount)
            record("cached-nil",JSONObject().put("explicitNilPersisted",true).put("readonlyRowsRetained",true).put("connectionAuthorityNotGranted",true))}
    }
    @Test fun productionSixtySecondDeadlineStopsAProgressingResponseWhoseIdleGapsStayBelowSdkTimeout()=runBlocking<Unit> {
        MockWebServer().use{server->val(_,teams)=binding(server);val original=checkNotNull(teams.state.value.scope)
            val cipher=context.getSharedPreferences(name,0).getString("state",null)
            // Five chunks at0,20,40,60,80 seconds avoid the SDK's30s idle timeout.
            val body=JSONObject().put("id","user").put("padding","x".repeat(33_000)).toString()
            server.teamUsers{MockResponse().setBody(body).throttleBody(8192,20,TimeUnit.SECONDS)}
            val started=android.os.SystemClock.elapsedRealtime();val error=runCatching{teams.select("two")}.exceptionOrNull()
            val elapsed=android.os.SystemClock.elapsedRealtime()-started;timedOut(error)
            assertTrue("Default deadline returned too early: $elapsed",elapsed>=59_000)
            assertTrue("Default deadline did not bound the phase: $elapsed",elapsed<75_000)
            assertEquals(original,teams.state.value.scope);assertFalse(teams.state.value.loading);assertEquals(5,server.requestCount)
            assertEquals(cipher,context.getSharedPreferences(name,0).getString("state",null));drained()
            record("default-sixty-seconds",JSONObject().put("configuredMillis",NativeAccountTeams.NETWORK_TIMEOUT_MILLIS).put("elapsedMillis",elapsed)
                .put("idleGapSeconds",20).put("selectionPatches",0).put("ciphertextPreserved",true).put("tokenFaultInjected",false))
            // Let the local fixture server observe the cancelled socket before shutdown.
            delay(21_000)
        }
    }
}
