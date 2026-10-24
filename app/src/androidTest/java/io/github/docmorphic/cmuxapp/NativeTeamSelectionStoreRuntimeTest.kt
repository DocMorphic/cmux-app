package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey

/** Real encrypted snapshots and completion code; user publication uses an explicit controlled seam. */
class NativeTeamSelectionStoreRuntimeTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name:String;private lateinit var alias:String;private lateinit var store:NativeCredentialStore
    private val environment="https://fixture.example/api/v1/#${NativeAccount.PROJECT_ID}"
    private val user=NativeSignInUser("replacement-user","Fixture person","fixture@example.test",null)
    private fun cold()=NativeCredentialStore.withKeyProvider(context,name,alias)
    private fun cache(value:NativeCredentialStore=store)=NativeAccountProfileCache(value::load,value::update)
    private fun controller(value:NativeCredentialStore=store)=NativeAccountTeams({"fixture-access"},value::taskSession,
        "https://fixture.example/api/v1/".toHttpUrl(),cache=cache(value))
    private fun seed() {
        store.update{it.put("task_session","old-login").put("access_token","fixture-access").put("refresh_token","fixture-refresh")
            .put("pairings",JSONArray().put("fixture-pairing"))
            NativeAccountProfileCache.writeVerifiedUser(it,"old-login",environment,user.copy(id="old-user"))}
        cache().save("old-login",environment,NativeAccountTeamsState("old-user",listOf(NativeTeam("two","Two")),"two"))
        // Seed an actual old-version encrypted snapshot. A current store.update would
        // immediately migrate it during save, so it cannot produce this upgrade input.
        val legacy=store.requireReadable()!!.apply{remove(NativeTeamSelectionStore.KEY)}
        val key=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey(alias,null) as SecretKey
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key)}
        val encrypted=android.util.Base64.encodeToString(cipher.iv+cipher.doFinal(legacy.toString().toByteArray()),android.util.Base64.NO_WRAP)
        check(context.getSharedPreferences(name,0).edit().putString("state",encrypted).commit())
        assertFalse(cold().requireReadable()!!.has(NativeTeamSelectionStore.KEY))
    }
    private fun record(case:String,values:JSONObject) {
        val root=cold().requireReadable()!!;assertEquals("fixture-pairing",root.getJSONArray("pairings").getString(0))
        val cipher=context.getSharedPreferences(name,0).getString("state",null)!!
        assertFalse(cipher.contains("fixture-refresh"));assertFalse(cipher.contains("Fixture person"));assertFalse(cipher.contains("fixture-pairing"))
        File(context.getExternalFilesDir(null),"team-choice").apply{mkdirs()}.resolve("$case.json")
            .writeText(values.put("fixtureOnly",true).put("encryptedStoreUsed",true).put("pairingPreserved",true).toString(2))
    }
    @Before fun setup(){check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        name="team-choice-fixture-"+UUID.randomUUID();alias="cmux_team_choice_fixture_"+UUID.randomUUID()
        store=NativeCredentialStore.withKeyProvider(context,name,alias);seed()}
    @After fun cleanup(){runBlocking{withTimeout(5000){while(store.authWork.activeCount>0)delay(5)}}
        store.clear();context.deleteSharedPreferences(name);KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}

    @Test fun newReceiptMigratesLegacyChoiceBeforeDiscardingMembershipAcrossEncryptedReconstruction() {
        store.update{it.put("task_session","new-login")}
        val reader=cold();assertFalse(reader.requireReadable()!!.has(NativeAccountProfileCache.KEY))
        controller(reader).use{assertEquals("two",it.state.value.selectedTeamId);assertTrue(it.state.value.teams.isEmpty())
            assertNull(it.state.value.scope);assertFalse(it.hasLoadedMembership);assertNull(it.state.value.userId)}
        record("receipt-migration",JSONObject().put("legacyChoiceRetained",true).put("oldMembershipDiscarded",true).put("coldScopeIsNil",true))
    }
    @Test fun failedUserReadAfterRealCompletionTokenWriteRetainsChoiceAndPendingSession()=runBlocking<Unit> {
        val fetching=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val flow=NativeSignInCompletion(store::load,store::update,{fetching.complete(Unit);release.await();throw IOException("Fixture unavailable")},
            environment,store.accountStateLock,work=store.authWork)
        val attempt=async(Dispatchers.Default){runCatching{flow.signIn{it("new-access","new-refresh")}}}
        try{withTimeout(5000){fetching.await()};controller(cold()).use{assertEquals("two",it.state.value.selectedTeamId)
                assertTrue(it.state.value.teams.isEmpty());assertNull(it.state.value.scope)}
            release.complete(Unit);assertTrue(withTimeout(5000){attempt.await()}.isFailure)
            assertTrue(NativeSignInCompletion.isPending(cold().load()));assertEquals("new-refresh",cold().requireReadable()!!.getString("refresh_token"))
            assertEquals("two",cache(cold()).rememberedSelection(environment))
            record("failed-user-read",JSONObject().put("realCompletionTokenWrite",true).put("controlledUserFetchFailure",true)
                .put("pendingSessionRetained",true).put("rememberedChoiceRetained",true).put("coldScopeIsNil",true))
        }finally{release.complete(Unit);attempt.cancelAndJoin()}
    }
    @Test fun verifiedNewPrincipalKeepsPreferenceWithoutBorrowingOldMembership()=runBlocking<Unit> {
        val flow=NativeSignInCompletion(store::load,store::update,{user},environment,store.accountStateLock,
            onPublished={_,_->throw IOException("Fixture team refresh unavailable")},work=store.authWork)
        flow.signIn{it("new-access","new-refresh")}
        val reader=cold();val card=cache(reader).read(reader.taskSession()!!,environment)!!
        assertEquals(user.id,card.userId);assertEquals("two",card.selectedTeamId);assertTrue(card.teams.isEmpty());assertNull(card.scope)
        controller(reader).use{assertEquals("two",it.state.value.selectedTeamId);assertNull(it.state.value.scope);assertFalse(it.hasLoadedMembership)}
        record("new-principal",JSONObject().put("realCompletionPublication",true).put("controlledUserAndTeamSeams",true)
            .put("rememberedChoiceRetained",true).put("oldMembershipDiscarded",true).put("coldScopeIsNil",true))
    }
    @Test fun explicitNilSurvivesColdStorageWithoutResurrectingLegacyCardChoice() {
        store.update{NativeTeamSelectionStore.write(it,environment,null)}
        val reader=cold();assertEquals("two",reader.requireReadable()!!.getJSONObject(NativeAccountProfileCache.KEY).getString("selected"))
        assertNull(cache(reader).rememberedSelection(environment));controller(reader).use{assertNull(it.state.value.selectedTeamId);assertNull(it.state.value.scope)}
        store.update{it.put("task_session","replacement")};assertNull(cache(cold()).rememberedSelection(environment))
        record("explicit-nil",JSONObject().put("legacyCardStillHeldChoice",true).put("nilSurvivesReconstruction",true).put("coldScopeIsNil",true))
    }
    @Test fun environmentSwitchClearsRememberedChoiceBeforeColdExposureAndCannotReviveItOnReturn() {
        val other="https://other.example/api/v1/#other"
        assertNull(cache(cold()).rememberedSelection(other));assertNull(cache(cold()).rememberedSelection(environment))
        controller(cold()).use{assertNull(it.state.value.selectedTeamId);assertNull(it.state.value.scope)}
        assertEquals("fixture-refresh",cold().requireReadable()!!.getString("refresh_token"))
        record("environment-reset",JSONObject().put("choiceResetOnly",true).put("switchBackCannotReviveChoice",true)
            .put("fullAuthProjectResetNotExercised",true).put("coldScopeIsNil",true))
    }
    @Test fun readableEmptyCredentialRestoreClearsAStandaloneChoiceWithoutChangingPairings()=runBlocking<Unit> {
        store.update{NativeTeamSelectionStore.migrate(it)
            it.put("access_token","").put("refresh_token","").remove("task_session")
            it.remove(NativeAuthUserCache.KEY);it.remove(NativeAccountProfileCache.KEY)}
        assertEquals("two",cache(cold()).rememberedSelection(environment))
        val account=NativeAccount(cold(),NativeOAuthClient(NativeAccount.PROJECT_ID,NativeAccount.PUBLISHABLE_KEY),NativeOAuthBrowser(),
            refreshOverride={error("Empty-token fixture must not refresh")})
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try{account.sessionRestore.start(scope);withTimeout(5000){account.sessionRestore.awaitBootstrapped()}
            assertEquals(NativeAuthState(),account.sessionRestore.state.value);assertNull(cache(cold()).rememberedSelection(environment))
            assertFalse(cold().requireReadable()!!.has(NativeTeamSelectionStore.KEY))
            record("empty-credential-clear",JSONObject().put("realAccountRestore",true).put("readableEmptyStoreClearsChoice",true)
                .put("noProviderExchange",true))
        }finally{scope.cancel();scope.coroutineContext[Job]!!.join()}
    }
}
