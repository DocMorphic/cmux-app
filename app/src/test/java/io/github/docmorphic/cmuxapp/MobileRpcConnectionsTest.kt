package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

internal class PoolTestTransport : MobileRpcTransport {
    val incoming = Channel<ByteArray>(16)
    val sent = Channel<JSONObject>(16)
    val connects = AtomicInteger()
    val closes = AtomicInteger()
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun connect() { connects.incrementAndGet(); gate?.await() }
    override suspend fun read() = incoming.receiveCatching().getOrNull()
    override suspend fun write(bytes: ByteArray) {
        sent.send(JSONObject(MobileFrameDecoder().feed(bytes).single().toString(Charsets.UTF_8)))
    }
    suspend fun answer(request: JSONObject) {
        incoming.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id"))
            .put("ok", true).put("result", JSONObject().put("method", request.getString("method"))).toString().toByteArray()))
    }
    override fun close() { closes.incrementAndGet(); incoming.close(); gate?.cancel() }
}

class MobileRpcConnectionsTest {
    @Test fun consumersShareOneWireButOwnSubscriptionsAndCloseIndependently() = runBlocking<Unit> {
        val transport = PoolTestTransport()
        MobileRpcConnections().use { pool ->
            val first = pool.acquire("mac", { true }) { MobileRpcClient(transport, { "test-token" }) }
            val second = pool.acquire("mac", { true }) { error("Opened a duplicate wire") }
            val firstSubscription = async { first.subscribe(listOf("workspace.list.changed")) }
            val a = transport.sent.receive(); transport.answer(a); firstSubscription.await()
            val secondSubscription = async { second.subscribe(listOf("notification.feed.changed")) }
            val b = transport.sent.receive(); transport.answer(b); secondSubscription.await()
            assertNotEquals(a.getJSONObject("params").getString("client_id"), b.getJSONObject("params").getString("client_id"))
            first.close(); first.close()
            assertEquals(0, transport.closes.get())
            assertTrue(runCatching { first.workspaces() }.isFailure)
            val workspaces = async { second.workspaces() }
            transport.answer(transport.sent.receive())
            assertEquals("mobile.workspace.list", withTimeout(2000) { workspaces.await() }.getString("method"))
            second.close()
            assertEquals(1, transport.connects.get())
            assertEquals(1, transport.closes.get())
        }
    }

    @Test fun closingLeaseCancelsOnlyItsPendingReplies() = runBlocking<Unit> {
        val transport = PoolTestTransport()
        MobileRpcConnections().use { pool ->
            val a = pool.acquire("mac", { true }) { MobileRpcClient(transport, { "test-token" }) }
            val b = pool.acquire("mac", { true }) { error("duplicate") }
            val abandoned = async { runCatching { a.workspaces() } }
            val old = transport.sent.receive()
            val remaining = async { b.notifications() }
            val current = transport.sent.receive()
            a.close()
            assertTrue(withTimeout(1000) { abandoned.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0, transport.closes.get())
            transport.answer(old); transport.answer(current)
            assertEquals("notification.feed.list", withTimeout(2000) { remaining.await() }.getString("method"))
            b.close()
        }
    }

    @Test fun revocationWakesEveryConsumerAndOldReleasesCannotCloseReplacement() = runBlocking<Unit> {
        val firstTransport = PoolTestTransport()
        val replacement = PoolTestTransport()
        MobileRpcConnections().use { pool ->
            val a = pool.acquire("mac", { true }) { MobileRpcClient(firstTransport, { "test-token" }) }
            val b = pool.acquire("mac", { true }) { error("duplicate") }
            val disconnectedA = async(start = CoroutineStart.UNDISPATCHED) { a.disconnected.first() }
            val disconnectedB = async(start = CoroutineStart.UNDISPATCHED) { b.disconnected.first() }
            pool.retain(emptySet())
            withTimeout(1000) { disconnectedA.await(); disconnectedB.await() }
            val c = pool.acquire("mac", { true }) { MobileRpcClient(replacement, { "test-token" }) }
            a.close(); b.close()
            assertEquals(0, replacement.closes.get())
            assertEquals(1, firstTransport.closes.get())
            c.close()
            assertEquals(1, replacement.closes.get())
        }
    }

    @Test fun concurrentAcquisitionDoesNotDialTwiceAndCloseAbortsAdmission() = runBlocking<Unit> {
        val transport = PoolTestTransport().apply { gate = CompletableDeferred() }
        val pool = MobileRpcConnections()
        val a = async { runCatching { pool.acquire("mac", { true }) { MobileRpcClient(transport, { "test-token" }) } } }
        withTimeout(2000) { while (transport.connects.get() == 0) delay(1) }
        val b = async { runCatching { pool.acquire("mac", { true }) { error("duplicate") } } }
        pool.close()
        withTimeout(2000) { assertTrue(a.await().isFailure); assertTrue(b.await().isFailure) }
        assertEquals(1, transport.closes.get())
    }

    @Test fun revokedScopeCannotBorrowEvenAnExistingConnection() = runBlocking<Unit> {
        MobileRpcConnections().use { pool ->
            val a = pool.acquire("mac", { true }) { MobileRpcClient(PoolTestTransport(), { "test-token" }) }
            assertTrue(runCatching { pool.acquire("mac", { false }) { error("must not dial") } }.isFailure)
            a.close()
        }
    }
}
