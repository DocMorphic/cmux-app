package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceEmptyRecoveryTest {
    @Test fun duplicateRetryIsIgnoredAndSuccessClearsBusy() = runBlocking {
        val gate = CompletableDeferred<Unit>(); var calls = 0
        val recovery = NativeWorkspaceEmptyRecovery(this)
        try {
            assertTrue(recovery.start { calls++; gate.await() })
            assertFalse(recovery.start { calls++ })
            yield(); assertEquals(1, calls); assertTrue(recovery.state.value.busy)
            gate.complete(Unit)
            withTimeout(2000) { while (recovery.state.value.busy) yield() }
            assertNull(recovery.state.value.message)
        } finally { recovery.close() }
    }
    @Test fun timeoutCancelsOwnedReadAndAllowsFreshRetry() = runBlocking {
        val cancelled = CompletableDeferred<Unit>()
        val recovery = NativeWorkspaceEmptyRecovery(this, 25)
        try {
            recovery.start { try { awaitCancellation() } finally { cancelled.complete(Unit) } }
            withTimeout(2000) { cancelled.await(); while (recovery.state.value.busy) yield() }
            assertEquals(NativeWorkspaceEmptyRecovery.TIMEOUT, recovery.state.value.message)
            assertTrue(recovery.start {})
            withTimeout(2000) { while (recovery.state.value.busy) yield() }
            assertNull(recovery.state.value.message)
        } finally { recovery.close() }
    }
    @Test fun retiredLateFailureCannotOverwriteNewAttempt() = runBlocking {
        val old = CompletableDeferred<Unit>(); val fresh = CompletableDeferred<Unit>()
        val recovery = NativeWorkspaceEmptyRecovery(this)
        try {
            recovery.start { withContext(NonCancellable) { old.await(); error("old failure") } }
            yield(); recovery.cancel()
            recovery.start { fresh.await() }; yield()
            old.complete(Unit); yield()
            assertTrue(recovery.state.value.busy); assertNull(recovery.state.value.message)
            fresh.complete(Unit)
            withTimeout(2000) { while (recovery.state.value.busy) yield() }
            assertNull(recovery.state.value.message)
        } finally { old.complete(Unit); fresh.complete(Unit); recovery.close() }
    }
    @Test fun errorsAreActionableAndClosedOrCancelledOwnersCannotRestart() = runBlocking {
        val recovery = NativeWorkspaceEmptyRecovery(this)
        recovery.start { error("private transport details") }
        withTimeout(2000) { while (recovery.state.value.busy) yield() }
        assertEquals(NativeWorkspaceEmptyRecovery.FAILURE, recovery.state.value.message)
        recovery.close(); assertFalse(recovery.start { error("must not run") })
        assertEquals(NativeWorkspaceEmptyRecoveryState(), recovery.state.value)
        val cancelled = CoroutineScope(Job().apply { cancel() })
        assertFalse(NativeWorkspaceEmptyRecovery(cancelled).start {})
    }
}
