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
import java.util.concurrent.atomic.AtomicReference

/** Real account, encrypted store and HTTP; provider replies are isolated loopback fixtures. */
class NativeTeamRefreshReadsRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val controllers=mutableListOf<NativeAccountTeams>()
    private val plans=mutableListOf<Plan>()
    private val published=NativeSignInUser("refresh-user","Published profile","fixture@example.test","one")
    private fun client(server:MockWebServer)=NativeOAuthClient(NativeAccount.PROJECT_ID,NativeAccount.PUBLISHABLE_KEY,server.url("/"))
    private fun profile(user:NativeSignInUser,selected:String?=user.selectedTeam)=MockResponse().setBody(JSONObject()
        .put("id",user.id).put("display_name",user.name).put("primary_email",user.email)
        .put("selected_team",selected?.let{JSONObject().put("id",it)} ?: JSONObject.NULL).toString())
    private inner class Plan(val server:MockWebServer) : Dispatcher() {
        val userReads=AtomicInteger();val memberReads=AtomicInteger();val freshStarted=CountDownLatch(2);val release=CountDownLatch(1)
        @Volatile var holdFresh=false
        @Volatile var empty=false
        @Volatile var ineligible=false
        @Volatile var delayMillis=0L
        override fun dispatch(request:RecordedRequest):MockResponse {
            if(request.path=="/api/v1/users/me" && request.method=="GET") {
                // The auth coordinator finishes its user read before starting either team read.
                val authRead=userReads.incrementAndGet()%3==1
                if(authRead)return profile(published)
                freshStarted.countDown()
                if(holdFresh)check(release.await(8,TimeUnit.SECONDS))
                return if(ineligible)MockResponse().setBody("{\"id\":\"refresh-user\",\"is_anonymous\":true}")
                    else profile(published.copy(name="SDK lookup profile"),if(empty)null else "two").setBodyDelay(delayMillis,TimeUnit.MILLISECONDS)
            }
            if(request.path=="/api/v1/teams?user_id=me" && request.method=="GET") {
                memberReads.incrementAndGet()
                return MockResponse().setBody(if(empty)"{\"items\":[]}" else
                    "{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"},{\"id\":\"two\",\"display_name\":\"Two\"}]}")
                    .setBodyDelay(delayMillis,TimeUnit.MILLISECONDS)
            }
            return MockResponse().setResponseCode(599).setBody("Unexpected fixture endpoint")
        }
    }
    private fun binding(server:MockWebServer,timeout:Long=NativeAccountTeams.NETWORK_TIMEOUT_MILLIS):Triple<NativeAccount,NativeAccountTeams,Plan> {
        val access="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject()
            .put("exp",System.currentTimeMillis()/1000+3600).toString().toByteArray())+".fixture"
        store.update {it.put("task_session","refresh-login").put("access_token",access).put("refresh_token","fixture-refresh")
            .put("fixture_pairing_marker","retain");NativeAuthUserCache.write(it,client(server).accountEnvironment,published)}
        val plan=Plan(server).also{plans+=it;server.dispatcher=it}
        val account=NativeAccount(store,client(server),NativeOAuthBrowser())
        val teams=NativeAccountTeams(account,store,server.url("/api/v1/"),networkTimeoutMillis=timeout).also{controllers+=it}
        account.onSignedIn={owner,user->teams.refreshAfterSignIn(owner,user)}
        return Triple(account,teams,plan)
    }
    private suspend fun boot(account:NativeAccount){account.sessionRestore.start(scope);withTimeout(5000){account.sessionRestore.awaitBootstrapped()}}
    private suspend fun both(plan:Plan){assertTrue(withContext(Dispatchers.IO){plan.freshStarted.await(5,TimeUnit.SECONDS)})}
    private fun record(case:String,data:JSONObject) {
        assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
        val cipher=context.getSharedPreferences(name,0).getString("state",null)!!
        assertFalse(cipher.contains("Published profile"));assertFalse(cipher.contains("fixture-refresh"))
        File(context.getExternalFilesDir(null),"team-reads").apply{mkdirs()}.resolve("$case.json")
            .writeText(data.put("fixtureOnly",true).put("pairingPreserved",true).put("encryptedStoreUsed",true).toString(2))
    }
    @Before fun setup(){check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="team-reads-fixture-"+UUID.randomUUID();alias="cmux_team_reads_fixture_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(context,name,alias)}
    @After fun cleanup(){plans.forEach{it.release.countDown()};scope.cancel();runBlocking{scope.coroutineContext[Job]!!.join()
        withTimeout(5000){while(store.authWork.activeCount>0)delay(5)}}
        controllers.forEach{it.close()};store.clear();context.deleteSharedPreferences(name)
        KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}

    @Test fun callbackMakesFreshMembershipAndSelectionReadsWithoutReplacingThePublishedProfile()=runBlocking<Unit> {
        MockWebServer().use{server->val(account,teams,plan)=binding(server);boot(account)
            assertEquals(3,plan.userReads.get());assertEquals(1,plan.memberReads.get());assertEquals(4,server.requestCount)
            assertEquals("two",teams.state.value.scope?.teamId);assertEquals(published.name,teams.state.value.displayName)
            assertEquals(published,account.sessionRestore.state.value.user)
            record("fresh-callback",JSONObject().put("authUserReads",1).put("freshTeamUserReads",2).put("membershipReads",1)
                .put("independentSelectedChoiceApplied",true).put("publishedProfilePreserved",true))}
    }
    @Test fun bothFreshReadsStartBeforeEitherReplyAndNoPartialScopeIsPublished()=runBlocking<Unit> {
        MockWebServer().use{server->val(account,teams,plan)=binding(server);plan.holdFresh=true;account.sessionRestore.start(scope)
            try{both(plan);assertTrue(account.sessionRestore.state.value.authenticated);assertEquals(1,teams.activeMembershipReads)
                assertNull(teams.state.value.scope);assertEquals(3,plan.userReads.get());assertEquals(0,plan.memberReads.get())
                plan.release.countDown();withTimeout(5000){account.sessionRestore.awaitBootstrapped()};assertEquals("two",teams.state.value.scope?.teamId)
                record("parallel-reads",JSONObject().put("freshRequestsStartedBeforeReplies",2).put("partialScopeWithheld",true))
            }finally{plan.release.countDown()}}
    }
    @Test fun emptyMembershipKeepsTheEncryptedChoiceButRetiresEveryLiveProof()=runBlocking<Unit> {
        MockWebServer().use{server->val(account,teams,plan)=binding(server);boot(account)
            val old=checkNotNull(teams.state.value.scope);val proof=checkNotNull(teams.captureContinuation(old));val identity=account.sessionRestore.identity()
            plan.empty=true;account.sessionRestore.revalidate()
            assertEquals(identity,account.sessionRestore.identity());assertTrue(teams.hasLoadedMembership);assertTrue(teams.state.value.teams.isEmpty())
            assertEquals("two",teams.state.value.selectedTeamId);assertNull(teams.state.value.scope);assertFalse(teams.allowsContinuation(proof))
            val cold=NativeAccountTeams(account,store,server.url("/api/v1/")).also{controllers+=it}
            assertTrue(cold.state.value.cached);assertEquals("two",cold.state.value.selectedTeamId);assertNull(cold.state.value.scope)
            assertEquals(6,plan.userReads.get());assertEquals(2,plan.memberReads.get())
            record("empty-remembers",JSONObject().put("choiceRestoredFromEncryptedCache",true).put("loadedEmptyMembership",true)
                .put("oldProofRetired",true).put("coldScopeIsNil",true).put("sameUserGenerationKept",true))}
    }
    @Test fun aPublishedEligibleUserCannotBypassFreshMembershipIneligibility()=runBlocking<Unit> {
        MockWebServer().use{server->val(account,teams,plan)=binding(server);plan.ineligible=true;plan.holdFresh=true;account.sessionRestore.start(scope)
            try{both(plan);plan.release.countDown();withTimeout(5000){account.sessionRestore.awaitBootstrapped()}
                assertTrue(account.sessionRestore.state.value.authenticated);assertEquals(published,account.sessionRestore.state.value.user)
                assertEquals(0,plan.memberReads.get());assertFalse(teams.hasLoadedMembership);assertNull(teams.state.value.scope)
                assertEquals(published.name,teams.state.value.displayName)
                record("fresh-ineligible",JSONObject().put("freshUserReads",2).put("membershipRequests",0).put("publishedUserKept",true))
            }finally{plan.release.countDown()}}
    }
    @Test fun aSingleTeamDeadlineBoundsFreshUserAndMembershipHttpTogether()=runBlocking<Unit> {
        MockWebServer().use{server->val(account,teams,plan)=binding(server,300);plan.delayMillis=190
            val observed=AtomicReference<Exception?>();teams.onMembershipCompleted={_,failure->observed.set(failure)}
            val start=android.os.SystemClock.elapsedRealtime();boot(account);val elapsed=android.os.SystemClock.elapsedRealtime()-start
            val error=observed.get();assertTrue(error.toString(),error is NativeSignInFailure)
            assertEquals(NativeSignInError.TIMED_OUT,(error as NativeSignInFailure).reason);assertTrue(elapsed in 250..2500)
            assertEquals(3,plan.userReads.get());assertEquals(1,plan.memberReads.get());assertEquals(0,teams.activeMembershipReads)
            assertNull(teams.state.value.scope);assertTrue(account.sessionRestore.state.value.authenticated)
            record("shared-budget",JSONObject().put("configuredPhaseMillis",300).put("elapsedMillis",elapsed)
                .put("publishedAccountPreserved",true).put("partialScopeWithheld",true))}
    }
    @Test fun replacingThePublishedIdentityWhileFreshReadsWaitRejectsTheirOldResults()=runBlocking<Unit> {
        MockWebServer().use{server->val(account,teams,plan)=binding(server);plan.holdFresh=true;account.sessionRestore.start(scope)
            try{both(plan);val old=checkNotNull(account.sessionRestore.identity());val replacement=published.copy(id="replacement",name="Replacement")
                account.sessionRestore.publishedSignIn(replacement)
                store.update{NativeAccountProfileCache.writeVerifiedUser(it,"refresh-login",client(server).accountEnvironment,replacement)}
                teams.reconcileLogin();plan.release.countDown();withTimeout(5000){account.sessionRestore.awaitBootstrapped()}
                assertFalse(account.sessionRestore.isIdentityCurrent(old));assertEquals(replacement,account.sessionRestore.state.value.user)
                assertEquals(replacement.id,teams.state.value.userId);assertEquals(replacement.name,teams.state.value.displayName);assertNull(teams.state.value.scope)
                record("replacement",JSONObject().put("oldResultsRejected",true).put("replacementProfilePreserved",true)
                    .put("controlledPublication",true).put("providerNotExercised",true))
            }finally{plan.release.countDown()}}
    }
}
