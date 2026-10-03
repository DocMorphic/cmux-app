package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TerminalInputQueueTest {
    @Test fun binaryMouseBytesAreCopiedOrderedBehindImagesAndErasedAfterDelivery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ready = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        var borrowed: ByteArray? = null
        val queue = TerminalInputQueue(scope) { entry ->
            borrowed = entry.rawBytes
            assertArrayEquals(byteArrayOf(27, 91, 77, 32, 183.toByte(), 35), entry.rawBytes)
            order += "mouse"
        }
        try {
            queue.offerAction({}) { ready.await(); order += "image" }
            val mouse = byteArrayOf(27, 91, 77, 32, 183.toByte(), 35)
            assertTrue(queue.offerBytes(mouse)); mouse.fill(0)
            assertTrue(order.isEmpty())
            ready.complete(Unit); queue.awaitIdle()
            assertEquals(listOf("image", "mouse"), order)
            assertTrue(borrowed!!.all { it == 0.toByte() })
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun binaryOverflowDropsWaitingInputAndNeverMutatesTheCallersBuffer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = TerminalInputQueue(scope) { error("Discarded binary input must not run") }
        try {
            queue.offerAction({}) { awaitCancellation() }
            val bytes = ByteArray(TerminalInputQueue.MAX_PENDING_BYTES) { 7 }
            assertFalse(queue.offerBytes(bytes))
            assertNotNull(queue.status.value.error)
            assertTrue(bytes.all { it == 7.toByte() })
            queue.close(); assertFalse(queue.offerBytes(byteArrayOf(1)))
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun imagePreparationReservesItsPlaceAheadOfLaterKeysAndReleasesOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val prepared = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        var released = 0
        val queue = TerminalInputQueue(scope) { sent += it.text }
        try {
            queue.offer("before")
            assertTrue(queue.offerAction({ released++ }) { prepared.await(); sent += "image" })
            queue.offer("after")
            assertEquals(listOf("before"), sent)
            assertEquals(0, released)
            prepared.complete(Unit)
            queue.awaitIdle()
            assertEquals(listOf("before", "image", "after"), sent)
            queue.close()
            assertEquals(1, released)
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun ambiguousImageFailureReleasesWaitingGrantsAndNeverReplaysImages() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fail = CompletableDeferred<Unit>()
        var released = 0
        var attempted = 0
        val queue = TerminalInputQueue(scope) { error("Keys after uncertain image must be discarded") }
        try {
            queue.offerAction({ released++ }) { attempted++; fail.await(); error("Lost paste acknowledgement") }
            queue.offerAction({ released++ }) { attempted++ }
            queue.offer("after image")
            fail.complete(Unit)
            assertNotNull(queue.status.value.error)
            assertEquals(2, released)
            assertEquals(1, attempted)
            assertTrue(queue.resume())
            queue.awaitIdle()
            assertEquals(1, attempted)
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun richInputIsBoundedAndClosingReleasesRunningQueuedAndRejectedGrants() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var released = 0
        var started = 0
        val queue = TerminalInputQueue(scope) {}
        try {
            repeat(TerminalInputQueue.MAX_PENDING_ACTIONS) {
                assertTrue(queue.offerAction({ released++ }) { started++; awaitCancellation() })
            }
            assertFalse(queue.offerAction({ released++ }) { error("Excess input must not run") })
            assertEquals(1, released)
            assertEquals(1, started)
            queue.close()
            assertEquals(TerminalInputQueue.MAX_PENDING_ACTIONS + 1, released)
            assertFalse(queue.offerAction({ released++ }) { error("Closed input must not run") })
            assertEquals(TerminalInputQueue.MAX_PENDING_ACTIONS + 2, released)
        } finally { queue.close(); scope.cancel() }
    }

    @Test fun closingBeforeWorkerStartsStillReleasesProviderGrants() = runBlocking {
        val tasks = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.addLast(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var released = 0
        val queue = TerminalInputQueue(scope) {}
        try {
            assertTrue(queue.offerAction({ released++ }) { error("Disposed content must not be opened") })
            queue.close()
            assertEquals(1, released)
        } finally {
            queue.close(); scope.cancel()
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    @Test fun cancellingOwnerScopeDiscardsGrantsAndRejectsNewContent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var released = 0
        val queue = TerminalInputQueue(scope) {}
        queue.offerAction({ released++ }) { awaitCancellation() }
        queue.offerAction({ released++ }) { error("Cancelled paste must not run") }
        scope.cancel()
        assertTrue(queue.status.value.closed)
        assertEquals(2, released)
        assertFalse(queue.offerAction({ released++ }) { error("Stopped worker must not accept input") })
        assertEquals(3, released)
    }

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
