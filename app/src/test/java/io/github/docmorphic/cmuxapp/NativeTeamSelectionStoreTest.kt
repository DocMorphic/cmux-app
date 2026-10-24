package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Selection is a presentation preference; only fresh membership can authorize a connection. */
class NativeTeamSelectionStoreTest {
    private val environment="https://fixture.example/api/v1/#${NativeAccount.PROJECT_ID}"
    private val user=NativeSignInUser("user","Fixture","fixture@example.test",null)
    private inner class Store {
        val lock=Any()
        var value=JSONObject().put("task_session","login").put("refresh_token","fixture-refresh")
            .put("pairings",JSONArray().put("retain"))
        val cache=NativeAccountProfileCache(::load,::update)
        @Synchronized fun load()=JSONObject(value.toString())
        @Synchronized fun update(transform:(JSONObject)->Unit) {
            val next=load();transform(next);NativeAccountProfileCache.prune(next);value=next
        }
        fun legacy(selected:Any?=" two ") {
            cache.save("login",environment,NativeAccountTeamsState("user",listOf(NativeTeam("two","Two")),"two"))
            value.remove(NativeTeamSelectionStore.KEY)
            value.getJSONObject(NativeAccountProfileCache.KEY).put("selected",selected ?: JSONObject.NULL)
        }
        fun choice()=NativeTeamSelectionStore.read(load(),environment)
        fun preserved(){assertEquals("retain",load().getJSONArray("pairings").getString(0))}
    }
    private fun controller(store:Store)=NativeAccountTeams({"fixture-access"},
        {store.load().optString("task_session").takeIf{it.isNotBlank()}},"https://fixture.example/api/v1/".toHttpUrl(),cache=store.cache)
    private fun completion(store:Store,fetch:suspend(String)->NativeSignInUser,
        published:suspend(String,NativeSignInUser)->Unit={_,_->})=NativeSignInCompletion(
        store::load,store::update,fetch,environment,store.lock,onPublished=published)

    @Test fun legacyReadIsNormalizedAndDoesNotMutateTheRoot() {
        val store=Store();store.legacy();val before=store.load().toString()
        assertEquals("two",store.choice());assertEquals("two",store.cache.rememberedSelection(environment))
        assertEquals(before,store.load().toString());store.preserved()
    }
    @Test fun aNewReceiptMigratesChoiceBeforePruningOldMembership() {
        val store=Store();store.legacy()
        store.update{it.put("task_session","new-login")}
        assertFalse(store.load().has(NativeAccountProfileCache.KEY));assertEquals("two",store.choice())
        controller(store).use{assertEquals("two",it.state.value.selectedTeamId);assertNull(it.state.value.userId)
            assertTrue(it.state.value.teams.isEmpty());assertNull(it.state.value.scope);assertFalse(it.hasLoadedMembership)}
        store.preserved()
    }
    @Test fun verifiedPublicationForANewReceiptAndPrincipalKeepsChoiceWithoutRowsOrScope() {
        val store=Store();store.legacy();store.update{it.put("task_session","replacement")}
        store.update{NativeAccountProfileCache.writeVerifiedUser(it,"replacement",environment,user.copy(id="new-user"))}
        val card=store.cache.read("replacement",environment)!!
        assertEquals("two",card.selectedTeamId);assertEquals("new-user",card.userId);assertTrue(card.teams.isEmpty())
        assertTrue(card.cached);assertNull(card.scope);store.preserved()
    }
    @Test fun missingOrCorruptCardDoesNotEraseIndependentChoice() {
        val store=Store();store.legacy();store.update{NativeTeamSelectionStore.migrate(it);it.remove(NativeAccountProfileCache.KEY)}
        store.update{it.put(NativeAccountProfileCache.KEY,"corrupt")}
        assertNull(store.cache.read("login",environment));assertEquals("two",store.choice())
        controller(store).use{assertEquals("two",it.state.value.selectedTeamId);assertNull(it.state.value.scope)}
    }
    @Test fun legacyChoiceDoesNotDependOnValidPresentationRows() {
        val store=Store();store.legacy()
        store.value.getJSONObject(NativeAccountProfileCache.KEY).put("teams",false).put("user",false).put("name",42)
        assertNull(store.cache.read("login",environment));store.update{it.put("task_session","next")}
        assertEquals("two",store.choice());assertFalse(store.load().has(NativeAccountProfileCache.KEY))
    }
    @Test fun malformedLegacyChoiceDoesNotInvalidateOtherwiseReadableCard() {
        val store=Store();store.legacy(JSONObject().put("id","two"))
        val card=store.cache.read("login",environment)!!
        assertNull(card.selectedTeamId);assertTrue(card.cached);assertNull(card.scope);assertEquals(1,card.teams.size)
    }
    @Test fun unknownLegacyVersionAndInvalidEnvironmentCannotBeMigrated() {
        for(change in listOf<(JSONObject)->Unit>({it.put("version",2)},{it.put("environment",false)},{it.put("environment","")})) {
            val store=Store();store.legacy();change(store.value.getJSONObject(NativeAccountProfileCache.KEY))
            store.update{it.put("task_session","next")};assertNull(store.choice());assertFalse(store.load().has(NativeTeamSelectionStore.KEY))
        }
    }
    @Test fun explicitNilOverridesLegacyChoiceAcrossReconstruction() {
        val store=Store();store.legacy();store.update{NativeTeamSelectionStore.write(it,environment,null)}
        assertEquals(" two ",store.load().getJSONObject(NativeAccountProfileCache.KEY).getString("selected"))
        assertNull(store.choice());NativeTeamSelectionStore.migrate(store.value);assertNull(store.choice())
        controller(store).use{assertNull(it.state.value.selectedTeamId);assertNull(it.state.value.scope)}
    }
    @Test fun malformedIndependentRecordNeverFallsBackToLegacyChoice() {
        val bad=listOf<Any>("corrupt",JSONObject().put("version",2).put("environment",environment).put("selected","two"),
            JSONObject().put("version",1).put("environment",environment).put("selected",42))
        for(record in bad){val store=Store();store.legacy();store.value.put(NativeTeamSelectionStore.KEY,record)
            assertNull(store.choice());store.update{NativeTeamSelectionStore.migrate(it)};assertNull(store.choice())}
    }
    @Test fun ownedSelectionCanBeWrittenWithoutAnyPresentationCard() {
        val store=Store();store.cache.updateSelection("login",environment,"user","\u0085two\u00a0")
        assertEquals("two",store.choice());assertFalse(store.load().has(NativeAccountProfileCache.KEY))
        store.cache.updateSelection("login",environment,"user",null);assertNull(store.choice());store.preserved()
    }
    @Test fun rejectedOldWriterCannotOverwriteOrClearNewChoice() {
        val store=Store();store.legacy();store.update{it.put("task_session","next");NativeTeamSelectionStore.write(it,environment,"next-choice")}
        val before=store.load().toString();store.cache.updateSelection("login",environment,"user",null)
        store.cache.save("login",environment,NativeAccountTeamsState("user",emptyList(),"old"));store.cache.remove("login")
        assertEquals(before,store.load().toString());assertEquals("next-choice",store.choice())
    }
    @Test fun retiringOnlyTheProfileCardPreservesTheChoice() {
        val store=Store();store.legacy();store.cache.remove("login")
        assertFalse(store.load().has(NativeAccountProfileCache.KEY));assertEquals("two",store.choice());store.preserved()
    }
    @Test fun projectSwitchClearsChoiceBeforeExposureAndSwitchBackDoesNotReviveIt() {
        val store=Store();store.legacy();val before=store.load().toString();val other="https://other.example/#other"
        assertNull(store.cache.read("login",other));assertEquals(before,store.load().toString())
        assertNull(store.cache.rememberedSelection(other));assertNull(store.cache.rememberedSelection(environment))
        assertNull(store.choice());assertEquals("fixture-refresh",store.load().getString("refresh_token"));store.preserved()
    }
    @Test fun localLogoutClearsBothLegacyAndIndependentChoiceAndPreservesPairings() {
        for(migrated in listOf(false,true)){val store=Store();store.legacy();if(migrated)store.update{NativeTeamSelectionStore.migrate(it)}
            store.update(NativeLocalLogout::retire);assertNull(store.choice())
            assertFalse(store.load().has(NativeTeamSelectionStore.KEY));assertFalse(store.load().has(NativeAccountProfileCache.KEY))
            controller(store).use{assertNull(it.state.value.selectedTeamId);assertNull(it.state.value.scope)};store.preserved()}
    }
    @Test fun longChoiceAndFoundationWhitespaceRoundTripIndependentlyOfCardBounds() {
        val store=Store();val selected="x".repeat(513)
        store.cache.updateSelection("login",environment,"user","\u2007$selected\u202f")
        assertEquals(selected,store.choice());controller(store).use{assertEquals(selected,it.state.value.selectedTeamId);assertNull(it.state.value.scope)}
        store.cache.updateSelection("login",environment,"user","\u0085\u00a0\n");assertNull(store.choice())
        store.cache.updateSelection("login",environment,"user","\u200b");assertNull(store.choice())
    }
    @Test fun tokenWriteAndFailedUserFetchKeepChoiceButCannotBorrowMembership()=runBlocking<Unit> {
        val store=Store();store.legacy();val fetching=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val flow=completion(store,{fetching.complete(Unit);release.await();throw IOException("Fixture user unavailable")})
        val attempt=async{runCatching{flow.signIn{it("new-access","new-refresh")}}}
        try{fetching.await();assertEquals("two",store.choice());assertFalse(store.load().has(NativeAccountProfileCache.KEY))
            controller(store).use{assertEquals("two",it.state.value.selectedTeamId);assertNull(it.state.value.scope)}
            release.complete(Unit);assertTrue(attempt.await().isFailure);assertEquals("two",store.choice())
            assertEquals("new-refresh",store.load().getString("refresh_token"));store.preserved()
        }finally{release.complete(Unit);attempt.cancelAndJoin()}
    }
    @Test fun canceledUnpublishedExchangeDoesNotClearPreferenceUntilActualLogout()=runBlocking<Unit> {
        val store=Store();store.legacy();val written=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val flow=completion(store,{user})
        val attempt=async{flow.signIn{it("new-access","new-refresh");written.complete(Unit);release.await()}}
        try{written.await();attempt.cancelAndJoin();assertEquals("two",store.choice());assertEquals("",store.load().getString("refresh_token"))
            store.update(NativeLocalLogout::retire);assertNull(store.choice());store.preserved()
        }finally{release.complete(Unit);attempt.cancelAndJoin()}
    }
    @Test fun successfulSignInWithFailedMembershipKeepsChoiceForThePublishedNewUser()=runBlocking<Unit> {
        val store=Store();store.legacy()
        val flow=completion(store,{user.copy(id="new-user")},{_,_->throw IOException("Fixture membership unavailable")})
        flow.signIn{it("new-access","new-refresh")}
        val owner=store.load().getString("task_session");val card=store.cache.read(owner,environment)!!
        assertEquals("new-user",card.userId);assertEquals("two",card.selectedTeamId);assertTrue(card.teams.isEmpty())
        assertNull(card.scope);assertFalse(NativeSignInCompletion.isPending(store.load()));store.preserved()
    }
}
