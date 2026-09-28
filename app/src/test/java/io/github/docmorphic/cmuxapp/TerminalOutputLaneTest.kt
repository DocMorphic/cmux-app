package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TerminalOutputLaneTest {
    private class Wire : TerminalLaneWire {
        val chunks = Channel<ByteArray>(16)
        val writes = Channel<ByteArray>(16)
        val retired = CompletableDeferred<Unit>()
        override suspend fun read() = chunks.receiveCatching().getOrNull() ?: byteArrayOf()
        override suspend fun write(bytes: ByteArray) { writes.send(bytes) }
        override suspend fun retire() { retired.complete(Unit); chunks.close() }
        override fun close() { chunks.close() }
    }
    private fun snapshot(text: String, end: ULong) = JSONObject().put("snapshot_data_b64", Base64.getEncoder().encodeToString(text.toByteArray()))
        .put("seq", java.math.BigInteger(end.toString()))
    private fun mirror() = TerminalStreamMirror("s", TerminalTransport(TerminalOutputMode.BYTES, false), TerminalViewport(40, 8))
    private fun text(mirror: TerminalStreamMirror) = RenderGrid.plainText(mirror.display.visibleLines())

    @Test fun fragmentedReplayThenChunksPreserveCursorAndInputOnSameWire() = runBlocking<Unit> {
        val wire = Wire()
        val cursor = Long.MAX_VALUE.toULong() + 10u
        val encoded = terminalEnvelope(start = cursor, bytes = "A".toByteArray()) +
            terminalEnvelope(2, cursor + 1u, "😀".toByteArray())
        wire.chunks.send(encoded.copyOfRange(0, 5)); wire.chunks.send(encoded.copyOfRange(5, encoded.size))
        IrxTerminalOutputLane(wire, cursor).use { lane ->
            assertEquals(cursor, lane.receive()!!.sequence)
            assertArrayEquals("😀".toByteArray(), lane.receive()!!.bytes)
            lane.send("x\r"); assertArrayEquals(TerminalLaneProtocol.input("x\r"), wire.writes.receive())
            wire.chunks.close(); assertNull(lane.receive()); assertTrue(lane.closed.value)
        }
        withTimeout(2000) { wire.retired.await() }
    }

    @Test fun wrongCursorMissingReplayRepeatedReplayAndTruncationRetireTheStream() = runBlocking<Unit> {
        for (bytes in listOf(terminalEnvelope(start = 11u), terminalEnvelope(2, 10u),
            terminalEnvelope(start = 10u) + terminalEnvelope(start = 10u), terminalEnvelope(start = 10u).copyOf(35))) {
            val wire = Wire(); wire.chunks.send(bytes); wire.chunks.close()
            val lane = IrxTerminalOutputLane(wire, 10u)
            assertTrue(runCatching { while (lane.receive() != null) { } }.isFailure)
            assertTrue(lane.closed.value); withTimeout(2000) { wire.retired.await() }
        }
    }

    @Test fun nativeAndEventOverlapIsRenderedOnceAboveSignedLongRange() {
        val mirror = mirror()
        val start = Long.MAX_VALUE.toULong() + 20u
        mirror.replay(snapshot("ready ", start))
        val bytes = "😀 café".toByteArray()
        mirror.lane(TerminalLaneProtocol.Output(true, start, start, start + bytes.size.toULong(), bytes))
        mirror.bytes(JSONObject().put("surface_id", "s").put("seq", java.math.BigInteger(start.toString()))
            .put("data_b64", Base64.getEncoder().encodeToString(bytes)))
        assertEquals("ready 😀 café", text(mirror))
        assertEquals(start + bytes.size.toULong(), mirror.nativeCursor)
        mirror.beginReplay()
        assertEquals(TerminalStreamMirror.Result.REPLAY,
            mirror.lane(TerminalLaneProtocol.Output(false, start, start + 20u, start + 21u, byteArrayOf(120))))
        assertEquals("ready 😀 café", text(mirror))
    }

    @Test fun gapSuspendsLaneUntilFreshRpcReplayAndReopensAtNewCursor() = runBlocking<Unit> {
        val mirror = mirror(); mirror.replay(snapshot("A", 10u))
        val opened = Channel<Pair<ULong, Wire>>(8)
        val resync = CompletableDeferred<Unit>()
        val owner = TerminalOutputLaneOwner(this, cursor = { mirror.nativeCursor }, useLane = { cursor, use ->
            val wire = Wire(); opened.send(cursor to wire)
            IrxTerminalOutputLane(wire, cursor).use { lane -> use(lane) }; true
        }, consume = mirror::lane, resync = { mirror.beginReplay(); resync.complete(Unit) })
        try {
            owner.resume()
            val (firstCursor, first) = withTimeout(2000) { opened.receive() }
            assertEquals(10uL, firstCursor); assertFalse(owner.send("before"))
            first.chunks.send(terminalEnvelope(start = 10u, bytes = "B".toByteArray()))
            withTimeout(2000) { owner.ready.first { it } }
            assertEquals("AB", text(mirror))
            assertTrue(owner.send("key")); assertArrayEquals(TerminalLaneProtocol.input("key"), first.writes.receive())
            first.chunks.send(terminalEnvelope(2, 20u, "gap".toByteArray()))
            withTimeout(2000) { resync.await(); first.retired.await() }
            assertFalse(owner.ready.value); assertEquals("AB", text(mirror)); assertTrue(opened.tryReceive().isFailure)
            mirror.replay(snapshot("recovered", 23u)); owner.resume()
            val (nextCursor, next) = withTimeout(2000) { opened.receive() }
            assertEquals(23uL, nextCursor)
            next.chunks.send(terminalEnvelope(start = 23u))
            withTimeout(2000) { owner.ready.first { it } }
            assertEquals("recovered", text(mirror))
        } finally { owner.close() }
    }

    @Test fun pausedOwnerCannotApplyLateNativeReadOrEnableInput() = runBlocking<Unit> {
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var deliveries = 0
        val lane = object : TerminalOutputLane {
            override val closed = kotlinx.coroutines.flow.MutableStateFlow(false)
            override suspend fun receive(): TerminalLaneProtocol.Output = withContext(NonCancellable) {
                reading.complete(Unit); release.await()
                TerminalLaneProtocol.Output(true, 5u, 5u, 6u, byteArrayOf(120))
            }
            override suspend fun send(text: String) { error("Stale input") }
            override fun close() { closed.value = true }
        }
        val owner = TerminalOutputLaneOwner(this, { 5uL }, { _, use ->
            try { use(lane); true } finally { lane.close(); finished.complete(Unit) }
        }, { deliveries++; TerminalStreamMirror.Result.APPLIED }, { error("Unexpected replay") })
        try {
            owner.resume(); withTimeout(2000) { reading.await() }
            owner.pause(); release.complete(Unit)
            withTimeout(2000) { finished.await() }
            assertEquals(0, deliveries); assertFalse(owner.ready.value); assertFalse(owner.send("rpc"))
            assertTrue(lane.closed.value)
        } finally { release.complete(Unit); owner.close() }
    }

    @Test fun boundedOpenFailuresLeaveFallbackWithoutResyncLoop() = runBlocking<Unit> {
        var attempts = 0
        val owner = TerminalOutputLaneOwner(this, { 5uL }, { _, _ -> attempts++; throw java.io.IOException("unsupported") },
            { error("Unexpected frame") }, { error("Unbounded resync") })
        try {
            owner.resume()
            withTimeout(2000) { while (attempts < 3) delay(5) }
            delay(50)
            assertEquals(3, attempts); assertFalse(owner.send("rpc"))
        } finally { owner.close() }
    }
}
