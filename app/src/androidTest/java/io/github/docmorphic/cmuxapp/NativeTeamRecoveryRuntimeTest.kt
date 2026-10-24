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

/** Process account binding, actual Keystore and local HTTP; no physical account or provider. */
class NativeTeamRecoveryRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val owners=mutableListOf<AutoCloseable>()
    private val user=NativeSignInUser("account-a","Account A",null,"one")
    private fun client(server:MockWebServer)=NativeOAuthClient("fixture-project","fixture-key",server.url("/"))
    private fun userReply()=MockResponse().setBody("{\"id\":\"account-a\",\"display_name\":\"Account A\",\"selected_team\":{\"id\":\"one\"}}")
    private fun teamReply()=MockResponse().setBody("{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"}]}")
    private fun rate()=MockResponse().setResponseCode(429).setHeader("Content-Type","text/html").setHeader("Retry-After","2")
        .setBody("<html>private-body-canary"+"x".repeat(300_000))
    private data class Binding(val account:NativeAccount,val teams:NativeAccountTeams,val recovery:NativeTeamScopeRecovery)
    private fun binding(server:MockWebServer):Binding {
        val token="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject().put("exp",System.currentTimeMillis()/1000+3600).toString().toByteArray())+".fixture"
        store.update {root->root.put("task_session","login").put("access_token",token).put("refresh_token","fixture-refresh")
            .put("fixture_pairing_marker","retain");NativeAuthUserCache.write(root,client(server).accountEnvironment,user)}
        val account=NativeAccount(store,client(server),NativeOAuthBrowser())
        val teams=NativeAccountTeams(account,store,server.url("/api/v1/")).also {owners+=it}
        account.onSignedIn={login,value->teams.refreshAfterSignIn(login,value)}
        val owner=NativeAccountTeamRecovery(scope,account,teams,store.accountStateLock,jitter={.5}).also {owners+=it}
        return Binding(account,teams,owner.coordinator)
    }
    private fun MockWebServer.refreshReply(reply:MockResponse){teamUsers(3){userReply()};enqueue(reply)}
    private suspend fun boot(b:Binding) {b.account.sessionRestore.start(scope);withTimeout(5000){b.account.sessionRestore.awaitBootstrapped()}}
    private suspend fun recovered(b:Binding) {withTimeout(8000){while(!b.teams.hasLoadedMembership || b.recovery.hasPendingRecovery)delay(25)}}
    private fun record(case:String,data:JSONObject) {
        assertEquals("retain",store.requireReadable()!!.getString("fixture_pairing_marker"))
        File(context.getExternalFilesDir(null),"team-recovery").apply{mkdirs()}.resolve("$case.json")
            .writeText(data.put("fixtureOnly",true).put("pairingPreserved",true).toString(2))
    }
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="team-recovery-fixture-"+UUID.randomUUID();alias="cmux_team_recovery_fixture_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(context,name,alias)
    }
    @After fun cleanup() {owners.asReversed().forEach{it.close()};scope.cancel();runBlocking{scope.coroutineContext[Job]!!.join()}
        store.clear();context.deleteSharedPreferences(name);KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}
    @Test fun missingMembershipRetriesUserAndTeamsWithoutUiPollingThenStopsWhenHealthy()=runBlocking<Unit> {
        MockWebServer().use {server->val b=binding(server)
            server.refreshReply(MockResponse().setResponseCode(503));server.refreshReply(teamReply())
            val start=android.os.SystemClock.elapsedRealtime();boot(b);assertTrue(b.recovery.hasPendingRecovery)
            recovered(b);val elapsed=android.os.SystemClock.elapsedRealtime()-start
            assertTrue(elapsed>=1900);assertNotNull(b.teams.state.value.scope);assertEquals(8,server.requestCount)
            delay(2200);assertEquals(8,server.requestCount)
            record("ordinary-recovery",JSONObject().put("httpRequests",8).put("elapsedMillis",elapsed).put("healthyExtraRequests",0))}
    }
    @Test fun oversizedTeamThrottleBlocksSignalsThenTheOwningLoopRecovers()=runBlocking<Unit> {
        MockWebServer().use {server->val b=binding(server)
            server.refreshReply(rate());server.refreshReply(teamReply())
            boot(b);assertTrue(b.recovery.hasRateLimit);repeat(10){b.recovery.recoverIfNeeded()};assertEquals(4,server.requestCount)
            recovered(b);assertEquals(8,server.requestCount);assertFalse(b.recovery.hasRateLimit)
            record("membership-throttle",JSONObject().put("suppressedSignals",10).put("httpRequests",8).put("oversizedHtmlClassified",true))}
    }
    @Test fun oversizedUserThrottlePreservesEncryptedCacheAndRecoversThroughTheSameBinding()=runBlocking<Unit> {
        MockWebServer().use {server->val b=binding(server);val cipher=context.getSharedPreferences(name,0).getString("state",null)
            server.teamUsers{rate()};server.refreshReply(teamReply());boot(b)
            assertTrue(b.account.sessionRestore.state.value.authenticated);assertTrue(b.recovery.hasPendingRecovery);assertTrue(b.recovery.hasRateLimit)
            assertEquals(cipher,context.getSharedPreferences(name,0).getString("state",null))
            b.recovery.recoverIfNeeded();assertEquals(1,server.requestCount);recovered(b);assertEquals(5,server.requestCount)
            record("user-throttle",JSONObject().put("ciphertextPreservedDuringFailure",true).put("httpRequests",5))}
    }
    @Test fun successfulEmptyMembershipCountsAsLoadedWithoutStartingARecoveryLoop()=runBlocking<Unit> {
        MockWebServer().use {server->val b=binding(server);server.refreshReply(MockResponse().setBody("{\"items\":[]}"))
            boot(b);assertTrue(b.teams.hasLoadedMembership);assertNull(b.teams.state.value.scope);assertFalse(b.recovery.hasPendingRecovery)
            b.recovery.recoverIfNeeded();delay(2200);assertEquals(4,server.requestCount)
            record("empty-loaded",JSONObject().put("loaded",true).put("scopeIsNil",true).put("extraRequests",0))}
    }
    @Test fun localSignOutRetiresThePendingLoopBeforeItCanMakeAnotherRequest()=runBlocking<Unit> {
        MockWebServer().use {server->val b=binding(server);server.refreshReply(MockResponse().setResponseCode(503))
            boot(b);assertTrue(b.recovery.hasPendingRecovery);assertTrue(b.account.signOut());assertFalse(b.recovery.hasPendingRecovery)
            delay(2200);assertEquals(4,server.requestCount);assertNull(store.taskSession())
            record("signout",JSONObject().put("pendingRetired",true).put("extraRequests",0))}
    }
    @Test fun freshVerifiedPublicationResetsAnOldThrottleBeforeNewMembershipRecovery()=runBlocking<Unit> {
        MockWebServer().use {server->val b=binding(server);server.refreshReply(rate().setHeader("Retry-After","900"))
            boot(b);assertTrue(b.recovery.hasRateLimit);val old=b.account.sessionRestore.identity()
            b.account.sessionRestore.publishedSignIn(user);assertFalse(b.recovery.hasRateLimit);assertNotEquals(old,b.account.sessionRestore.identity())
            server.teamUsers(2){userReply()};server.enqueue(MockResponse().setResponseCode(503));assertTrue(runCatching{b.teams.refreshAfterSignIn("login",user)}.isFailure)
            server.refreshReply(teamReply());val start=android.os.SystemClock.elapsedRealtime();recovered(b)
            val elapsed=android.os.SystemClock.elapsedRealtime()-start;assertTrue(elapsed>=1850);assertEquals(11,server.requestCount)
            record("fresh-generation",JSONObject().put("oldThrottleReset",true).put("providerNotExercised",true).put("elapsedMillis",elapsed).put("httpRequests",11))}
    }
    @Test fun defaultNetworkCallbackRechecksAHealthyBindingWithoutSendingAccountHttp()=runBlocking<Unit> {
        MockWebServer().use {server->val b=binding(server);server.refreshReply(teamReply());boot(b)
            val signalled=CompletableDeferred<Unit>()
            NativeTeamRecoveryNetwork(context,scope){b.recovery.recoverIfNeeded();signalled.complete(Unit)}.use {
                withTimeout(10000){signalled.await()};assertEquals(4,server.requestCount);assertFalse(b.recovery.hasPendingRecovery)
            }
            record("network-registration",JSONObject().put("validatedDefaultCallbackObserved",true).put("healthyExtraHttp",0).put("networkLossNotExercised",true))}
    }
}
