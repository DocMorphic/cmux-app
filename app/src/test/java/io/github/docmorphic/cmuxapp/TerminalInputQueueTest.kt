package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TerminalInputQueueTest {
    @Test fun rpcTimeoutPausesButDoesNotKillTheLaneBeforeExplicitResume() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val attempted = mutableListOf<String>()
        val queue = TerminalInputQueue(scope) {
            attempted += it.text
            if (attempted.size == 1) withTimeout(10) { awaitCancellation() }
        }
        try {
            queue.offer("timeout")
            withTimeout(3000) { while (queue.status.value.error == null) yield() }
            assertTrue(queue.resume())
            queue.offer("new input")
            withTimeout(3000) { queue.awaitIdle() }
            assertEquals(listOf("timeout", "new input"), attempted)
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun keysAndPasteKeepTheirOrderUntilAcknowledged() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val delivered = mutableListOf<TerminalInputQueue.Entry>()
        val queue = TerminalInputQueue(scope) {
            delivered += it
            if (delivered.size == 1) release.await()
        }
        try {
            queue.offer("i")
            queue.offer("中文🙂")
            queue.offer("line one\nline two", paste = true)
            queue.offer("\u001b")
            assertEquals(listOf("i"), delivered.map { it.text })
            val waiting = async { queue.awaitIdle() }
            yield(); assertFalse(waiting.isCompleted)
            release.complete(Unit)
            withTimeout(3000) { waiting.await() }
            assertEquals(listOf("i", "中文🙂", "line one\nline two", "\u001b"), delivered.map { it.text })
            assertTrue(delivered[2].paste)
            assertEquals(0, queue.status.value.pendingBytes)
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun failedInputDropsQueuedBytesAndRequiresExplicitResumeWithoutReplay() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val attempted = mutableListOf<String>()
        val queue = TerminalInputQueue(scope) {
            attempted += it.text
            if (attempted.size == 1) { release.await(); error("Lost acknowledgement") }
        }
        try {
            queue.offer("first")
            queue.offer("must not be replayed")
            release.complete(Unit)
            assertNotNull(queue.status.value.error)
            assertFalse(queue.offer("while paused"))
            assertTrue(runCatching { queue.awaitIdle() }.isFailure)
            assertTrue(queue.resume())
            queue.offer("new input")
            queue.awaitIdle()
            assertEquals(listOf("first", "new input"), attempted)
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun overflowWaitsForInflightRequestAndClosedQueueCannotSend() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val attempted = mutableListOf<String>()
        val queue = TerminalInputQueue(scope) { attempted += it.text; release.await() }
        try {
            queue.offer("x")
            assertFalse(queue.offer("y".repeat(TerminalInputQueue.MAX_PENDING_BYTES)))
            assertFalse(queue.resume())
            release.complete(Unit)
            assertTrue(queue.resume())
            queue.close()
            assertFalse(queue.offer("after close"))
            assertFalse(queue.resume())
            assertEquals(listOf("x"), attempted)
        } finally { queue.close(); scope.cancel() }
    }
}
