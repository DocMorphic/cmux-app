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
    @Test fun slowMacAdmissionDoesNotDelayOtherMacs() = runBlocking<Unit> {
        val slow = PoolTestTransport().apply { gate = CompletableDeferred() }
        val healthy = PoolTestTransport()
        MobileRpcConnections().use { pool ->
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { pool.acquire("slow", { true }) { MobileRpcClient(slow, { "fixture" }) } }
            }
            val quick = async { runCatching { pool.acquire("healthy", { true }) { MobileRpcClient(healthy, { "fixture" }) } } }
            try {
                val lease = withTimeout(1_000) { quick.await().getOrThrow() }
                assertEquals(1, slow.connects.get()); assertFalse(pending.isCompleted)
                lease.close()
            } finally { pool.close(); pending.await(); quick.await() }
        }
    }

    @Test fun slowHostProbeDoesNotDelayOtherMacAndRevocationIsScoped() = runBlocking<Unit> {
        val slow = PoolTestTransport()
        val healthy = PoolTestTransport().apply { gate = CompletableDeferred() }
        MobileRpcConnections().use { pool ->
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { pool.acquire("slow", { true }, validate = { it.hostStatus() }) { MobileRpcClient(slow, { "fixture" }) } }
            }
            withTimeout(2000) { slow.sent.receive() }
            val quick = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { pool.acquire("healthy", { true }) { MobileRpcClient(healthy, { "fixture" }) } }
            }
            try {
                assertEquals(1, healthy.connects.get())
                pool.retain(setOf("healthy"))
                assertTrue(withTimeout(2000) { pending.await() }.isFailure)
                assertEquals(0, healthy.closes.get())
                healthy.gate!!.complete(Unit)
                val lease = withTimeout(2000) { quick.await().getOrThrow() }
                val request = async { lease.workspaces() }
                healthy.answer(withTimeout(2000) { healthy.sent.receive() })
                assertEquals("mobile.workspace.list", withTimeout(2000) { request.await() }.getString("method"))
                lease.close()
            } finally { pool.close(); pending.await(); quick.await() }
        }
    }

    @Test fun cancellingSameMacWaiterPreservesDialAndOtherWaitersShareTheWire() = runBlocking<Unit> {
        val wire = PoolTestTransport().apply { gate = CompletableDeferred() }
        MobileRpcConnections().use { pool ->
            val first = async(start = CoroutineStart.UNDISPATCHED) { pool.acquire("mac", { true }) { MobileRpcClient(wire, { "fixture" }) } }
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { pool.acquire("mac", { true }) { error("duplicate") } }
            val remaining = async(start = CoroutineStart.UNDISPATCHED) { pool.acquire("mac", { true }) { error("duplicate") } }
            cancelled.cancelAndJoin()
            assertFalse(first.isCompleted); assertEquals(0, wire.closes.get())
            wire.gate!!.complete(Unit)
            val a = withTimeout(2000) { first.await() }
            val b = withTimeout(2000) { remaining.await() }
            assertEquals(1, wire.connects.get())
            a.close(); assertFalse(b.isClosed); assertEquals(0, wire.closes.get())
            b.close(); assertEquals(1, wire.closes.get())
        }
    }

    @Test fun closeAbortsAllParallelCandidatesAndSameMacWaiters() = runBlocking<Unit> {
        val wires = List(3) { PoolTestTransport().apply { gate = CompletableDeferred() } }
        MobileRpcConnections().use { pool ->
            val attempts = wires.mapIndexed { index, wire -> async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { pool.acquire("mac-$index", { true }) { MobileRpcClient(wire, { "fixture" }) } }
            } }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { pool.acquire("mac-0", { true }) { error("duplicate") } }
            }
            assertTrue(wires.all { it.connects.get() == 1 })
            pool.close()
            withTimeout(2000) { assertTrue((attempts + waiter).awaitAll().all { it.isFailure }) }
            assertTrue(wires.all { it.closes.get() == 1 })
        }
    }

    @Test fun capacityCountsPendingMacsAndIsReclaimedAfterCancellationAndRelease() = runBlocking<Unit> {
        MobileRpcConnections().use { pool ->
            val wires = List(64) { PoolTestTransport().apply { gate = CompletableDeferred() } }
            val pending = wires.mapIndexed { index, wire -> async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { pool.acquire("mac-$index", { true }) { MobileRpcClient(wire, { "fixture" }) } }
            } }
            try {
                assertTrue(wires.all { it.connects.get() == 1 })
                var created = false
                val overflow = runCatching { pool.acquire("overflow", { true }) {
                    created = true; MobileRpcClient(PoolTestTransport(), { "fixture" })
                } }
                assertEquals("Too many active Mac connections", overflow.exceptionOrNull()?.message)
                assertFalse(created)
                val sameMac = async(start = CoroutineStart.UNDISPATCHED) {
                    pool.acquire("mac-1", { true }) { error("duplicate") }
                }
                wires[1].gate!!.complete(Unit)
                val existing = withTimeout(2000) { pending[1].await().getOrThrow() }
                withTimeout(2000) { sameMac.await() }.close()
                assertFalse(existing.isClosed)
                pending[0].cancelAndJoin()
                assertEquals(1, wires[0].closes.get())
                // Each completed lease frees both the wire and its admission reservation.
                repeat(70) { index ->
                    pool.acquire("replacement-$index", { true }) { MobileRpcClient(PoolTestTransport(), { "fixture" }) }.close()
                }
                existing.close()
            } finally { pool.close(); pending.joinAll() }
        }
    }

    @Test fun admissionProbeMustFinishBeforeAnyLeaseCanBeBorrowed() = runBlocking<Unit> {
        val transport = PoolTestTransport()
        MobileRpcConnections().use { pool ->
            val pending = async { pool.acquire("mac", { true }, validate = { it.hostStatus() }) { MobileRpcClient(transport, { "fixture" }) } }
            val request = withTimeout(2000) { transport.sent.receive() }
            assertNull(pool.borrowIfConnected("mac", { true }))
            transport.answer(request)
            val lease = withTimeout(2000) { pending.await() }
            assertNotNull(pool.borrowIfConnected("mac", { true })?.also { it.close() })
            lease.close()
        }
    }

    @Test fun revokedCandidateDuringHostProbeCannotPublishALease() = runBlocking<Unit> {
        val transport = PoolTestTransport()
        MobileRpcConnections().use { pool ->
            val pending = async { runCatching { pool.acquire("mac", { true }, validate = { it.hostStatus() }) { MobileRpcClient(transport, { "fixture" }) } } }
            withTimeout(2000) { transport.sent.receive() }
            pool.retain(emptySet())
            assertTrue(withTimeout(2000) { pending.await() }.isFailure)
            assertNull(pool.borrowIfConnected("mac", { true }))
            assertTrue(transport.closes.get() > 0)
        }
    }
    @Test fun settingsBorrowOnlyLiveAuthorizedWireAndReleaseIndependently() = runBlocking<Unit> {
        val transport = PoolTestTransport()
        MobileRpcConnections().use { pool ->
            assertNull(pool.borrowIfConnected("mac", { true }))
            val active = pool.acquire("mac", { true }) { MobileRpcClient(transport, { "token" }) }
            assertNull(pool.borrowIfConnected("other", { true }))
            assertNull(pool.borrowIfConnected("mac", { false }))
            val settings = requireNotNull(pool.borrowIfConnected("mac", { true }))
            settings.close(); assertFalse(active.isClosed); assertEquals(1, transport.connects.get())
            pool.retain(emptySet()); assertNull(pool.borrowIfConnected("mac", { true }))
            active.close()
        }
    }
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
