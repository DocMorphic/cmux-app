package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class NativeDeadlineSchedulerTest {
    private class Kernel:NativeDeadlineKernel {
        val armed=LinkedBlockingQueue<Long>();private val events=LinkedBlockingQueue<Unit>()
        @Volatile var closed=false
        @Volatile var fail=false
        override fun arm(deadlineMillis:Long){if(fail)error("Fixture arm failure");armed.add(deadlineMillis)}
        override fun signal(){events.add(Unit)}
        override fun awaitEvent(){checkNotNull(events.poll(3,TimeUnit.SECONDS)){"Fixture did not retire"}}
        override fun close(){closed=true}
    }
    private class Fixture {
        val now=AtomicLong();val kernels=CopyOnWriteArrayList<Kernel>()
        val scheduler=NativeDeadlineScheduler(now::get){Kernel().also{kernels+=it}}
        fun wake(time:Long){now.set(time);kernels.last().signal()}
        fun armed(deadline:Long){val end=System.nanoTime()+2_000_000_000
            while(System.nanoTime()<end){if(kernels.last().armed.poll(10,TimeUnit.MILLISECONDS)==deadline)return}
            error("Missing armed deadline $deadline")}
        fun idle(){val end=System.nanoTime()+2_000_000_000
            while(System.nanoTime()<end){if(kernels.all{it.closed})return;Thread.sleep(1)}
            error("Kernel not closed")}
    }
    @Test fun oneWorkerOrdersDeadlinesAndAnEarlierRegistrationRearmsIt() {
        val f=Fixture();val results=LinkedBlockingQueue<String>()
        val late=f.scheduler.register(1000){it.getOrThrow();results.add("late")};f.armed(1000)
        val early=f.scheduler.register(100){it.getOrThrow();results.add("early")};f.armed(100)
        assertEquals(1,f.kernels.size);f.wake(100);assertEquals("early",results.poll(2,TimeUnit.SECONDS));f.armed(1000)
        assertEquals(1,f.scheduler.pendingCount);f.wake(1000);assertEquals("late",results.poll(2,TimeUnit.SECONDS));f.idle()
        early.close();late.close();assertEquals(0,f.scheduler.pendingCount)
    }
    @Test fun spuriousWakeCannotExpireADeadlineAndLastCancellationClosesTheKernel() {
        val f=Fixture();val results=LinkedBlockingQueue<Result<Unit>>()
        val wait=f.scheduler.register(100){results.add(it)};f.armed(100);f.wake(50);f.armed(100)
        assertNull(results.poll());wait.close();f.idle();assertNull(results.poll())
        val next=f.scheduler.register(200){results.add(it)};f.armed(200);assertEquals(2,f.kernels.size)
        f.wake(200);assertTrue(results.poll(2,TimeUnit.SECONDS).isSuccess);f.idle();next.close()
    }
    @Test fun actualWorkerFailureSettlesPendingWaitsAndTheNextRegistrationCanRecover() {
        val f=Fixture();val results=LinkedBlockingQueue<Result<Unit>>()
        val wait=f.scheduler.register(100){results.add(it)};f.armed(100);f.kernels.last().fail=true;f.wake(50)
        assertTrue(results.poll(2,TimeUnit.SECONDS).isFailure);f.idle();assertEquals(0,f.scheduler.pendingCount);wait.close()
        val next=f.scheduler.register(200){results.add(it)};f.armed(200);f.wake(200)
        assertTrue(results.poll(2,TimeUnit.SECONDS).isSuccess);f.idle();next.close()
    }
    @Test fun completedOldTicketCannotCancelAnotherRegistration() {
        val f=Fixture();val results=LinkedBlockingQueue<Int>()
        val first=f.scheduler.register(100){it.getOrThrow();results.add(1)};f.armed(100)
        val second=f.scheduler.register(200){it.getOrThrow();results.add(2)};f.wake(100)
        assertEquals(1,results.poll(2,TimeUnit.SECONDS));first.close();assertEquals(1,f.scheduler.pendingCount)
        f.wake(200);assertEquals(2,results.poll(2,TimeUnit.SECONDS));f.idle();second.close()
    }
    @Test fun continuousWakeExpiresWorkWithoutAdvancingTheCoroutineDelayClock()=runTest {
        var continuous=0L;val wake=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val registered=CompletableDeferred<Long>()
        val work=NativeAuthWork(nowMillis={continuous},waitUntil={deadline->registered.complete(deadline);wake.await()})
        val result=async{runCatching{work.run(NativeAuthPhase.REFRESH,60_000){withContext(NonCancellable){release.await()}}}}
        runCurrent();assertEquals(60_000,registered.await());continuous=70_000;wake.complete(Unit);runCurrent()
        assertEquals(NativeSignInError.TIMED_OUT,(result.await().exceptionOrNull() as NativeSignInFailure).reason)
        assertEquals(0,testScheduler.currentTime);assertEquals(1,work.activeCount)
        assertTrue(runCatching{work.run(NativeAuthPhase.REFRESH,60_000){error("Parked token phase was admitted")}}.isFailure)
        release.complete(Unit);runCurrent();assertEquals(0,work.activeCount)
    }
    @Test fun deadlineRegistrationFailureSettlesTheCallerAndCancelsItsOperation()=runTest {
        val entered=CompletableDeferred<Unit>();val fail=CompletableDeferred<Unit>();var cancelled=false
        val work=NativeAuthWork(nowMillis={testScheduler.currentTime},waitUntil={fail.await();throw NativeSignInFailure(NativeSignInError.NETWORK_ERROR)})
        val result=async{runCatching{work.run(NativeAuthPhase.USER,60_000){entered.complete(Unit);try{awaitCancellation()}finally{cancelled=true}}}}
        runCurrent();entered.await();fail.complete(Unit);runCurrent()
        assertEquals(NativeSignInError.NETWORK_ERROR,(result.await().exceptionOrNull() as NativeSignInFailure).reason)
        assertTrue(cancelled);assertEquals(0,work.activeCount);currentCoroutineContext().ensureActive()
    }
    @Test fun callerCancellationRetiresTheInjectedWaitAndKeepsTheActualWorkTracked()=runTest {
        val release=CompletableDeferred<Unit>();var waiterRetired=false
        val work=NativeAuthWork(nowMillis={testScheduler.currentTime},waitUntil={try{awaitCancellation()}finally{waiterRetired=true}})
        val task=launch{work.run(NativeAuthPhase.REFRESH,60_000){withContext(NonCancellable){release.await()}}}
        runCurrent();task.cancelAndJoin();runCurrent();assertTrue(waiterRetired);assertEquals(1,work.activeCount)
        release.complete(Unit);runCurrent();assertEquals(0,work.activeCount)
    }
    @Test fun continuousSleepAdvanceDoesNotExpireTheOriginalUptimeRetryDamper()=runTest {
        var continuous=0L;var uptime=0L;val wake=kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val release=CompletableDeferred<Unit>()
        val work=NativeAuthWork(nowMillis={continuous},retryResetMillis=30,retryNowMillis={uptime},waitUntil={deadline->while(continuous<deadline)wake.receive()})
        val old=async{runCatching{work.run(NativeAuthPhase.EMAIL_EXCHANGE,10,admission=NativeAuthWork.Admission.EXCLUSIVE){
            withContext(NonCancellable){release.await()}
        }}}
        runCurrent();continuous=10;wake.send(Unit);runCurrent();assertEquals(NativeSignInError.TIMED_OUT,(old.await().exceptionOrNull() as NativeSignInFailure).reason)
        continuous=70_000
        assertTrue(runCatching{work.run(NativeAuthPhase.EMAIL_EXCHANGE,100,admission=NativeAuthWork.Admission.EXCLUSIVE){error("Uptime damper expired during sleep")}}.isFailure)
        uptime=31;assertEquals(42,work.run(NativeAuthPhase.EMAIL_EXCHANGE,100,admission=NativeAuthWork.Admission.EXCLUSIVE){42})
        assertEquals(1,work.activeCount);release.complete(Unit);runCurrent();assertEquals(0,work.activeCount);wake.close()
    }
    @Test fun failedDeadlineWaitCannotAdmitOverItsStillRetiringTokenOperation()=runTest {
        val fail=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val work=NativeAuthWork(nowMillis={testScheduler.currentTime},waitUntil={fail.await();throw NativeSignInFailure(NativeSignInError.NETWORK_ERROR)})
        val first=async{runCatching{work.run(NativeAuthPhase.REFRESH,60_000){withContext(NonCancellable){release.await()}}}}
        runCurrent();fail.complete(Unit);runCurrent();assertEquals(NativeSignInError.NETWORK_ERROR,(first.await().exceptionOrNull() as NativeSignInFailure).reason)
        assertEquals(1,work.activeCount)
        assertEquals(NativeSignInError.TIMED_OUT,(runCatching{work.run(NativeAuthPhase.REFRESH,60_000){error("Retiring work overlapped")}}.exceptionOrNull() as NativeSignInFailure).reason)
        release.complete(Unit);runCurrent();assertEquals(0,work.activeCount)
    }
}
