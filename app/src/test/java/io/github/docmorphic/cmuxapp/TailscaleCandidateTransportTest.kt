package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TailscaleCandidateTransportTest {
    private val first = PairingCode.Route("100.99.1.2", 58465)
    private val second = first.copy(host = "100.99.1.3")
    private class Wire : MobileRpcTransport {
        var closed = false
        val writes = mutableListOf<ByteArray>()
        var failConnect = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun connect() { gate?.await(); check(!closed && !failConnect) }
        override suspend fun read(): ByteArray? = byteArrayOf(8)
        override suspend fun write(bytes: ByteArray) { check(!closed); writes += bytes }
        override fun close() { closed = true; gate?.cancel() }
    }
    @Test fun triesOnlyCapturedRoutesAndExchangesDataOnFirstReachableOne() = runBlocking<Unit> {
        val a = Wire().apply { failConnect = true }; val b = Wire()
        val attempts = mutableListOf<PairingCode.Route>()
        TailscaleCandidateTransport(listOf(first, second), { true }) { attempts += it; if (it == first) a else b }.use { transport ->
            transport.connect(); transport.connect()
            assertEquals(listOf(first, second), attempts); assertTrue(a.closed)
            transport.write(byteArrayOf(7)); assertArrayEquals(byteArrayOf(7), b.writes.single())
            assertArrayEquals(byteArrayOf(8), transport.read())
        }
        assertTrue(b.closed)
    }
    @Test fun emptyOrRetiredRoutesNeverCreateTransport() = runBlocking<Unit> {
        TailscaleCandidateTransport(emptyList(), { true }) { error("must not create") }.use {
            assertTrue(runCatching { it.connect() }.isFailure)
        }
        TailscaleCandidateTransport(listOf(first), { false }) { error("must not create") }.use {
            assertTrue(runCatching { it.connect() }.isFailure)
        }
    }
    @Test fun allFailedCandidatesCloseWithoutAnotherConnectionMethod() = runBlocking<Unit> {
        val wires = mutableListOf<Wire>()
        val transport = TailscaleCandidateTransport(listOf(first, second), { true }) { Wire().apply { failConnect = true; wires += this } }
        assertTrue(runCatching { transport.connect() }.isFailure)
        assertEquals(2, wires.size); assertTrue(wires.all { it.closed })
        assertTrue(runCatching { transport.connect() }.isFailure)
        assertEquals(2, wires.size)
    }
    @Test fun closingPendingCandidateCancelsItAndDoesNotTryAnotherRoute() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val wire = Wire().apply { gate = CompletableDeferred() }
        var attempts = 0
        val transport = TailscaleCandidateTransport(listOf(first, second), { true }) { attempts++; entered.complete(Unit); wire }
        val job = async { runCatching { transport.connect() } }
        entered.await(); yield(); transport.close()
        assertTrue(withTimeout(2000) { job.await() }.isFailure)
        assertEquals(1, attempts); assertTrue(wire.closed)
    }
    @Test fun retiredPermissionClosesInsteadOfWritingOrTryingAnotherRoute() = runBlocking<Unit> {
        var allowed = true; var attempts = 0; val wire = Wire()
        val transport = TailscaleCandidateTransport(listOf(first, second), { allowed }) { attempts++; wire }
        transport.connect(); allowed = false
        assertTrue(runCatching { transport.write(byteArrayOf(9)) }.isFailure)
        assertTrue(wire.closed); assertTrue(wire.writes.isEmpty()); assertEquals(1, attempts)
    }
}
