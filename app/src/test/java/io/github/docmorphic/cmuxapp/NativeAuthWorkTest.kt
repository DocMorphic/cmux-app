package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NativeAuthWorkTest {
    @Test fun completedPlainPhaseKeepsItsWinWhenThePublicContinuationIsDelayed() {
        val dispatcher = object : CoroutineDispatcher() {
            val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
            fun step() { checkNotNull(queue.poll(2, java.util.concurrent.TimeUnit.SECONDS)) { "Missing queued continuation" }.run() }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val work = NativeAuthWork()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = scope.async {
            runCatching {
                work.run(NativeAuthPhase.TEAM_SELECTION, 500, admission = NativeAuthWork.Admission.PHASE) {
                    entered.complete(Unit)
                    release.await()
                    42
                }
            }
        }
        try {
            while (!entered.isCompleted) dispatcher.step()
            release.complete(Unit)
            while (work.activeCount != 0) dispatcher.step()
            assertFalse(result.isCompleted)
            Thread.sleep(650)
            while (!result.isCompleted) dispatcher.step()
            assertEquals(42, result.getCompleted().getOrThrow())
        } finally {
            release.complete(Unit)
            scope.cancel()
            work.cancelAll()
            while (true) (dispatcher.queue.poll() ?: break).run()
            assertEquals(0, work.activeCount)
        }
    }

    @Test fun plainTeamPhasesRemainTrackedWithoutBlockingRetryOrTokenQuiescence()=runTest {
        val work=NativeAuthWork({testScheduler.currentTime});val gate=CompletableDeferred<Unit>()
        val first=async{runCatching{work.run(NativeAuthPhase.TEAM_SELECTION,10,admission=NativeAuthWork.Admission.PHASE){
            withContext(NonCancellable){gate.await()}
        }}}
        try {
            runCurrent();advanceTimeBy(11);runCurrent();assertTrue(first.await().isFailure);assertEquals(1,work.activeCount)
            assertEquals(2,work.run(NativeAuthPhase.TEAM_SELECTION,10,admission=NativeAuthWork.Admission.PHASE){2})
            work.quiesce(10);assertEquals(1,work.activeCount)
            gate.complete(Unit);runCurrent();assertEquals(0,work.activeCount)
        } finally {gate.complete(Unit);first.cancelAndJoin();runCurrent()}
    }
    @Test fun deadlineEndsTheWaitButRetainsTheCancellationIgnoringOperationUntilTerminal() = runTest {
        val work = NativeAuthWork({ testScheduler.currentTime }); val release = CompletableDeferred<Int>()
        val waiting = async { runCatching { work.run(NativeAuthPhase.USER, 50) { withContext(NonCancellable) { release.await() } } } }
        runCurrent(); assertEquals(1, work.activeCount); advanceTimeBy(50); runCurrent()
        assertTrue(waiting.isCompleted); assertEquals(NativeSignInError.TIMED_OUT, nativeSignInError(waiting.await().exceptionOrNull() as Exception))
        assertEquals(1, work.activeCount); release.complete(7); runCurrent(); assertEquals(0, work.activeCount)
        assertEquals(8, work.run(NativeAuthPhase.USER, 50) { 8 })
    }

    @Test fun callerCancellationDoesNotWaitForAnUncooperativeOperation() = runTest {
        val work = NativeAuthWork({ testScheduler.currentTime }); val release = CompletableDeferred<Unit>()
        val caller = launch { work.run(NativeAuthPhase.REFRESH, 1000) { withContext(NonCancellable) { release.await() } } }
        runCurrent(); caller.cancel(); runCurrent(); assertTrue(caller.isCompleted); assertEquals(1, work.activeCount)
        release.complete(Unit); runCurrent(); assertEquals(0, work.activeCount)
    }

    @Test fun automaticResetCannotReclaimTokenWorkWhileAnExplicitResetRetainsItsActualJob() = runTest {
        val work = NativeAuthWork({ testScheduler.currentTime }, retryResetMillis = 30); val release = CompletableDeferred<Unit>()
        val caller = async { runCatching { work.run(NativeAuthPhase.USER, 10) { withContext(NonCancellable) { release.await() } } } }
        runCurrent(); advanceTimeBy(10); runCurrent(); caller.await(); advanceTimeBy(31)
        var ran = false
        val rejected = runCatching { work.run(NativeAuthPhase.USER, 10) { ran = true } }.exceptionOrNull() as Exception
        assertEquals(NativeSignInError.TIMED_OUT, nativeSignInError(rejected)); assertFalse(ran); assertEquals(1, work.activeCount)
        work.supersedeTimedOutPhases(); work.run(NativeAuthPhase.USER, 10) { ran = true }
        assertTrue(ran); assertEquals(1, work.activeCount)
        release.complete(Unit); runCurrent(); work.run(NativeAuthPhase.USER, 10) { ran = true }; assertTrue(ran)
    }

    @Test fun anExpiredExclusiveSlotDoesNotForgetItsActualJobDuringPreflight() = runTest {
        val work = NativeAuthWork({ testScheduler.currentTime }, retryResetMillis = 30); val release = CompletableDeferred<Unit>()
        val old = async { runCatching { work.run(NativeAuthPhase.EMAIL_EXCHANGE, 10, admission = NativeAuthWork.Admission.EXCLUSIVE) {
            withContext(NonCancellable) { release.await() }
        } } }
        runCurrent(); advanceTimeBy(10); runCurrent(); old.await(); advanceTimeBy(31)
        assertEquals(2, work.run(NativeAuthPhase.EMAIL_EXCHANGE, 10, admission = NativeAuthWork.Admission.EXCLUSIVE) { 2 })
        assertEquals(1, work.activeCount)
        val join = async { runCatching { work.quiesce(10) } }; runCurrent(); advanceTimeBy(10); runCurrent()
        assertEquals(NativeSignInError.TIMED_OUT, nativeSignInError(join.await().exceptionOrNull() as Exception))
        assertEquals(1, work.activeCount); release.complete(Unit); runCurrent(); assertEquals(0, work.activeCount)
    }

    @Test fun explicitSupersessionReclaimsOnlyTimedOutExclusiveSlots() = runTest {
        val work = NativeAuthWork({ testScheduler.currentTime }); val release = CompletableDeferred<Unit>()
        val old = async { runCatching { work.run(NativeAuthPhase.OAUTH_EXCHANGE, 10, admission = NativeAuthWork.Admission.EXCLUSIVE) {
            withContext(NonCancellable) { release.await() }
        } } }
        runCurrent(); work.supersedeTimedOutPhases()
        assertTrue(runCatching { work.run(NativeAuthPhase.OAUTH_EXCHANGE, 10, admission = NativeAuthWork.Admission.EXCLUSIVE) {} }.isFailure)
        advanceTimeBy(10); runCurrent(); old.await(); work.supersedeTimedOutPhases()
        work.run(NativeAuthPhase.OAUTH_EXCHANGE, 10, admission = NativeAuthWork.Admission.EXCLUSIVE) {}
        assertEquals(1, work.activeCount); release.complete(Unit); runCurrent(); assertEquals(0, work.activeCount)
    }

    @Test fun preflightCancelsAndWaitsForActualTokenWorkButDoesNotJoinPromptOnlyWork() = runTest {
        val work = NativeAuthWork({ testScheduler.currentTime }); val release = CompletableDeferred<Unit>()
        val token = launch { work.run(NativeAuthPhase.REFRESH, 1000) { withContext(NonCancellable) { release.await() } } }
        val prompt = launch { work.run(NativeAuthPhase.SEND_CODE, 1000, admission = NativeAuthWork.Admission.EXCLUSIVE) { awaitCancellation() } }
        runCurrent(); val preflight = async { work.quiesce(500) }; runCurrent()
        assertFalse(token.isCompleted); assertFalse(preflight.isCompleted); assertFalse(prompt.isCompleted)
        release.complete(Unit); runCurrent(); preflight.await(); assertTrue(token.isCompleted); assertEquals(1, work.activeCount)
        work.cancelAll(); runCurrent(); assertTrue(prompt.isCompleted); assertEquals(0, work.activeCount)
    }

    @Test fun tokenExpiryAndExclusiveRaceWinnerFollowTheirDifferentSourceContracts() = runTest {
        var now = 0L; val work = NativeAuthWork({ now })
        val result = runCatching { work.run(NativeAuthPhase.USER, 50) { now = 51; "late" } }
        assertEquals(NativeSignInError.TIMED_OUT, nativeSignInError(result.exceptionOrNull() as Exception)); assertEquals(0, work.activeCount)
        assertEquals("winner", work.run(NativeAuthPhase.EMAIL_EXCHANGE, 50, admission = NativeAuthWork.Admission.EXCLUSIVE) { now += 51; "winner" })
        assertEquals(0, work.activeCount)
    }

    @Test fun conditionalCleanupIsTrackedUntilFinishedAndItsFailureIsContained() = runTest {
        val work = NativeAuthWork({ testScheduler.currentTime }); val release = CompletableDeferred<Unit>()
        val cleanup = work.cleanup(coroutineContext, "old") { release.await(); throw IOException("Fixture storage failure") }
        runCurrent(); val preflight = async { work.quiesce(50) }; runCurrent(); assertFalse(preflight.isCompleted)
        release.complete(Unit); runCurrent(); preflight.await(); assertTrue(cleanup.isCompleted); assertEquals(0, work.activeCount)
        currentCoroutineContext().ensureActive()
    }
}
