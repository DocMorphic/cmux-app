package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class MobileRpcTransportTest {
    private class MemoryTransport : MobileRpcTransport {
        val incoming = Channel<ByteArray>(16)
        val pushed = Channel<ByteArray>(16)
        val sent = Channel<ByteArray>(16)
        val closes = AtomicInteger()
        var writeFailure: Throwable? = null
        var stallWrite = false
        var writeGate: CompletableDeferred<Unit>? = null
        var blockingWrite: java.util.concurrent.CountDownLatch? = null
        override val independentEvents = pushed.receiveAsFlow()
        override suspend fun connect() { }
        override suspend fun read() = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            sent.send(bytes)
            writeFailure?.let { throw it }
            writeGate?.await()
            blockingWrite?.await()
            if (stallWrite) awaitCancellation()
        }
        override fun close() {
            closes.incrementAndGet(); incoming.close(); pushed.close(); writeGate?.cancel(); blockingWrite?.countDown()
        }
        suspend fun request() = JSONObject(MobileFrameDecoder().feed(sent.receive()).single().toString(Charsets.UTF_8))
        suspend fun answer(request: JSONObject) {
            val bytes = MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", true)
                .put("result", JSONObject().put("received", true)).toString().toByteArray())
            incoming.send(bytes.copyOfRange(0, 3)); incoming.send(bytes.copyOfRange(3, bytes.size))
        }
    }

    @Test fun independentEventsAndFragmentedControlRepliesReachTheExistingRpcClient() = runBlocking<Unit> {
        val transport = MemoryTransport()
        MobileRpcClient(transport, { "fixture-token" }).use { client ->
            client.connect()
            val event = async(start = CoroutineStart.UNDISPATCHED) { client.events.first() }
            val response = async { client.workspaces() }
            val request = withTimeout(2000) { transport.request() }
            assertEquals("fixture-token", request.getJSONObject("auth").getString("stack_access_token"))
            transport.pushed.send("""{"kind":"event","topic":"workspace.list.changed","payload":{"marker":7},"stream_id":"events"}""".toByteArray())
            transport.answer(request)
            assertTrue(withTimeout(2000) { response.await() }.getBoolean("received"))
            assertEquals(7, withTimeout(2000) { event.await() }.payload.getInt("marker"))
        }
        assertEquals(1, transport.closes.get())
    }

    @Test fun cancellingScreenDuringStartedFrameFinishesItWithoutRetiringSharedConnection() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val transport = MemoryTransport().apply { writeGate = gate }
        MobileRpcClient(transport, { "fixture-token" }).use { client ->
            client.connect()
            val screen = launch { client.request("mobile.terminal.viewport") }
            val viewport = withTimeout(2000) { transport.request() }
            screen.cancel()
            // The provider has the frame, but its suspend write has not returned yet.
            gate.complete(Unit)
            screen.join()
            assertEquals(0, transport.closes.get())
            assertFalse(client.isClosed)
            transport.writeGate = null
            transport.answer(viewport)
            val later = async { client.workspaces() }
            transport.answer(withTimeout(2000) { transport.request() })
            assertTrue(withTimeout(2000) { later.await() }.getBoolean("received"))
            assertTrue(transport.sent.tryReceive().isFailure)
        }
    }

    @Test fun cancellingWhileWaitingForWriterNeverTransmitsTheCancelledCommand() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val transport = MemoryTransport().apply { writeGate = gate }
        MobileRpcClient(transport, { "fixture-token" }).use { client ->
            client.connect()
            val first = async { client.workspaces() }
            val listing = withTimeout(2000) { transport.request() }
            val queued = launch(start = CoroutineStart.UNDISPATCHED) { client.request("terminal.input") }
            queued.cancelAndJoin()
            gate.complete(Unit)
            transport.answer(listing)
            assertTrue(first.await().getBoolean("received"))
            assertEquals(0, transport.closes.get())
            assertTrue(transport.sent.tryReceive().isFailure)
        }
    }

    @Test fun writeDeadlineClosesEvenAJavaBlockingWriter() = runBlocking<Unit> {
        val gate = java.util.concurrent.CountDownLatch(1)
        val transport = MemoryTransport().apply { blockingWrite = gate }
        // Keep a broken implementation from hanging the entire test worker indefinitely.
        val failsafe = Thread { try { Thread.sleep(5000); gate.countDown() } catch (_: InterruptedException) {} }
            .apply { isDaemon = true; start() }
        try {
            MobileRpcClient(transport, { "fixture-token" }).use { client ->
                client.connect()
                val start = System.nanoTime()
                val failure = runCatching { client.request("terminal.input", timeoutMillis = 100) }.exceptionOrNull()
                assertNotNull(failure)
                assertEquals(1, transport.closes.get())
                assertTrue("Blocking write must be released by its deadline", (System.nanoTime() - start) / 1_000_000 < 2000)
                assertEquals("terminal.input", transport.request().getString("method"))
                assertTrue(transport.sent.tryReceive().isFailure)
            }
        } finally { gate.countDown(); failsafe.interrupt() }
    }

    @Test fun closeFailsPendingRequestImmediatelyAndNeverReusesTheTransport() = runBlocking<Unit> {
        val transport = MemoryTransport()
        val client = MobileRpcClient(transport, { "fixture-token" })
        client.connect()
        val response = async { runCatching { client.workspaces() } }
        withTimeout(2000) { transport.request() }
        client.close()
        assertTrue(withTimeout(500) { response.await() }.isFailure)
        assertTrue(runCatching { client.connect() }.isFailure)
        assertTrue(runCatching { client.workspaces() }.isFailure)
        assertEquals(1, transport.closes.get())
        assertTrue(transport.sent.tryReceive().isFailure)
    }

    @Test fun uncertainPartialWriteRetiresTransportAndIsNotRepeated() = runBlocking<Unit> {
        val transport = MemoryTransport().apply { writeFailure = IOException("partial write") }
        MobileRpcClient(transport, { "fixture-token" }).use { client ->
            client.connect()
            assertTrue(runCatching { client.request("terminal.input", JSONObject().put("text", "once")) }.isFailure)
            assertEquals("terminal.input", transport.request().getString("method"))
            assertEquals(1, transport.closes.get())
            assertTrue(runCatching { client.workspaces() }.isFailure)
            assertTrue(transport.sent.tryReceive().isFailure)
        }
    }

    @Test fun blockedNativeWriteHasDeadlineAndClosesWithoutReplay() = runBlocking<Unit> {
        val transport = MemoryTransport().apply { stallWrite = true }
        MobileRpcClient(transport, { "fixture-token" }).use { client ->
            client.connect()
            val failure = runCatching { client.request("terminal.input", timeoutMillis = 100) }.exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
            assertEquals(1, transport.closes.get())
            transport.request()
            assertTrue(transport.sent.tryReceive().isFailure)
        }
    }

    @Test fun responseTimeoutDoesNotReplayAndLateReplyCannotAnswerAnotherRequest() = runBlocking<Unit> {
        val transport = MemoryTransport()
        MobileRpcClient(transport, { "fixture-token" }).use { client ->
            client.connect()
            val first = async { runCatching { client.request("terminal.input", timeoutMillis = 100) } }
            val old = transport.request()
            assertTrue(first.await().exceptionOrNull() is TimeoutCancellationException)
            assertEquals(0, transport.closes.get())
            val next = async { client.workspaces() }
            val current = transport.request()
            transport.answer(old)
            transport.answer(current)
            assertTrue(withTimeout(2000) { next.await() }.getBoolean("received"))
            assertTrue(transport.sent.tryReceive().isFailure)
        }
    }
}
