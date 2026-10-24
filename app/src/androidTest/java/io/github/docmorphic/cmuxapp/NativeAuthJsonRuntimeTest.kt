package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Original byte corpus on ART plus isolated real account/Keystore/loopback consumers. */
class NativeAuthJsonRuntimeTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val reference by lazy{JSONObject(instrumentation.context.assets.open("native-sdk-json-reference.json").bufferedReader().use{it.readText()})}
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val owners=mutableListOf<AutoCloseable>()
    private fun wire(name:String):ByteArray {
        val rows=reference.getJSONArray("wire");val row=(0 until rows.length()).map{rows.getJSONObject(it)}.single{it.getString("case")==name}
        return Base64.getDecoder().decode(row.getString("base64"))
    }
    private fun JSONObject.text(key:String)=opt(key).takeUnless{it===JSONObject.NULL} as? String
    private fun client(server:MockWebServer)=NativeOAuthClient(NativeAccount.PROJECT_ID,NativeAccount.PUBLISHABLE_KEY,server.url("/"))
    private inner class Plan:Dispatcher() {
        @Volatile var user=wire("valid");@Volatile var teams=wire("valid")
        val userReads=AtomicInteger();val teamReads=AtomicInteger()
        override fun dispatch(request:RecordedRequest):MockResponse {
            val body=when {
                request.method=="GET" && request.path=="/api/v1/users/me"->{userReads.incrementAndGet();user}
                request.method=="GET" && request.path=="/api/v1/teams?user_id=me"->{teamReads.incrementAndGet();teams}
                else->return MockResponse().setResponseCode(599)
            }
            return MockResponse().setBody(Buffer().write(body)).setHeader("Content-Type","text/plain; charset=us-ascii")
        }
    }
    private data class Binding(val account:NativeAccount,val teams:NativeAccountTeams,val recovery:NativeTeamScopeRecovery,val plan:Plan)
    private fun binding(server:MockWebServer,configure:(Plan)->Unit={}):Binding {
        val plan=Plan().also{configure(it);server.dispatcher=it}
        val access="fixture."+Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject()
            .put("exp",System.currentTimeMillis()/1000+3600).toString().toByteArray())+".fixture"
        store.update{it.put("task_session","wire-login").put("access_token",access).put("refresh_token","fixture-wire-refresh")
            .put("fixture_pairing_marker","retain-wire-pairing")
            NativeAccountProfileCache.writeVerifiedUser(it,"wire-login",client(server).accountEnvironment,NativeSignInUser("wire-user"," Cached Wire ",null,null))
            NativeTeamSelectionStore.write(it,client(server).accountEnvironment,"remembered")}
        val account=NativeAccount(store,client(server),NativeOAuthBrowser())
        val teams=NativeAccountTeams(account,store,server.url("/api/v1/")).also{owners+=it}
        account.onSignedIn={owner,user->teams.refreshAfterSignIn(owner,user)}
        val recovery=NativeAccountTeamRecovery(scope,account,teams,store.accountStateLock,jitter={.5}).also{owners+=it}
        return Binding(account,teams,recovery.coordinator,plan)
    }
    private suspend fun boot(b:Binding){b.account.sessionRestore.start(scope);withTimeout(8000){b.account.sessionRestore.awaitBootstrapped()}}
    private fun cold()=NativeCredentialStore.withKeyProvider(context,name,alias)
    private fun record(case:String,value:JSONObject) {
        assertEquals("retain-wire-pairing",cold().requireReadable()!!.getString("fixture_pairing_marker"))
        val cipher=context.getSharedPreferences(name,0).getString("state",null)!!;assertFalse(cipher.contains("fixture-wire-refresh"))
        File(context.getExternalFilesDir(null),"sdk-json").apply{mkdirs()}.resolve("$case.json")
            .writeText(value.put("fixtureOnly",true).put("encryptedFixtureUsed",true).put("pairingMarkerPreserved",true).toString(2))
    }
    @Before fun setup(){check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="sdk-json-fixture-"+UUID.randomUUID();alias="cmux_sdk_json_fixture_"+UUID.randomUUID();store=NativeCredentialStore.withKeyProvider(context,name,alias)}
    @After fun cleanup(){owners.asReversed().forEach{it.close()};scope.cancel();runBlocking{scope.coroutineContext[Job]!!.join()
        withTimeout(5000){while(store.authWork.activeCount>0)delay(5)}};store.clear();context.deleteSharedPreferences(name)
        KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}

    @Test fun all368OriginalWireProjectionsExecuteOnAndroidWithoutJsonTokenerDifferences() {
        val rows=reference.getJSONArray("wire");assertEquals(368,rows.length())
        for(i in 0 until rows.length()){val row=rows.getJSONObject(i);val label=row.getString("case")
            val parsed=runCatching{NativeAuthJson.objectValue(Base64.getDecoder().decode(row.getString("base64")))}
            assertEquals(label,!row.isNull("user"),parsed.isSuccess)
            if(parsed.isSuccess){val expected=row.getJSONObject("user");val user=runCatching{NativeSignInUser.decode(parsed.getOrThrow())}
                assertEquals(label,expected.getBoolean("eligible"),user.isSuccess)
                if(user.isSuccess){val value=user.getOrThrow();assertEquals(label,expected.getString("id"),value.id)
                    assertEquals(label,expected.text("name"),value.name);assertEquals(label,expected.text("email"),value.email)
                    assertEquals(label,expected.text("image"),value.profileImageUrl);assertEquals(label,expected.text("selected"),value.selectedTeam)
                    assertEquals(label,expected.getBoolean("demo"),value.demonstrationContentEnabled)}}
            val teams=parsed.getOrNull()?.let(NativeSdkTeamRows::decode).orEmpty();val expected=row.getJSONArray("teams")
            assertEquals(label,expected.length(),teams.size);teams.forEachIndexed{n,t->assertEquals(label,expected.getJSONObject(n).getString("id"),t.id)
                assertEquals(label,expected.getJSONObject(n).getString("name"),t.name)}
        }
        store.update{it.put("fixture_pairing_marker","retain-wire-pairing")}
        record("original-corpus",JSONObject().put("wireCases",368).put("foundation",reference.getJSONObject("foundation")))
    }
    @Test fun encodedUserAndTeamBodiesBootTheRealAccountAndReconstructEncryptedDisplayOnly()=runBlocking<Unit> {
        MockWebServer().use{server->val b=binding(server){it.user=wire("utf-16-be-bom");it.teams=wire("utf-32-be-bom")};boot(b)
            assertEquals("wire-user",b.account.sessionRestore.identity()?.userId);assertEquals("one",b.teams.state.value.scope?.teamId)
            assertEquals(3,b.plan.userReads.get());assertEquals(1,b.plan.teamReads.get())
            val reader=cold();val account=NativeAccount(reader,client(server),NativeOAuthBrowser())
            NativeAccountTeams(account,reader,server.url("/api/v1/")).use{assertTrue(it.state.value.cached);assertEquals("one",it.state.value.selectedTeamId);assertNull(it.state.value.scope)}
            record("encoded-http",JSONObject().put("userEncoding","UTF-16BE BOM").put("teamEncoding","UTF-32BE BOM")
                .put("contentTypeOverriddenByBytes",true).put("coldScopeIsNil",true))}
    }
    @Test fun trailingMaterialInASuccessfulListRetiresScopeAndProofAndDoesNotScheduleRecovery()=runBlocking<Unit> {
        MockWebServer().use{server->val b=binding(server);boot(b);val proof=b.teams.captureContinuation(b.teams.state.value.scope!!)!!
            b.plan.teams=wire("trailing-text");b.account.sessionRestore.revalidate()
            assertTrue(b.teams.hasLoadedMembership);assertTrue(b.teams.state.value.teams.isEmpty());assertNull(b.teams.state.value.scope)
            assertFalse(b.teams.allowsContinuation(proof));assertFalse(b.recovery.hasPendingRecovery)
            record("invalid-list",JSONObject().put("trailingMaterialRejected",true).put("loadedEmptyMembership",true).put("oldProofRetired",true))}
    }
    @Test fun duplicateEligibilityFlagsUseTheFirstValueAcrossActualRestoreAndRecovery()=runBlocking<Unit> {
        MockWebServer().use{server->val b=binding(server){it.user=wire("duplicate-anonymous-true-first")};boot(b)
            assertNull(b.account.sessionRestore.verifiedUser());assertNull(b.teams.state.value.scope);assertEquals(0,b.plan.teamReads.get())
            assertTrue(b.recovery.hasPendingRecovery);b.plan.user=wire("duplicate-anonymous-false-first");b.recovery.recoverIfNeeded()
            withTimeout(8000){while(!b.teams.hasLoadedMembership || b.recovery.hasPendingRecovery)delay(20)}
            assertNotNull(b.account.sessionRestore.verifiedUser());assertEquals("one",b.teams.state.value.scope?.teamId)
            assertEquals(4,b.plan.userReads.get());assertEquals(1,b.plan.teamReads.get())
            record("duplicate-flags",JSONObject().put("firstTrueDeniedMembership",true).put("firstFalseRecoveredMembership",true).put("httpRequests",5))}
    }
    @Test fun malformedSelectionExtensionCannotDiscardEncryptedAccountOrRememberedPreference() {
        MockWebServer().use{server->val b=binding(server)
            store.update{it.getJSONObject(NativeAuthUserCache.KEY).put("selected",JSONObject().put("id","unrelated-extension"))}
            val reader=cold();val account=NativeAccount(reader,client(server),NativeOAuthBrowser())
            assertEquals("wire-user",account.sessionRestore.state.value.user?.id);assertNull(account.sessionRestore.state.value.user?.selectedTeam)
            assertNull(account.sessionRestore.verifiedUser());assertEquals(0,server.requestCount)
            NativeAccountTeams(account,reader,server.url("/api/v1/")).use{assertTrue(it.state.value.cached)
                assertEquals("remembered",it.state.value.selectedTeamId);assertNull(it.state.value.scope)}
            assertNull(b.teams.state.value.scope)
            record("cache-extension",JSONObject().put("sharedAccountRetained",true).put("independentPreferenceRetained",true).put("networkRequests",0))}
    }
    @Test fun malformedPrimaryUserBytesFollowTheExistingNoUserClearWithoutLosingPairingOrKey()=runBlocking<Unit> {
        MockWebServer().use{server->val b=binding(server);boot(b);val proof=b.teams.captureContinuation(b.teams.state.value.scope!!)!!
            val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};val created=keys.getCreationDate(alias)
            b.plan.user=wire("invalid-utf8");b.account.sessionRestore.revalidate()
            assertEquals(NativeAuthState(),b.account.sessionRestore.state.value);assertFalse(b.teams.allowsContinuation(proof));b.teams.reconcileLogin()
            assertNull(b.teams.state.value.scope);assertNull(cold().taskSession());assertEquals(created,keys.getCreationDate(alias))
            record("invalid-primary-user",JSONObject().put("existingNoUserClearFollowed",true).put("oldProofRetired",true).put("existingEncryptionKeyRetained",true))}
    }
}
