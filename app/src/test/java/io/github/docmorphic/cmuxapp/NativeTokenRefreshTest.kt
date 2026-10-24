package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NativeTokenRefreshTest {
    private class Fixture(val scheduler:TestCoroutineScheduler) {
        var elapsed=0L
        val owner=NativeRefreshOwner("login","refresh",0)
        var view=NativeRefreshView(owner,"old")
        var calls=0
        val work=NativeAuthWork(nowMillis={elapsed})
        val signals=mutableListOf<NativeRefreshRateLimitObservation>();var recoveries=0
        val refresh=NativeTokenRefresh(now={elapsed},dispatcher=StandardTestDispatcher(scheduler),jitter={.5},observe=signals::add,recovered={recoveries++})
        fun apply(owner:NativeRefreshOwner,access:String?,fence:(()->Boolean)->Boolean):Boolean {
            if(view.owner!=owner)return false
            return fence { view=NativeRefreshView(owner.takeIf { access!=null },access);true }
        }
        suspend fun read(exchange:suspend ()->String)=refresh.resolve(owner,"old",work,{view},::apply,{calls++;exchange()},{false})
    }
    @Test fun concurrentReadersShareBothSuccessAndFailureAndLaterCallsCanRetry()=runTest {
        for(fail in listOf(false,true)) {
            val f=Fixture(testScheduler);val gate=CompletableDeferred<Unit>();val failure=IOException("Fixture transport unavailable")
            try {
                val readers=List(12) { async { f.read { gate.await();if(fail)throw failure;"minted" } } }
                runCurrent();assertEquals(1,f.calls);assertEquals(12,f.refresh.waiterCount)
                gate.complete(Unit);runCurrent();val results=readers.awaitAll()
                if(fail) { assertTrue(results.all { it is NativeRefreshResult.Failure && it.error===failure });assertEquals("old",f.view.access) }
                else { assertTrue(results.all { it is NativeRefreshResult.Success && it.access=="minted" });assertEquals("minted",f.view.access) }
                f.view=NativeRefreshView(f.owner,"old")
                val retry=async { f.read { "retry" } };runCurrent();assertTrue(retry.await() is NativeRefreshResult.Success);assertEquals(2,f.calls)
            } finally { f.refresh.close() }
        }
    }
    @Test fun cancellingOneWaiterLeavesTheSharedExchangeForItsOtherWaiter()=runTest {
        val f=Fixture(testScheduler);val gate=CompletableDeferred<Unit>()
        try {
            val one=async { f.read { gate.await();"minted" } };val two=async { f.read { error("Already owned") } }
            runCurrent();one.cancel();runCurrent();assertEquals(1,f.refresh.waiterCount);assertEquals(1,f.calls);assertFalse(two.isCompleted)
            gate.complete(Unit);runCurrent();assertEquals("minted",(two.await() as NativeRefreshResult.Success).access)
        } finally { gate.complete(Unit);f.refresh.close() }
    }
    @Test fun lastCancelledWaiterReleasesAttemptButActualLateWorkStaysTrackedAndCannotWrite()=runTest {
        val f=Fixture(testScheduler);val gate=CompletableDeferred<Unit>()
        try {
            val old=async { f.read { withContext(NonCancellable) { gate.await() };"obsolete" } }
            runCurrent();old.cancel();runCurrent();assertEquals(0,f.refresh.waiterCount);assertEquals(1,f.work.activeCount)
            val fresh=async { f.read { "minted" } };runCurrent();assertTrue(fresh.await() is NativeRefreshResult.Success)
            gate.complete(Unit);runCurrent();assertEquals("minted",f.view.access);assertEquals(0,f.work.activeCount)
        } finally { gate.complete(Unit);f.refresh.close() }
    }
    @Test fun originalDefaultThirtySecondDeadlineRejectsLateWorkAndAllowsANewAttempt()=runTest {
        val f=Fixture(testScheduler);val gate=CompletableDeferred<Unit>()
        try {
            val old=async { f.read { withContext(NonCancellable) { gate.await() };"obsolete" } }
            runCurrent();f.elapsed=30_000;advanceTimeBy(30_000);runCurrent()
            assertSame(NativeRefreshResult.TimedOut,old.await());assertEquals("old",f.view.access)
            val fresh=async { f.read { "minted" } };runCurrent();assertTrue(fresh.await() is NativeRefreshResult.Success)
            gate.complete(Unit);runCurrent();assertEquals("minted",f.view.access);assertEquals(0,f.work.activeCount)
        } finally { gate.complete(Unit);f.refresh.close() }
    }
    @Test fun elapsedDeadlineWinsWhenResponseRunsBeforeTheSleepingTimer()=runTest {
        var elapsed=0L;val never=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>()
        val refresh=NativeTokenRefresh(now={elapsed},dispatcher=StandardTestDispatcher(testScheduler),sleepUntil={never.await()})
        val f=Fixture(testScheduler)
        try {
            val old=async { refresh.resolve(f.owner,"old",f.work,{f.view},f::apply,{gate.await();"obsolete"},{false}) }
            runCurrent();elapsed=600_000;gate.complete(Unit);runCurrent()
            assertSame(NativeRefreshResult.TimedOut,old.await());assertEquals("old",f.view.access)
        } finally { never.complete(Unit);gate.complete(Unit);refresh.close();f.refresh.close() }
    }
    @Test fun delayedTimerRegistrationUsesTheOriginalDeadlineRatherThanANewBudget()=runTest {
        var elapsed=0L;val registered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>()
        val refresh=NativeTokenRefresh(now={elapsed},dispatcher=StandardTestDispatcher(testScheduler),sleepUntil={deadline->registered.await();delay((deadline-elapsed).coerceAtLeast(0))})
        val f=Fixture(testScheduler)
        try {
            val old=async { refresh.resolve(f.owner,"old",f.work,{f.view},f::apply,{withContext(NonCancellable){gate.await()};"obsolete"},{false}) }
            runCurrent();elapsed=600_000;registered.complete(Unit);runCurrent()
            assertSame(NativeRefreshResult.TimedOut,old.await());assertEquals("old",f.view.access)
            gate.complete(Unit);runCurrent();assertEquals("old",f.view.access)
        } finally { gate.complete(Unit);registered.complete(Unit);refresh.close();f.refresh.close() }
    }
    @Test fun deadlineFenceRunsAtFinalCommitAfterTheEncryptionPhase()=runTest {
        val f=Fixture(testScheduler)
        try {
            val result=async { f.refresh.resolve(f.owner,"old",f.work,{f.view},{owner,access,fence->
                f.elapsed=30_001;f.apply(owner,access,fence)
            },{"minted"},{false}) }
            runCurrent();assertSame(NativeRefreshResult.TimedOut,result.await());assertEquals("old",f.view.access)
        } finally { f.refresh.close() }
    }
    @Test fun ownerReplacementRetiresOldWaitersAndItsLateResultCannotRetireTheSuccessor()=runTest {
        val f=Fixture(testScheduler);val gate=CompletableDeferred<Unit>();val next=NativeRefreshOwner("next","next-refresh",1)
        try {
            val old=async { f.read { withContext(NonCancellable) { gate.await() };"obsolete" } };runCurrent()
            f.view=NativeRefreshView(next,"old")
            val fresh=async { f.refresh.resolve(next,"old",f.work,{f.view},f::apply,{"minted"},{false}) };runCurrent()
            assertSame(NativeRefreshResult.Changed,old.await());assertTrue(fresh.await() is NativeRefreshResult.Success)
            gate.complete(Unit);runCurrent();assertEquals(NativeRefreshView(next,"minted"),f.view)
        } finally { gate.complete(Unit);f.refresh.close() }
    }
    @Test fun concurrentMintAlreadyInStorageAvoidsAnotherExchange()=runTest {
        val f=Fixture(testScheduler)
        try {
            f.view=NativeRefreshView(f.owner,"already-minted")
            assertEquals("already-minted",(f.read { error("No exchange") } as NativeRefreshResult.Success).access);assertEquals(0,f.calls)
        } finally { f.refresh.close() }
    }
    @Test fun storedOwnerChangeRetiresWaitersWithoutWaitingForAResponseOrAnotherReader()=runTest {
        val f=Fixture(testScheduler);val gate=CompletableDeferred<Unit>()
        try {
            val old=async { f.read { withContext(NonCancellable) { gate.await() };"obsolete" } };runCurrent()
            f.view=NativeRefreshView(null,null);f.refresh.ownerChanged(null)
            assertEquals(0,f.refresh.waiterCount);runCurrent();assertSame(NativeRefreshResult.Changed,old.await())
            assertEquals(1,f.work.activeCount);gate.complete(Unit);runCurrent();assertNull(f.view.access);assertEquals(0,f.work.activeCount)
        } finally { gate.complete(Unit);f.refresh.close() }
    }
    @Test fun rateLimitCooldownIsSharedAndReportsOnlyExchangesThenOneRecovery()=runTest {
        val f=Fixture(testScheduler)
        suspend fun request(action:suspend ()->String):NativeRefreshResult {
            val pending=async { f.read(action) };runCurrent();return pending.await()
        }
        try {
            assertTrue(request { throw NativeRefreshRateLimited(null,"html") } is NativeRefreshResult.Failure)
            assertEquals(1,f.calls);assertEquals(2.5,f.signals.single().backoffSeconds,0.0)
            assertTrue(request { error("Cooling down") } is NativeRefreshResult.Failure);assertEquals(1,f.calls);assertEquals(1,f.signals.size)
            f.elapsed=2500
            request { throw NativeRefreshRateLimited(null,"json") };assertEquals(5.0,f.signals.last().backoffSeconds,0.0)
            assertEquals(2,f.signals.last().attempt);assertEquals("/api/v1/auth/oauth/token",f.signals.last().endpoint)
            f.elapsed=7500;assertTrue(request { "minted" } is NativeRefreshResult.Success);assertEquals(1,f.recoveries)
            f.view=NativeRefreshView(f.owner,"old");request { throw NativeRefreshRateLimited(900.0,"other") }
            assertEquals(1,f.signals.last().attempt);assertEquals(600.0,f.signals.last().backoffSeconds,0.0)
        } finally { f.refresh.close() }
    }
    @Test fun rejectedRefreshClearsOnlyItsCapturedOwnerAndResetsTheCooldownStreak()=runTest {
        val f=Fixture(testScheduler)
        try {
            val rejected=async { f.read { throw NativeAccount.InvalidRefreshToken() } };runCurrent()
            assertSame(NativeRefreshResult.Rejected,rejected.await());assertNull(f.view.owner);assertNull(f.view.access)
            assertEquals(0,f.recoveries)
        } finally { f.refresh.close() }
    }
    @Test fun retryAfterAndBodyClassificationMatchTheOriginalBoundsWithoutRetainingContents() {
        assertEquals(0.0,nativeRefreshRetryAfter("0",0));assertEquals(86_400.0,nativeRefreshRetryAfter("999999",0))
        assertNull(nativeRefreshRetryAfter("NaN",0));assertNull(nativeRefreshRetryAfter("-2",0));assertNull(nativeRefreshRetryAfter("Infinity",0))
        assertEquals(60.0,nativeRefreshRetryAfter("Thu, 01 Jan 1970 00:01:00 GMT",0));assertEquals(0.0,nativeRefreshRetryAfter("Thu, 01 Jan 1970 00:00:00 GMT",60_000))
        assertEquals("html",nativeRefreshBodyKind(null," \n<private>".toByteArray()));assertEquals("json",nativeRefreshBodyKind(null," [private]".toByteArray()))
        assertEquals("json",nativeRefreshBodyKind("application/problem+json","<private>".toByteArray()));assertEquals("other",nativeRefreshBodyKind(null,"private".toByteArray()))
        assertEquals("json",nativeRefreshBodyKind(null,byteArrayOf(133.toByte(),123)))
        assertEquals("other",nativeRefreshBodyKind(null,byteArrayOf(28,123)))
        assertEquals(1.0,nativeRefreshBackoffSeconds(0.0,1,.5),0.0);assertEquals(300.0,nativeRefreshBackoffSeconds(null,99,1.0),0.0)
        assertEquals("NativeRefreshOwner(redacted)",NativeRefreshOwner("private","secret",0).toString())
    }
}
