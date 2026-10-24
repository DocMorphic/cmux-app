package io.github.docmorphic.cmuxapp

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
import java.util.concurrent.atomic.AtomicInteger

/** Real account/Keystore/HTTP with isolated SDK reply fixtures; never a provider or physical device. */
class NativeSdkModelRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val owners=mutableListOf<AutoCloseable>();private val plans=mutableListOf<Plan>()
    private fun client(server:MockWebServer)=NativeOAuthClient(NativeAccount.PROJECT_ID,NativeAccount.PUBLISHABLE_KEY,server.url("/"))
    private inner class Plan:Dispatcher() {
        @Volatile var user=JSONObject().put("id","model-user").put("display_name"," Model Name ")
            .put("primary_email","").put("selected_team",JSONObject().put("id","one"))
        @Volatile var rows="{\"items\":[{\"id\":\"one\",\"display_name\":\"One\"}]}"
        val failTeams=AtomicInteger();val teamReads=AtomicInteger();val userReads=AtomicInteger()
        @Volatile var hold=false;val entered=CountDownLatch(1);val release=CountDownLatch(1)
        override fun dispatch(request:RecordedRequest):MockResponse {
            if(request.method=="GET" && request.path=="/api/v1/users/me"){userReads.incrementAndGet();return MockResponse().setBody(user.toString())}
            if(request.method=="GET" && request.path=="/api/v1/teams?user_id=me") {
                teamReads.incrementAndGet();val body=rows
                if(hold){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
                if(failTeams.getAndUpdate{if(it>0)it-1 else 0}>0)return MockResponse().setResponseCode(503)
                return MockResponse().setBody(body)
            }
            return MockResponse().setResponseCode(599)
        }
    }
    private data class Binding(val account:NativeAccount,val teams:NativeAccountTeams,val recovery:NativeTeamScopeRecovery,val plan:Plan)
    private fun binding(server:MockWebServer,configure:(Plan)->Unit={}):Binding {
        val plan=Plan().also{configure(it);plans+=it;server.dispatcher=it}
        val user=NativeSignInUser.decode(plan.user)
        val access="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject()
            .put("exp",System.currentTimeMillis()/1000+3600).toString().toByteArray())+".fixture"
        store.update{it.put("task_session","model-login").put("access_token",access).put("refresh_token","fixture-model-refresh")
            .put("fixture_pairing_marker","retain-model-pairing");NativeAuthUserCache.write(it,client(server).accountEnvironment,user)}
        val account=NativeAccount(store,client(server),NativeOAuthBrowser())
        val teams=NativeAccountTeams(account,store,server.url("/api/v1/")).also{owners+=it}
        account.onSignedIn={owner,value->teams.refreshAfterSignIn(owner,value)}
        val recovery=NativeAccountTeamRecovery(scope,account,teams,store.accountStateLock,jitter={.5}).also{owners+=it}
        return Binding(account,teams,recovery.coordinator,plan)
    }
    private suspend fun boot(b:Binding){b.account.sessionRestore.start(scope);withTimeout(10000){b.account.sessionRestore.awaitBootstrapped()}}
    private fun cold()=NativeCredentialStore.withKeyProvider(context,name,alias)
    private fun record(case:String,values:JSONObject) {
        assertEquals("retain-model-pairing",cold().requireReadable()!!.getString("fixture_pairing_marker"))
        val cipher=context.getSharedPreferences(name,0).getString("state",null)!!;assertFalse(cipher.contains("fixture-model-refresh"))
        File(context.getExternalFilesDir(null),"sdk-model").apply{mkdirs()}.resolve("$case.json")
            .writeText(values.put("fixtureOnly",true).put("encryptedStoreUsed",true).put("pairingMarkerPreserved",true).toString(2))
    }
    @Before fun setup(){check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="sdk-model-fixture-"+UUID.randomUUID();alias="cmux_sdk_model_fixture_"+UUID.randomUUID();store=NativeCredentialStore.withKeyProvider(context,name,alias)}
    @After fun cleanup(){plans.forEach{it.release.countDown()};owners.asReversed().forEach{it.close()};scope.cancel()
        runBlocking{scope.coroutineContext[Job]!!.join();withTimeout(5000){while(store.authWork.activeCount>0)delay(5)}}
        store.clear();context.deleteSharedPreferences(name);KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}

    @Test fun rawProfilesMetadataAndDuplicateEmptyRowsSurviveVerifiedBootAndEncryptedReconstruction()=runBlocking<Unit> {
        MockWebServer().use{server->val b=binding(server){p->p.user.put("profile_image_url"," image ")
                .put("client_read_only_metadata",JSONObject().put("cmuxReviewDemoContent",true))
            p.rows="{\"items\":[{\"id\":\"one\",\"display_name\":\" First \"},{\"id\":\"one\",\"display_name\":false},{\"display_name\":\"Empty ID\"}]}"}
            boot(b);val user=b.account.sessionRestore.state.value.user!!
            assertEquals(" Model Name ",user.name);assertEquals("",user.email);assertEquals(" image ",user.profileImageUrl);assertTrue(user.demonstrationContentEnabled)
            assertEquals(listOf("one","one",""),b.teams.state.value.teams.map{it.id});assertEquals(listOf(" First ","","Empty ID"),b.teams.state.value.teams.map{it.name})
            assertEquals("one",b.teams.state.value.scope?.teamId)
            val reader=cold();val account=NativeAccount(reader,client(server),NativeOAuthBrowser());assertEquals(user,account.sessionRestore.state.value.user)
            NativeAccountTeams(account,reader,server.url("/api/v1/")).use{c->assertTrue(c.state.value.cached);assertEquals(b.teams.state.value.teams,c.state.value.teams);assertNull(c.state.value.scope)}
            record("raw-projections",JSONObject().put("rawProfileAndMetadataPreserved",true).put("duplicateAndEmptyRowsRetained",true).put("coldScopeIsNil",true))}
    }
    @Test fun malformedSuccessfulListLoadsEmptyAndRetiresProofWithoutStartingRecovery()=runBlocking<Unit> {
        MockWebServer().use{server->val b=binding(server);boot(b);val proof=b.teams.captureContinuation(b.teams.state.value.scope!!)!!
            b.plan.rows="private-malformed-team-body";b.account.sessionRestore.revalidate()
            assertTrue(b.teams.hasLoadedMembership);assertTrue(b.teams.state.value.teams.isEmpty());assertNull(b.teams.state.value.scope)
            assertFalse(b.teams.allowsContinuation(proof));assertFalse(b.recovery.hasPendingRecovery);assertEquals("one",b.teams.state.value.selectedTeamId)
            record("malformed-empty",JSONObject().put("successfulMalformedListCountsAsLoadedEmpty",true).put("oldProofRetired",true).put("recoveryScheduled",false))}
    }
    @Test fun emptyPublishedUserHasLoadedMembershipWithoutIdentityAndRejectsOldEmptyGenerationReplies()=runBlocking<Unit> {
        MockWebServer().use{server->val b=binding(server){it.user.put("id","")};boot(b)
            assertTrue(b.account.sessionRestore.state.value.authenticated);assertNull(b.account.sessionRestore.identity());assertNotNull(b.account.sessionRestore.verifiedUser())
            assertTrue(b.teams.hasLoadedMembership);assertNull(b.teams.state.value.scope);assertFalse(b.recovery.hasPendingRecovery)
            b.plan.hold=true;val old=async(Dispatchers.Default){b.account.sessionRestore.revalidate()}
            try{assertTrue(withContext(Dispatchers.IO){b.plan.entered.await(5,TimeUnit.SECONDS)})
                val generation=b.account.sessionRestore.sessionGeneration;val user=b.account.sessionRestore.verifiedUser()!!
                b.account.sessionRestore.publishedSignIn(user);assertTrue(b.account.sessionRestore.sessionGeneration>generation)
                store.update{NativeAccountProfileCache.writeVerifiedUser(it,"model-login",client(server).accountEnvironment,user)}
                b.teams.reconcileLogin();b.plan.release.countDown();withTimeout(5000){old.await()}
                assertNull(b.account.sessionRestore.identity());assertNull(b.teams.state.value.scope);assertFalse(b.teams.hasLoadedMembership);assertTrue(b.teams.state.value.teams.isEmpty())
                record("empty-user-generation",JSONObject().put("emptyUserAuthenticatedWithoutConnectionIdentity",true).put("oldGenerationReplyRejected",true).put("controlledReplacementPublication",true))
            }finally{b.plan.release.countDown();old.cancelAndJoin()}}
    }
    @Test fun emptyUserMembershipFailureRecoversThroughAuthGenerationWithoutInventingScope()=runBlocking<Unit> {
        MockWebServer().use{server->val b=binding(server){it.user.put("id","");it.failTeams.set(1)};boot(b)
            assertTrue(b.recovery.hasPendingRecovery);assertFalse(b.teams.hasLoadedMembership);assertNull(b.account.sessionRestore.identity())
            withTimeout(8000){while(!b.teams.hasLoadedMembership || b.recovery.hasPendingRecovery)delay(20)}
            assertEquals(2,b.plan.teamReads.get());assertEquals(6,b.plan.userReads.get());assertNull(b.teams.state.value.scope)
            record("empty-user-recovery",JSONObject().put("authGenerationOwnsRecovery",true).put("membershipReads",2).put("connectionScopeIsNil",true))}
    }
    @Test fun originalLargeSuccessfulUserAndMembershipPayloadsDoNotHitAndroidOnlyCaps()=runBlocking<Unit> {
        MockWebServer().use{server->val b=binding(server){p->p.user.put("id","u".repeat(129)).put("display_name","n".repeat(513)).put("padding","x".repeat(270_000))
            p.rows=JSONObject().put("items",JSONArray((0 until 4097).map{JSONObject().put("id","t$it").put("display_name","N")}))
                .put("padding","x".repeat(2_100_000)).toString()}
            boot(b);assertEquals(129,b.account.sessionRestore.identity()!!.userId.length);assertEquals(4097,b.teams.state.value.teams.size)
            assertEquals("t0",b.teams.state.value.scope?.teamId);assertEquals(513,b.account.sessionRestore.state.value.user!!.name!!.length)
            val reader=cold();val account=NativeAccount(reader,client(server),NativeOAuthBrowser())
            NativeAccountTeams(account,reader,server.url("/api/v1/")).use{assertEquals(4097,it.state.value.teams.size);assertNull(it.state.value.scope)}
            record("large-models",JSONObject().put("teamRows",4097).put("teamResponseBytes",b.plan.rows.toByteArray().size)
                .put("userResponseBytes",b.plan.user.toString().toByteArray().size).put("coldScopeIsNil",true))}
    }
}
