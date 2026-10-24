package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NativeTeamScopeRecoveryTest {
    private class Fixture(val test:TestScope) {
        val lock=Any();var generation=0L;var authenticated=true;var loaded=false;var reads=0
        var offset=0L;var calls=0;var draws=0
        val times=mutableListOf<Long>();val observations=mutableListOf<NativeSessionCheckObservation>()
        var action:suspend ()->Unit={}
        val recovery=NativeTeamScopeRecovery(test.backgroundScope,{generation},{authenticated},{loaded},{reads},
            {admit->if(admit()){calls++;times+=test.testScheduler.currentTime;action()}},
            {test.testScheduler.currentTime+offset},{draws++;.5},observations::add,{},lock)
        fun preserve()=recovery.preserved(generation)
        fun fail(error:Exception)=recovery.validationFailed(generation,error)
        fun rate(seconds:Double?=null)=fail(NativeAccountRateLimited(seconds,"/api/v1/teams","html"))
    }
    private fun reference()=JSONObject(checkNotNull(javaClass.getResourceAsStream("/native-team-recovery-reference.json"))
        .bufferedReader().use {it.readText()})
    @Test fun normalDelaysMatchAllTenExecutedOriginalCases() {
        val root=reference();assertEquals("a5911a1f92f3eeb11c4d7f3e2ef89204718c5009",root.getString("upstream"))
        val rows=root.getJSONArray("normal");assertEquals(10,rows.length())
        for(i in 0 until rows.length()){val row=rows.getJSONObject(i)
            assertEquals(row.getLong("milliseconds"),nativeTeamRecoveryDelayMillis(row.getInt("attempt")))}
    }
    @Test fun rateDelaysMatchAllFortyEightExecutedOriginalCases() {
        val rows=reference().getJSONArray("rate");assertEquals(48,rows.length())
        for(i in 0 until rows.length()){val row=rows.getJSONObject(i)
            assertEquals(row.toString(),row.getDouble("seconds"),nativeTeamRateLimitDelaySeconds(
                if(row.isNull("retryAfterSeconds"))null else row.getDouble("retryAfterSeconds"),row.getInt("streak"),row.getDouble("jitter")),.000000001)}
    }
    @Test fun retryHeadersMatchAllFourteenExecutedOriginalCases() {
        val root=reference();val rows=root.getJSONArray("headers");assertEquals(14,rows.length())
        for(i in 0 until rows.length()){val row=rows.getJSONObject(i);val actual=nativeRefreshRetryAfter(
            if(row.isNull("header"))null else row.getString("header"),root.getLong("nowMillis"))
            if(row.isNull("seconds"))assertNull(row.toString(),actual) else assertEquals(row.toString(),row.getDouble("seconds"),actual!!,.000000001)}
    }
    @Test fun oneLoopRetriesAtTwoFourEightSixteenThirtyTwoAndSixtySeconds()=runTest {
        val f=Fixture(this);repeat(10){f.preserve()};runCurrent()
        for(wait in listOf(2000L,4000,8000,16000,32000,60000,60000)){advanceTimeBy(wait);runCurrent()}
        assertEquals(listOf(2000L,6000,14000,30000,62000,122000,182000),f.times)
        f.loaded=true;advanceTimeBy(60000);runCurrent();assertEquals(7,f.calls);assertFalse(f.recovery.hasPendingRecovery)
    }
    @Test fun emptyLoadedListIsHealthyAndAnActiveReadOrSignedOutOwnerDoesNotSchedule()=runTest {
        val f=Fixture(this);f.loaded=true;f.preserve();f.recovery.recoverIfNeeded();assertFalse(f.recovery.hasPendingRecovery)
        f.loaded=false;f.reads=1;f.preserve();assertFalse(f.recovery.hasPendingRecovery)
        f.reads=0;f.authenticated=false;f.preserve();f.recovery.recoverIfNeeded();assertEquals(0,f.calls)
    }
    @Test fun aHostSignalExpeditesMissingMembershipButNotHealthyMembership()=runTest {
        val f=Fixture(this);f.preserve();runCurrent();f.recovery.recoverIfNeeded();assertEquals(1,f.calls)
        f.loaded=true;f.recovery.recoverIfNeeded();assertEquals(1,f.calls)
        f.recovery.validationCompleted();assertFalse(f.recovery.hasPendingRecovery)
    }
    @Test fun explicitThrottleBlocksSignalsEvenAfterItsDeadlineUntilTheLoopRetries()=runTest {
        val f=Fixture(this);f.rate(1.0);f.preserve();runCurrent();advanceTimeBy(1000);runCurrent()
        f.recovery.recoverIfNeeded();assertEquals(0,f.calls)
        advanceTimeBy(1000);runCurrent();assertEquals(1,f.calls);assertEquals(1,f.draws)
        f.recovery.recoverIfNeeded();assertEquals(1,f.calls);assertTrue(f.recovery.hasRateLimit)
    }
    @Test fun absentHeaderStreakUsesOneJitterDrawPerFailureAndNonRateFailureResetsIt()=runTest {
        val f=Fixture(this);f.rate();f.rate();assertEquals(listOf(15.0,30.0),f.observations.map{it.backoffSeconds})
        f.fail(IOException());assertFalse(f.recovery.hasRateLimit);f.rate();assertEquals(15.0,f.observations.last().backoffSeconds,0.0)
        assertEquals(3,f.draws)
    }
    @Test fun aNewThrottleExtendsAnAlreadySleepingRetry()=runTest {
        val f=Fixture(this);f.preserve();runCurrent();advanceTimeBy(1000);f.rate(5.0)
        advanceTimeBy(4999);runCurrent();assertEquals(0,f.calls);advanceTimeBy(1);runCurrent();assertEquals(listOf(6000L),f.times)
    }
    @Test fun aPreservedTimeoutKeepsTheSelectedRateDurationForTheNextAttempt()=runTest {
        val f=Fixture(this);f.rate(3.0);f.preserve();f.action={f.preserve()};runCurrent()
        advanceTimeBy(3000);runCurrent();assertEquals(1,f.calls);assertTrue(f.recovery.hasRateLimit)
        advanceTimeBy(3999);runCurrent();assertEquals(1,f.calls);advanceTimeBy(1);runCurrent();assertEquals(2,f.calls);assertEquals(1,f.draws)
    }
    @Test fun elapsedClockWakeRechecksTheDeadlineWithoutBypassingAnUnexpiredLimit()=runTest {
        val f=Fixture(this);f.rate(8.0);f.preserve();runCurrent();f.offset=7999;f.recovery.wakeClock();runCurrent();assertEquals(0,f.calls)
        f.offset=8000;f.recovery.wakeClock();runCurrent();assertEquals(1,f.calls)
    }
    @Test fun freshSignInResetsTheOldBackoffAndStartsAtTwoSeconds()=runTest {
        val f=Fixture(this);f.preserve();runCurrent();repeat(5){advanceTimeBy(nativeTeamRecoveryDelayMillis(it));runCurrent()}
        f.rate(900.0);f.recovery.signedIn();f.generation++;f.preserve();runCurrent()
        advanceTimeBy(1999);runCurrent();assertEquals(5,f.calls);advanceTimeBy(1);runCurrent();assertEquals(6,f.calls);assertFalse(f.recovery.hasRateLimit)
    }
    @Test fun staleFailuresCannotThrottleTheReplacementGeneration()=runTest {
        val f=Fixture(this);f.generation=1;f.recovery.validationFailed(0,NativeAccountRateLimited(900.0,"/api/v1/teams","html"))
        f.recovery.membershipCompleted(NativeAuthIdentity(0,"old"),IOException());f.recovery.preserved(0)
        assertFalse(f.recovery.hasRateLimit);assertFalse(f.recovery.hasPendingRecovery);assertEquals(0,f.draws)
    }
    @Test fun signOutAndCloseRetirePendingCallbacks()=runTest {
        val f=Fixture(this);f.preserve();runCurrent();f.authenticated=false;f.recovery.cleared()
        advanceTimeBy(2000);runCurrent();assertEquals(0,f.calls)
        f.authenticated=true;f.preserve();runCurrent();f.recovery.close();f.preserve();f.recovery.recoverIfNeeded()
        advanceTimeBy(2000);runCurrent();assertEquals(0,f.calls);assertFalse(f.recovery.hasPendingRecovery)
    }
    @Test fun thrownValidationAndReportingCallbacksCannotKillRecovery()=runTest {
        val f=Fixture(this);f.action={throw IOException()};f.preserve();runCurrent();advanceTimeBy(2000);runCurrent()
        advanceTimeBy(4000);runCurrent();assertEquals(2,f.calls)
        val c=NativeTeamScopeRecovery(backgroundScope,{0},{true},{false},{0},{},observe={throw IOException()})
        c.validationFailed(0,NativeAccountRateLimited(1.0,"/api/v1/teams","html"));assertTrue(c.hasRateLimit);c.close()
    }
    @Test fun metadataCannotRetainHostQueryResponseOrAnAccountIdentifier() {
        val e=NativeAccountRateLimited(999999.0,"https://private.test/users/private-user?token=secret","private-body")
        assertEquals("/unknown",e.endpoint);assertEquals("other",e.bodyKind);assertEquals(86400.0,e.retryAfterSeconds!!,0.0)
        assertFalse(e.toString().contains("private"));assertNull(nativeSessionCheckLimit(IOException("429")))
        assertEquals("/unknown",nativeSessionCheckLimit(NativeAccountRequestFailure("rate_limit",false,"fixture"))!!.endpoint)
    }
    private inner class ModelFixture(val test:TestScope) {
        val lock=Any();val user=NativeSignInUser("account-a","A",null,null)
        var loaded=false;var calls=0;var probe:suspend ()->Boolean={true};var fetch:suspend ()->NativeSignInUser?={user}
        var membership:suspend ()->Unit={};var hook:suspend ()->Unit={}
        val model=NativeSessionRestore(true,user,{}, {user},{true},{probe()},{calls++;fetch()},{"refresh"},{true},{},
            {membership()},{hook()},NativeAuthWork(nowMillis={test.testScheduler.currentTime}),80,600,lock=lock)
        val recovery=NativeTeamScopeRecovery(test.backgroundScope,{model.sessionGeneration},{model.state.value.authenticated},{loaded},{0},
            {admit->model.revalidateIf(admit)},{test.testScheduler.currentTime},{.5},lock=lock)
        init {model.recoveryObserver=recovery}
    }
    @Test fun userThrottleSurvivesTokenProbeAndUserTimeoutsButAnOrdinaryErrorClearsIt()=runTest {
        val f=ModelFixture(this);f.fetch={throw NativeAccountRateLimited(10.0,"/api/v1/users/me","html")};f.model.revalidate()
        assertTrue(f.recovery.hasRateLimit);assertTrue(f.recovery.hasPendingRecovery)
        f.probe={awaitCancellation()};val probe=launch{f.model.revalidate()};runCurrent();advanceTimeBy(81);runCurrent();probe.join()
        assertTrue(f.recovery.hasRateLimit)
        f.probe={true};f.fetch={awaitCancellation()};val user=launch{f.model.revalidate()};runCurrent();advanceTimeBy(81);runCurrent();user.join()
        assertTrue(f.recovery.hasRateLimit);f.fetch={throw IOException()};f.model.revalidate();assertFalse(f.recovery.hasRateLimit)
    }
    @Test fun recoveryCancelsOnlyAfterTheOwningValidationFinishesItsPostSignInHook()=runTest {
        val f=ModelFixture(this);val gate=CompletableDeferred<Unit>();f.fetch={f.user.copy(id="new-user")}
        f.recovery.preserved(f.model.sessionGeneration);runCurrent()
        f.membership={f.loaded=true;f.recovery.membershipCompleted(f.model.identity(),null)};f.hook={gate.await()}
        val job=launch {f.model.revalidate()};runCurrent();assertTrue(f.recovery.hasPendingRecovery);assertTrue(f.model.revalidating.value)
        gate.complete(Unit);runCurrent();job.join();assertFalse(f.recovery.hasPendingRecovery);assertFalse(f.model.revalidating.value)
    }
    @Test fun recoveryAdmissionCannotBypassANewThrottleAndExistingValidationStillCoalesces()=runTest {
        val f=ModelFixture(this);f.model.revalidateIf {false};assertEquals(0,f.calls)
        val gate=CompletableDeferred<Unit>();f.fetch={gate.await();f.user}
        val lead=launch{f.model.revalidate()};runCurrent();val follower=launch{f.model.revalidateIf{false}};runCurrent()
        assertEquals(1,f.calls);assertFalse(follower.isCompleted);gate.complete(Unit);runCurrent();lead.join();follower.join()
        f.recovery.validationFailed(f.model.sessionGeneration,NativeAccountRateLimited(20.0,"/api/v1/teams","html"))
        f.recovery.recoverIfNeeded();assertEquals(1,f.calls)
        // The ordinary iOS foreground/explicit-retry route remains unconditional.
        f.model.revalidate();assertEquals(2,f.calls)
    }
}
