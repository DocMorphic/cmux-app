package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MobileDebugLogTest {
    @Test fun clearingRetainsOnlyLinesRecordedAfterTheSharedCutoff() {
        var now = 100L
        val ring = DebugLogBuffer(now = { now })
        ring.begin(DebugOperation.RPC_HOST); now = 200
        ring.begin(DebugOperation.SSH_CONNECT); ring.clearThrough(150)
        assertFalse(ring.snapshot().contains("RPC_HOST")); assertTrue(ring.snapshot().contains("SSH_CONNECT"))
    }
    @Test fun evictionHonorsBothLineAndCharacterLimits() {
        var nanos = 0L
        val ring = DebugLogBuffer(3, 512) { nanos }
        val first = ring.begin(DebugOperation.RPC_HOST)
        nanos = 25_000_000; ring.finish(first, DebugOutcome.SUCCESS)
        ring.begin(DebugOperation.RPC_WORKSPACE); ring.begin(DebugOperation.RPC_BROWSER)
        val snapshot = ring.snapshot()
        assertTrue(snapshot.startsWith("3 lines; 1 older entries discarded"))
        assertTrue(snapshot.contains("RPC_HOST SUCCESS 25ms"))
        assertFalse(snapshot.contains("RPC_HOST STARTED"))
        val bounded = DebugLogBuffer(4000, 256)
        repeat(100) { bounded.begin(DebugOperation.RPC_NOTIFICATIONS) }
        assertTrue(bounded.snapshot().substringAfter('\n').length <= 256)
        assertTrue(bounded.snapshot().contains("#100"))
    }
    @Test fun concurrentWritersAndSnapshotsHaveOrderedUniqueAdmissionIds() {
        val ring = DebugLogBuffer(4000, 300_000)
        val workers = Executors.newFixedThreadPool(4)
        try {
            repeat(4) { workers.submit { repeat(300) { ring.begin(DebugOperation.SSH_IO); ring.snapshot() } } }
            workers.shutdown(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
            val ids = Regex("#(\\d+)").findAll(ring.snapshot()).map { it.groupValues[1].toInt() }.toList()
            assertEquals((1..1200).toList(), ids)
        } finally { workers.shutdownNow() }
    }
    @Test fun labelsNeverIncludeMethodArgumentsOrFailureMessages() {
        val secret = "private-token@example.test"
        assertEquals(DebugOperation.RPC_OTHER, debugRpcOperation(secret))
        assertEquals(DebugOperation.RPC_TERMINAL, debugRpcOperation("mobile.terminal.$secret"))
        val ring = DebugLogBuffer()
        ring.finish(ring.begin(debugRpcOperation(secret)), debugOutcome(MobileRpcException(secret, secret)))
        assertTrue(ring.snapshot().contains("REMOTE_ERROR"))
        assertFalse(ring.snapshot().contains(secret))
    }
    @Test fun tracingPreservesReturnValueAndExactFailure() = runBlocking {
        val value = Any()
        assertSame(value, MobileDebugLog.trace(DebugOperation.RPC_HOST) { value })
        val failure = java.io.IOException("private exception text")
        assertSame(failure, runCatching { MobileDebugLog.trace(DebugOperation.SSH_IO) { throw failure } }.exceptionOrNull())
        assertTrue(MobileDebugLog.snapshot().contains("IO_ERROR"))
        assertFalse(MobileDebugLog.snapshot().contains("private exception text"))
    }
    @Test fun cancellationAndTimeoutStayDistinctAndPropagate() = runBlocking {
        val failure = CancellationException("private cancellation")
        assertSame(failure, runCatching { MobileDebugLog.trace(DebugOperation.RPC_TERMINAL) { throw failure } }.exceptionOrNull())
        val timeout = runCatching { MobileDebugLog.trace(DebugOperation.RPC_BROWSER) { withTimeout(1) { awaitCancellation() } } }.exceptionOrNull()
        assertTrue(timeout is TimeoutCancellationException)
        assertTrue(MobileDebugLog.snapshot().contains("CANCELLED"))
        assertTrue(MobileDebugLog.snapshot().contains("TIMEOUT"))
    }
}
