package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeAuthWaitsTest {
    private class Clock {
        var now=0L
        val wakes=MutableStateFlow(0L)
        val deadlines=mutableListOf<Long>()
        suspend fun wait(deadline:Long){deadlines+=deadline;wakes.first{it>=deadline}}
        suspend fun advance(value:Long){now=value;wakes.value=value}
    }
    @Test fun continuousCompletionDeadlineDoesNotJoinOrCancelTheUnderlyingJob()=runTest {
        val clock=Clock();val actual=Job();val waits=NativeAuthWaits({clock.now},clock::wait)
        val result=async{runCatching{waits.completions(listOf(actual),100)}}
        runCurrent();clock.advance(100);runCurrent()
        assertEquals(NativeSignInError.TIMED_OUT,(result.await().exceptionOrNull() as NativeSignInFailure).reason)
        assertTrue(actual.isActive);assertEquals(0,testScheduler.currentTime);actual.complete()
    }
    @Test fun sharedPreflightBudgetIncludesPreviousFlowWaitAndActualTokenRetirement()=runTest {
        val clock=Clock();val work=NativeAuthWork(nowMillis={clock.now},waitUntil=clock::wait)
        val previous=Job();val release=CompletableDeferred<Unit>()
        val token=launch{runCatching{work.run(NativeAuthPhase.REFRESH,1000){withContext(NonCancellable){release.await()}}}}
        runCurrent();val deadline=clock.now+100
        val result=async{runCatching{work.awaitCompletions(listOf(previous),100,deadline);work.quiesce(100,deadline)}}
        runCurrent();clock.now=60;previous.complete();runCurrent()
        assertEquals(listOf(1000L,100L,100L),clock.deadlines)
        clock.advance(100);runCurrent();assertEquals(NativeSignInError.TIMED_OUT,(result.await().exceptionOrNull() as NativeSignInFailure).reason)
        assertEquals(1,work.activeCount);release.complete(Unit);runCurrent();token.join();assertEquals(0,work.activeCount)
    }
    @Test fun structuredDeadlineCancelsThenJoinsItsActualNonCooperativeOperation()=runTest {
        val clock=Clock();val release=CompletableDeferred<Unit>();var operation:Job?=null;var after=0
        val waits=NativeAuthWaits({clock.now},clock::wait)
        val result=async{runCatching{waits.structured(100){operation=currentCoroutineContext().job
            withContext(NonCancellable){release.await()};currentCoroutineContext().ensureActive();after++}}}
        runCurrent();clock.advance(100);runCurrent();assertTrue(operation!!.isCancelled);assertFalse(result.isCompleted)
        release.complete(Unit);runCurrent();assertEquals(NativeSignInError.TIMED_OUT,(result.await().exceptionOrNull() as NativeSignInFailure).reason)
        assertEquals(0,after);assertEquals(0,testScheduler.currentTime)
    }
    @Test fun callerCancellationOfAReadOnlyJoinLeavesItsOtherOwnerAlive()=runTest {
        val actual=Job();var retired=false
        val waits=NativeAuthWaits({0}){try{awaitCancellation()}finally{retired=true}}
        val waiter=launch{waits.completions(listOf(actual),100)};runCurrent();waiter.cancelAndJoin();runCurrent()
        assertTrue(retired);assertTrue(actual.isActive);actual.complete()
    }
    @Test fun callerCancellationOfStructuredTailWaitsForItsOperationToRetire()=runTest {
        val release=CompletableDeferred<Unit>();val waits=NativeAuthWaits({0}){awaitCancellation()};var operation:Job?=null
        val waiter=launch{waits.structured(100){operation=currentCoroutineContext().job;withContext(NonCancellable){release.await()}}}
        runCurrent();waiter.cancel();runCurrent();assertTrue(operation!!.isCancelled);assertFalse(waiter.isCompleted)
        release.complete(Unit);runCurrent();waiter.join();currentCoroutineContext().ensureActive()
    }
    @Test fun deadlineSetupFailureStillJoinsTheStructuredOperationAndCannotStartAnotherLeg()=runTest {
        val fail=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var after=0;var operation:Job?=null
        val waits=NativeAuthWaits({0}){fail.await();throw NativeSignInFailure(NativeSignInError.NETWORK_ERROR)}
        val waiter=async{runCatching{waits.structured(100){operation=currentCoroutineContext().job
            withContext(NonCancellable){release.await()};currentCoroutineContext().ensureActive();after++}}}
        runCurrent();fail.complete(Unit);runCurrent();assertTrue(operation!!.isCancelled);assertFalse(waiter.isCompleted)
        release.complete(Unit);runCurrent();assertEquals(NativeSignInError.NETWORK_ERROR,(waiter.await().exceptionOrNull() as NativeSignInFailure).reason)
        assertEquals(0,after)
    }
    @Test fun postHookJoinAndStructuredServerTailHaveSeparateContinuousBudgets()=runTest {
        val clock=Clock();val work=NativeAuthWork(nowMillis={clock.now},waitUntil=clock::wait,teardownDispatcher=StandardTestDispatcher(testScheduler))
        val hookRelease=CompletableDeferred<Unit>();val tailRelease=CompletableDeferred<Unit>();var operation:Job?=null
        val hook=launch{runCatching{work.run(NativeAuthPhase.POST_SIGN_IN,1000){withContext(NonCancellable){hookRelease.await()}}}}
        runCurrent();work.cancelAll();val tail=work.launchSignOutTeardown(100){operation=currentCoroutineContext().job;withContext(NonCancellable){tailRelease.await()}}
        runCurrent();assertEquals(listOf(1000L,100L),clock.deadlines)
        clock.advance(100);runCurrent();assertEquals(listOf(1000L,100L,200L),clock.deadlines)
        clock.advance(200);runCurrent();assertTrue(operation!!.isCancelled);assertFalse(tail.isCompleted);assertEquals(2,work.activeCount)
        tailRelease.complete(Unit);runCurrent();tail.join();assertEquals(1,work.activeCount)
        hookRelease.complete(Unit);runCurrent();hook.join();assertEquals(0,work.activeCount)
    }
    @Test fun completedJoinsNeedNoTimerAndDoNotInventAnExpiryVerdict()=runTest {
        val waits=NativeAuthWaits({1000}){error("Completed joins must not acquire a timer")}
        waits.completions(emptyList(),100,1);waits.completions(listOf(Job().apply{complete()}),100,1)
        assertEquals(42,NativeAuthWaits({0}){awaitCancellation()}.structured(100){42})
    }
}
