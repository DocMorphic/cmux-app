package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NativeSessionRestoreTest {
    private val cached = NativeSignInUser("cached", "Cached Person", "cached@example.test", null)
    private val live = NativeSignInUser("live", "Live Person", "live@example.test", "team")
    private inner class Fixture(scope: TestScope, hint: Boolean = true, user: NativeSignInUser? = cached) {
        var epoch = 0L
        var savedHint = hint
        var cache = user
        var probeCalls=0;var userCalls=0;var teamCalls=0;var hookCalls=0;var clears=0;var saves=0
        var available: suspend () -> Boolean = { true }
        var probe: suspend () -> Boolean = { true }
        var fetch: suspend () -> NativeSignInUser? = { live }
        var refresh: suspend () -> String? = { "refresh" }
        var clear: suspend (String?) -> Boolean = { clears++;true }
        var teams: suspend (NativeSignInUser) -> Unit = { }
        val model=NativeSessionRestore(hint,user,{savedHint=it},{cache},{available()},
            {probeCalls++;probe()},{userCalls++;fetch()},{refresh()},{clear(it)},
            {cache=it;saves++},{teamCalls++;teams(it)},{hookCalls++},
            NativeAuthWork(nowMillis={scope.testScheduler.currentTime}),restoreTimeoutMillis=80,networkTimeoutMillis=600,externalEpoch={epoch})
        init { model.start(scope) }
    }
    @Test fun normalPrimingMatchesTheExecutedWholeOriginalSwiftModel() {
        val root=listOf(java.io.File("."),java.io.File("..")).first { java.io.File(it,"tests/fixtures/auth-priming-reference.json").isFile }
        val data=org.json.JSONObject(java.io.File(root,"tests/fixtures/auth-priming-reference.json").readText())
        val cases=data.getJSONArray("cases");var consumed=0
        for(index in 0 until cases.length()) {
            val row=cases.getJSONObject(index)
            if(listOf("clear","mock","fixture","autoLogin").any { row.getBoolean(it) })continue
            val value=NativeAuthState.primed(row.getBoolean("hasTokens"),if(row.getBoolean("cached"))cached else null)
            assertEquals(row.getBoolean("authenticated"),value.authenticated)
            assertEquals(row.getBoolean("restoring"),value.restoring)
            assertEquals(if(row.isNull("user"))null else row.getString("user"),value.user?.id);consumed++
        }
        assertEquals(4,consumed)
    }
    @Test fun verifiedUserPublishesBeforeMembershipAndMembershipFailureDoesNotUndoIt()=runTest {
        val gate=CompletableDeferred<Unit>();val f=Fixture(this,user=null)
        f.teams={assertTrue(f.model.state.value.authenticated);assertFalse(f.model.state.value.restoring);gate.await();throw IOException()}
        runCurrent();assertEquals(live,f.model.state.value.user);assertEquals(1,f.saves);assertEquals(0,f.hookCalls)
        gate.complete(Unit);runCurrent();f.model.awaitBootstrapped()
        assertTrue(f.model.state.value.authenticated);assertEquals(1,f.hookCalls)
    }
    @Test fun sameAccountRevalidationRefreshesMembershipWithoutReplayingTransitionEffects()=runTest {
        val f=Fixture(this);f.fetch={cached};runCurrent();f.model.awaitBootstrapped()
        assertEquals(1,f.teamCalls);assertEquals(0,f.hookCalls)
        f.model.revalidate();assertEquals(2,f.teamCalls);assertEquals(0,f.hookCalls)
    }
    @Test fun transientUserFailureWithLiveRefreshPreservesTheCachedPresentation()=runTest {
        val f=Fixture(this);f.fetch={throw NativeSessionUserUnavailable()};runCurrent();f.model.awaitBootstrapped()
        assertEquals(NativeAuthState(true,cached,false),f.model.state.value)
        assertTrue(f.savedHint);assertEquals(0,f.clears);assertEquals(0,f.teamCalls)
    }
    @Test fun missingCacheNeverInventsAuthenticationAfterTransientFailure()=runTest {
        val f=Fixture(this,user=null);f.fetch={throw IOException()};runCurrent();f.model.awaitBootstrapped()
        assertEquals(NativeAuthState(),f.model.state.value);assertTrue(f.savedHint);assertEquals(0,f.clears)
    }
    @Test fun failedUserValidationWithNoSurvivingRefreshClearsEvenAGenericError()=runTest {
        val f=Fixture(this);f.fetch={throw NativeSessionUserUnavailable()};f.refresh={null}
        runCurrent();f.model.awaitBootstrapped();assertEquals(NativeAuthState(),f.model.state.value)
        assertEquals(1,f.clears);assertFalse(f.savedHint)
    }
    @Test fun explicitUnauthorizedClearsButReturnedNilAlsoClearsWithASurvivingRefresh()=runTest {
        val f=Fixture(this);f.fetch={throw NativeSignInFailure(NativeSignInError.UNAUTHORIZED)}
        runCurrent();f.model.awaitBootstrapped();assertEquals(1,f.clears);assertFalse(f.model.state.value.authenticated)
        val second=Fixture(this);second.fetch={null};runCurrent();second.model.awaitBootstrapped()
        assertEquals(1,second.clears);assertFalse(second.savedHint)
    }
    @Test fun tokenProbeAndUserDeadlinesPreserveCacheWithoutWaitingForIgnoringWork()=runTest {
        val gate=CompletableDeferred<Unit>();val f=Fixture(this)
        f.probe={withContext(NonCancellable){gate.await()};true};runCurrent()
        advanceTimeBy(81);runCurrent();assertFalse(f.model.revalidating.value)
        assertEquals(NativeAuthState(true,cached,false),f.model.state.value);assertEquals(0,f.userCalls)
        gate.complete(Unit);runCurrent()
        val userGate=CompletableDeferred<Unit>();val second=Fixture(this)
        second.fetch={withContext(NonCancellable){userGate.await()};live};runCurrent()
        advanceTimeBy(81);runCurrent();assertFalse(second.model.revalidating.value)
        assertEquals(cached,second.model.state.value.user);assertEquals(0,second.clears)
        userGate.complete(Unit);runCurrent();assertEquals(cached,second.model.state.value.user)
    }
    @Test fun failedLiveRefreshReadPreservesCachedIdentityRatherThanTreatingItAsNoRefresh()=runTest {
        val f=Fixture(this);f.fetch={throw IOException()};f.refresh={throw NativeCredentialReadException()}
        runCurrent();f.model.awaitBootstrapped();assertEquals(cached,f.model.state.value.user)
        assertTrue(f.model.state.value.authenticated);assertEquals(0,f.clears)
    }
    @Test fun unavailableStoragePreservesAndRetriesExactlyOnceWhenItBecomesReadable()=runTest {
        val f=Fixture(this);var reads=0;f.available={++reads!=1};f.probe={f.probeCalls>1}
        runCurrent();f.model.awaitBootstrapped();assertEquals(2,f.probeCalls);assertEquals(3,reads)
        assertEquals(live,f.model.state.value.user);assertEquals(0,f.clears)
    }
    @Test fun repeatedUnavailableStorageDoesNotLoopOrClearTheSession()=runTest {
        val f=Fixture(this);var reads=0;f.available={++reads==2};f.probe={false}
        runCurrent();f.model.awaitBootstrapped();assertEquals(2,f.probeCalls);assertEquals(3,reads)
        assertEquals(NativeAuthState(true,cached,false),f.model.state.value);assertEquals(0,f.clears)
    }
    @Test fun aDefinitivelyEmptyStoreClearsOnlyPublishedStateAndSignedOutForegroundIsANoop()=runTest {
        val f=Fixture(this);f.probe={false};runCurrent();f.model.awaitBootstrapped()
        assertEquals(NativeAuthState(),f.model.state.value);assertFalse(f.savedHint);assertEquals(0,f.clears)
        f.model.revalidate();assertEquals(1,f.probeCalls)
    }
    @Test fun overlappingForegroundRequestsWaitForTheOneOwningRun()=runTest {
        val gate=CompletableDeferred<Unit>();val f=Fixture(this);f.fetch={gate.await();live};runCurrent()
        val waits=List(8){async { f.model.revalidate() }};runCurrent()
        assertEquals(1,f.userCalls);assertTrue(waits.none { it.isCompleted })
        waits.first().cancel();runCurrent();assertFalse(waits.first().isCompleted)
        gate.complete(Unit);runCurrent();waits.forEach { it.join() };assertEquals(1,f.userCalls)
        assertFalse(f.model.revalidating.value)
    }
    @Test fun signOutAndNewPublicationDropLateUserResults()=runTest {
        val gate=CompletableDeferred<Unit>();val f=Fixture(this);f.fetch={gate.await();live};runCurrent()
        f.model.clear();gate.complete(Unit);runCurrent();assertEquals(NativeAuthState(),f.model.state.value);assertEquals(0,f.saves)
        val secondGate=CompletableDeferred<Unit>();val second=Fixture(this)
        second.fetch={secondGate.await();cached};runCurrent();second.model.publishedSignIn(live)
        secondGate.complete(Unit);runCurrent();assertEquals(live,second.model.state.value.user);assertEquals(0,second.saves)
    }
    @Test fun aNewCredentialWriterPreventsAnOlderNilUserFromClearingTheStore()=runTest {
        val gate=CompletableDeferred<Unit>();val f=Fixture(this);f.fetch={null};f.refresh={gate.await();"old-refresh"};runCurrent()
        f.model.noteCredentialWrite();gate.complete(Unit);runCurrent();assertEquals(0,f.clears)
    }
    @Test fun anotherAccountWriterFencesBothLatePublicationAndLateClearing()=runTest {
        val userGate=CompletableDeferred<Unit>();val f=Fixture(this)
        f.fetch={userGate.await();live};runCurrent();f.epoch++
        userGate.complete(Unit);runCurrent()
        assertEquals(cached,f.model.state.value.user);assertEquals(0,f.saves);assertEquals(0,f.teamCalls)
        val refreshGate=CompletableDeferred<Unit>();val second=Fixture(this)
        second.fetch={null};second.refresh={refreshGate.await();"old"};runCurrent();second.epoch++
        refreshGate.complete(Unit);runCurrent();assertEquals(0,second.clears);assertTrue(second.savedHint)
    }
    @Test fun failedConditionalRetirementCannotClearPublishedIdentityOrHint()=runTest {
        val f=Fixture(this);f.fetch={null};f.clear={false};runCurrent();f.model.awaitBootstrapped()
        assertEquals(cached,f.model.state.value.user);assertTrue(f.model.state.value.authenticated);assertTrue(f.savedHint)
    }

    @Test fun discoveredTokensLoadCachedPresentationWithoutInventingAnAuthenticatedVerdict()=runTest {
        val gate=CompletableDeferred<Unit>();val f=Fixture(this,hint=false,user=null)
        f.cache=cached;f.fetch={gate.await();live};runCurrent()
        assertEquals(NativeAuthState(false,cached,false),f.model.state.value)
        gate.complete(Unit);runCurrent();assertEquals(live,f.model.state.value.user)
    }

    @Test fun localLogoutCanPublishItsVerdictWithoutCallingEncryptedCacheStorage()=runTest {
        var cacheReads=0
        val model=NativeSessionRestore(true,cached,{}, {cached},{true},{true},{live},{"refresh"},{true},{},{},
            onCleared={cacheReads++})
        model.clear(clearCachedUser=false)
        assertEquals(NativeAuthState(),model.state.value);assertEquals(0,cacheReads)
    }

}
