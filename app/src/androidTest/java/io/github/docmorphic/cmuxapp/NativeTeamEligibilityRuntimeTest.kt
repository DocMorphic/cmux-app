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

/** Production account/team constructor, isolated Keystore documents and local HTTP. */
class NativeTeamEligibilityRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val a=NativeSignInUser("account-a","Account A","a@example.test","one")
    private val controllers=mutableListOf<NativeAccountTeams>()
    private fun token()="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(
        JSONObject().put("exp",System.currentTimeMillis()/1000+3600).toString().toByteArray())+".fixture"
    private fun client(server:MockWebServer)=NativeOAuthClient("fixture-project","fixture-key",server.url("/"))
    private fun user(flag:String?=null)=JSONObject().put("id",a.id).put("display_name",a.name).put("primary_email",a.email)
        .put("selected_team",JSONObject().put("id","one")).apply {if(flag!=null)put(flag,true)}
    private fun userReply(flag:String?=null)=MockResponse().setBody(user(flag).toString())
    private fun teamReply()=MockResponse().setBody("{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"}]}")
    private fun seed(server:MockWebServer,cached:Boolean=true) {store.update {root->
        root.put("task_session","same-login").put("access_token",token()).put("refresh_token","saved-refresh").put("fixture_pairing_marker","retain")
        if(cached)NativeAuthUserCache.write(root,client(server).accountEnvironment,a)
    }}
    private fun account(server:MockWebServer)=NativeAccount(store,client(server),NativeOAuthBrowser())
    private fun teams(server:MockWebServer,account:NativeAccount)=NativeAccountTeams(account,store,server.url("/api/v1/")).also {controllers+=it}
    private suspend fun establish(server:MockWebServer,account:NativeAccount,c:NativeAccountTeams):Pair<NativeTeamScope,NativeTeamContinuation> {
        server.teamUsers{userReply()};account.sessionRestore.start(scope);withTimeout(5000){account.sessionRestore.awaitBootstrapped()}
        assertFalse(account.sessionRestore.needsIdentityValidation)
        server.teamUsers(2){userReply()};server.enqueue(teamReply());val owner=checkNotNull(c.refreshAfterSignIn("same-login",a).scope)
        return owner to checkNotNull(c.captureContinuation(owner))
    }
    private fun retained()=assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
    private fun record(case:String,value:JSONObject) {File(context.getExternalFilesDir(null),"team-eligibility").apply {mkdirs()}
        .resolve("$case.json").writeText(value.put("fixtureOnly",true).toString(2))}
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="team-eligibility-fixture-"+UUID.randomUUID();alias="cmux_team_eligibility_fixture_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(context,name,alias)
    }
    @After fun cleanup() {scope.cancel();controllers.forEach {it.close()};store.clear();context.deleteSharedPreferences(name)
        KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.deleteEntry(alias)}
    @Test fun coldRestrictedUserCannotPromoteCachedPresentationOrRequestMembership()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server);val cipher=context.getSharedPreferences(name,0).getString("state",null)
            val account=account(server);val c=teams(server,account);server.teamUsers{userReply("is_restricted")}
            assertTrue(runCatching {c.refresh()}.isFailure);assertNull(c.state.value.scope);assertEquals(1,server.requestCount)
            assertEquals("/api/v1/users/me",server.takeRequest(3,TimeUnit.SECONDS)!!.path)
            assertEquals(cipher,context.getSharedPreferences(name,0).getString("state",null));assertTrue(account.sessionRestore.needsIdentityValidation);retained()
            record("cold-restricted",JSONObject().put("membershipRequests",0).put("ciphertextPreserved",true).put("noFreshAuthority",true).put("pairingPreserved",true))}
    }
    @Test fun anonymousAndRestrictedDirectReadsKeepOnlyLoadedAuthorityAndNeverFetchNewTeams()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server);val account=account(server);val c=teams(server,account)
            val(owner,proof)=establish(server,account,c);val identity=account.sessionRestore.identity()
            for(flag in listOf("is_anonymous","is_restricted")) {
                server.teamUsers(2){userReply(flag)};assertTrue(runCatching {c.refresh()}.exceptionOrNull() is NativeSessionUserUnavailable)
                assertEquals(owner,c.state.value.scope);assertTrue(c.allowsContinuation(proof))
            }
            assertEquals(8,server.requestCount);assertEquals(identity,account.sessionRestore.identity())
            server.teamUsers(2){userReply()};server.enqueue(teamReply());assertEquals(owner,c.refresh().scope);assertTrue(c.allowsContinuation(proof));retained()
            record("loaded-ineligible",JSONObject().put("ineligibleUserReads",2).put("newMembershipRequestsForIneligibleUsers",0)
                .put("sameSessionLoadedProofPreserved",true).put("eligibleRetryKeepsGeneration",true).put("pairingPreserved",true))}
    }
    @Test fun ordinaryUserReadFailuresDoNotBecomeEmptyMembershipAndRateLimitStaysDistinct()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server);val account=account(server);val c=teams(server,account);val(owner,proof)=establish(server,account,c)
            val status=java.util.concurrent.atomic.AtomicInteger(403)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                check(request.method=="GET" && request.path=="/api/v1/users/me")
                return MockResponse().setResponseCode(status.get())
            }}
            assertTrue(runCatching {c.refresh()}.exceptionOrNull() is NativeSessionUserUnavailable)
            status.set(429);val rate=runCatching {c.refresh()}.exceptionOrNull();assertTrue(rate.toString(),rate is NativeAccountRateLimited)
            assertEquals(owner,c.state.value.scope);assertTrue(c.allowsContinuation(proof))
            // A failed primary may cancel its optional peer before that peer reaches HTTP.
            assertTrue(server.requestCount in 6..8)
            val requests=List(server.requestCount){checkNotNull(server.takeRequest(1,TimeUnit.SECONDS))}
            assertEquals(1,requests.count{it.path=="/api/v1/teams?user_id=me"});retained()
            record("failed-user-reads",JSONObject().put("loadedProofPreserved",true).put("rateLimitNotCollapsedIntoMissingUser",true).put("pairingPreserved",true))}
    }
    @Test fun successfulEmptyMembershipRetiresProofWhileFailedMembershipPreservesIt()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server);val account=account(server);val c=teams(server,account);val(owner,proof)=establish(server,account,c)
            server.teamUsers(2){userReply()};server.enqueue(MockResponse().setResponseCode(403));assertTrue(runCatching {c.refresh()}.isFailure)
            assertEquals(owner,c.state.value.scope);assertTrue(c.allowsContinuation(proof))
            server.teamUsers(2){userReply()};server.enqueue(MockResponse().setBody("{\"items\":[]}"));c.refresh()
            assertNull(c.state.value.scope);assertFalse(c.allowsContinuation(proof))
            server.teamUsers(2){userReply()};server.enqueue(teamReply());val fresh=checkNotNull(c.refresh().scope)
            assertTrue(c.isCurrent(fresh));assertNotEquals(owner.generation,fresh.generation);assertFalse(c.allowsContinuation(proof));retained()
            record("failure-versus-empty",JSONObject().put("failedReadKeptLoadedProof",true).put("successfulRemovalRetiredProof",true)
                .put("readditionCannotReviveOldProof",true).put("pairingPreserved",true))}
    }
    @Test fun actualRefreshRejectionStillRetiresMembershipWhenTheStoredSessionDisappears()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server);val account=account(server);val c=teams(server,account);val(owner,proof)=establish(server,account,c)
            val both=CountDownLatch(2)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path=="/api/v1/users/me"){both.countDown();check(both.await(5,TimeUnit.SECONDS));return MockResponse().setResponseCode(401)}
                return MockResponse().setResponseCode(401).setBody("{}")
            }}
            assertTrue(runCatching {c.refresh()}.isFailure);assertNull(store.taskSession());assertFalse(c.isCurrent(owner));assertFalse(c.allowsContinuation(proof))
            assertNull(c.state.value.scope);assertEquals(7,server.requestCount);retained()
            record("definitive-session-loss",JSONObject().put("userRequests",2).put("refreshRequests",1).put("oldProofRetired",true).put("pairingPreserved",true))}
    }
    @Test fun aNewGenerationWithFailedMembershipKeepsItsVerifiedProfileWithoutTheOldProof()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server);val account=account(server);val c=teams(server,account);val(_,proof)=establish(server,account,c)
            val fresh=a.copy(name="Updated Account A")
            store.update {NativeAccountProfileCache.writeVerifiedUser(it,"same-login",client(server).accountEnvironment,fresh)}
            account.sessionRestore.publishedSignIn(fresh);server.teamUsers(2){MockResponse().setResponseCode(403)}
            assertTrue(runCatching {c.refreshAfterSignIn("same-login",fresh)}.isFailure)
            assertNull(c.state.value.scope);assertFalse(c.allowsContinuation(proof));assertEquals(fresh.name,c.state.value.displayName)
            val cached=teams(server,account);assertEquals(fresh.name,cached.state.value.displayName);assertNull(cached.state.value.scope);assertTrue(cached.state.value.cached);retained()
            record("new-generation-failure",JSONObject().put("verifiedProfilePreserved",true).put("oldProofRetired",true).put("cacheGrantsNoScope",true).put("pairingPreserved",true))}
    }
    @Test fun aLateIneligibleReplyCannotWriteAnErrorOrCacheForTheReplacementPrincipal()=runBlocking<Unit> {
        MockWebServer().use {server->seed(server);val account=account(server);val c=teams(server,account);val(_,proof)=establish(server,account,c)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                entered.countDown();check(release.await(10,TimeUnit.SECONDS));return userReply("is_anonymous")}}
            val pending=scope.async {runCatching {c.refresh()}}
            try {assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
                val b=a.copy(id="account-b",name="B");account.sessionRestore.publishedSignIn(b)
                store.update {NativeAccountProfileCache.writeVerifiedUser(it,"same-login","${server.url("/api/v1/")}#${NativeAccount.PROJECT_ID}",b)}
                release.countDown();assertTrue(withTimeout(5000){pending.await()}.isFailure);assertFalse(c.allowsContinuation(proof))
                c.reconcileLogin();assertEquals(b.id,c.state.value.userId);assertEquals("B",c.state.value.displayName);assertNull(c.state.value.error);assertNull(c.state.value.scope);retained()
                record("late-ineligible",JSONObject().put("replacementProfilePreserved",true).put("noLateErrorPublication",true).put("oldProofRetired",true).put("pairingPreserved",true))
            } finally {release.countDown();pending.cancelAndJoin()}}
    }
}
