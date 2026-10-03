package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

/** Native lane boundaries exercised with the compiled upstream Swift acknowledgement. */
class TerminalLaneAcknowledgementTest {
    private val golden = JSONObject(javaClass.getResource("/terminal-input-delivery-204a11d.json")!!.readText())
    private val surface = golden.getString("surface")
    private val identity = TerminalInputDelivery(UUID.fromString(surface), UUID.fromString(golden.getString("stream")), ULong.MAX_VALUE)
    private val ackRow = golden.getJSONArray("acknowledgements").getJSONObject(0)
    private fun bytes(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val ackWire get() = bytes(ackRow.getString("envelope"))
    private val ack get() = TerminalInputAcknowledgement.decode(bytes(ackRow.getString("body")))
    private class Wire : TerminalLaneWire {
        val incoming = Channel<ByteArray>(128)
        val written = Channel<ByteArray>(128)
        val retired = CompletableDeferred<Unit>()
        var failWrite = false
        override suspend fun read() = incoming.receiveCatching().getOrNull() ?: byteArrayOf()
        override suspend fun write(bytes: ByteArray) { written.send(bytes); if (failWrite) throw IOException("uncertain write") }
        override suspend fun retire() { retired.complete(Unit); incoming.close() }
        override fun close() { incoming.close() }
    }

    @Test fun inputLanePipelinesBeforeAckAndDeliversFragmentedAndBufferedAcksWithoutDropping() = runBlocking<Unit> {
        val wire = Wire().apply { incoming.send(terminalEnvelope()) }
        IrxTerminalInputLane.open(wire, surface).use { lane ->
            assertTrue(lane.supportsIdentifiedInput)
            withTimeout(2000) {
                lane.sendIdentified("one", identity)
                lane.sendIdentified("two", identity.copy(sequence = 1uL))
            }
            assertArrayEquals(TerminalLaneProtocol.input("one", delivery = identity), wire.written.receive())
            assertArrayEquals(TerminalLaneProtocol.input("two", delivery = identity.copy(sequence = 1uL)), wire.written.receive())
            // Fill more than the lane's 64-element buffer before starting its collector.
            val coalesced = ByteArray(ackWire.size * 96)
            repeat(96) { ackWire.copyInto(coalesced, it * ackWire.size) }
            wire.incoming.send(coalesced)
            val received = async { withTimeout(3000) { lane.acknowledgements.take(97).toList() } }
            for (byte in ackWire) wire.incoming.send(byteArrayOf(byte))
            assertEquals(List(97) { ack }, received.await())
            assertFalse(lane.closed.value)
        }
        withTimeout(2000) { wire.retired.await() }
    }

    @Test fun wrongTargetOrNonCanonicalBindingCannotWriteIdentifiedInput() = runBlocking<Unit> {
        for (binding in listOf(surface, "1-1-1-1-1", null)) {
            val wire = Wire().apply { incoming.send(terminalEnvelope()) }
            IrxTerminalInputLane.open(wire, binding).use { lane ->
                assertEquals(binding == surface, lane.supportsIdentifiedInput)
                val wrong = identity.copy(surface = UUID.randomUUID())
                assertTrue(runCatching { lane.sendIdentified("wrong", wrong) }.isFailure)
                assertTrue(wire.written.tryReceive().isFailure)
            }
        }
    }

    @Test fun ackCannotReplaceReadinessAndMalformedOrPartialAckRetiresInputLane() = runBlocking<Unit> {
        val first = Wire().apply { incoming.send(ackWire); incoming.close() }
        assertTrue(runCatching { IrxTerminalInputLane.open(first, surface) }.isFailure)
        withTimeout(2000) { first.retired.await() }
        for (bad in listOf(ackWire.copyOf(ackWire.size - 1), ackWire.copyOf().apply { this[36] = 2 }, terminalEnvelope(2))) {
            val wire = Wire().apply { incoming.send(terminalEnvelope()) }
            IrxTerminalInputLane.open(wire, surface).use { lane ->
                wire.incoming.send(bad); wire.incoming.close()
                withTimeout(2000) { lane.closed.first { it } }
                assertTrue(lane.acknowledgements.toList().isEmpty())
                assertTrue(runCatching { lane.sendIdentified("late", identity) }.isFailure)
            }
        }
    }

    @Test fun inputOwnerRequiresHandlerAndDoesNotTurnUncertainIdentifiedWriteIntoFallback() = runBlocking<Unit> {
        for (handler in listOf(false, true)) {
            val wire = Wire().apply { incoming.send(terminalEnvelope()) }
            val seen = Channel<TerminalInputAcknowledgement>(4)
            val owner = TerminalInputLaneOwner(this, onAcknowledgement = if (handler) ({ seen.trySend(it); Unit }) else null) { use ->
                IrxTerminalInputLane.open(wire, surface).use { use(it) }; true
            }
            try {
                withTimeout(2000) { owner.ready.first { it } }
                if (!handler) {
                    assertFalse(owner.sendIdentified("unsent", identity))
                    assertTrue(wire.written.tryReceive().isFailure)
                } else {
                    assertTrue(owner.sendIdentified("key", identity))
                    wire.written.receive(); wire.incoming.send(ackWire)
                    assertEquals(ack, withTimeout(2000) { seen.receive() })
                    wire.failWrite = true
                    assertTrue(runCatching { owner.sendIdentified("uncertain", identity) }.isFailure)
                    assertArrayEquals(TerminalLaneProtocol.input("uncertain", delivery = identity), wire.written.receive())
                    assertTrue(wire.written.tryReceive().isFailure)
                }
            } finally { owner.close() }
        }
    }

    @Test fun closedInputOwnerCannotDeliverQueuedAcknowledgement() = runBlocking<Unit> {
        val answers = Channel<TerminalInputAcknowledgement>(4)
        val candidate = object : TerminalInputLane {
            override val closed = MutableStateFlow(false)
            override val acknowledgements = answers.receiveAsFlow()
            override suspend fun send(text: String) = Unit
            override fun close() { closed.value = true }
        }
        var seen = 0
        val owner = TerminalInputLaneOwner(this, onAcknowledgement = { seen++ }) { use -> use(candidate); true }
        withTimeout(2000) { owner.ready.first { it } }
        answers.send(ack); owner.close(); yield()
        assertEquals(0, seen); assertTrue(candidate.closed.value)
    }

    @Test fun duplexLaneInterleavesAckWithOutputAndKeepsReplayBarrier() = runBlocking<Unit> {
        val encoded = terminalEnvelope(start = 50uL, bytes = byteArrayOf(65)) + ackWire + terminalEnvelope(2, 51uL, byteArrayOf(66))
        // Exercise actual asynchronous lane reads at every envelope/header/payload split.
        for (split in 0..encoded.size) {
            val wire = Wire().apply {
                if (split > 0) incoming.send(encoded.copyOfRange(0, split))
                if (split < encoded.size) incoming.send(encoded.copyOfRange(split, encoded.size))
            }
            IrxTerminalOutputLane(wire, 50uL, surface).use { lane ->
                assertArrayEquals(byteArrayOf(65), lane.receive()!!.bytes)
                val answer = lane.receive()!!
                assertEquals(ack, answer.inputAcknowledgement); assertTrue(answer.bytes.isEmpty())
                assertArrayEquals(byteArrayOf(66), lane.receive()!!.bytes)
                lane.sendIdentified("key", identity)
                assertArrayEquals(TerminalLaneProtocol.input("key", delivery = identity), wire.written.receive())
                assertTrue(runCatching { lane.sendIdentified("wrong", identity.copy(surface = UUID.randomUUID())) }.isFailure)
                assertTrue(wire.written.tryReceive().isFailure)
            }
        }
        val wire = Wire().apply { incoming.send(ackWire) }
        IrxTerminalOutputLane(wire, 0uL, surface).use { lane ->
            assertTrue(runCatching { lane.receive() }.isFailure)
            assertTrue(lane.closed.value); assertTrue(wire.written.tryReceive().isFailure)
        }
    }

    @Test fun outputOwnerRoutesAckAwayFromRendererAndLeavesCursorAndReadinessAlone() = runBlocking<Unit> {
        val wire = Wire()
        val consumed = mutableListOf<TerminalLaneProtocol.Output>()
        val answers = Channel<TerminalInputAcknowledgement>(4)
        val owner = TerminalOutputLaneOwner(this, { 50uL }, { cursor, use ->
            IrxTerminalOutputLane(wire, cursor, surface).use { use(it) }; true
        }, consume = { consumed += it; TerminalStreamMirror.Result.APPLIED }, resync = { error("Unexpected resync") },
            onAcknowledgement = { answers.trySend(it); Unit })
        try {
            owner.resume(); yield()
            assertFalse(owner.sendIdentified("before", identity))
            wire.incoming.send(terminalEnvelope(start = 50uL, bytes = byteArrayOf(65)))
            withTimeout(2000) { owner.ready.first { it } }
            assertTrue(owner.sendIdentified("one", identity))
            assertTrue(owner.sendIdentified("two", identity.copy(sequence = 1uL)))
            wire.incoming.send(ackWire)
            assertEquals(ack, withTimeout(2000) { answers.receive() })
            assertTrue(owner.ready.value); assertEquals(1, consumed.size)
            assertEquals(51uL, consumed.single().current)
            wire.incoming.send(terminalEnvelope(2, 51uL, byteArrayOf(66)))
            withTimeout(2000) { while (consumed.size < 2) yield() }
            assertArrayEquals(byteArrayOf(66), consumed.last().bytes)
        } finally { owner.close() }
    }

    @Test fun pausedOutputOwnerRejectsLateAckFromOldLane() = runBlocking<Unit> {
        val reading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
        var seen = 0
        val candidate = object : TerminalOutputLane {
            override val closed = MutableStateFlow(false)
            override suspend fun receive(): TerminalLaneProtocol.Output = withContext(NonCancellable) {
                reading.complete(Unit); release.await()
                TerminalLaneProtocol.Output(false, 0uL, 0uL, 34uL, byteArrayOf(), ack)
            }
            override suspend fun send(text: String) = Unit
            override fun close() { closed.value = true }
        }
        val owner = TerminalOutputLaneOwner(this, { 0uL }, { _, use ->
            try { use(candidate); true } finally { candidate.close(); finished.complete(Unit) }
        }, { error("Acknowledgement rendered") }, { error("Unexpected resync") }, { seen++ })
        try {
            owner.resume(); withTimeout(2000) { reading.await() }
            owner.pause(); release.complete(Unit)
            withTimeout(2000) { finished.await() }
            assertEquals(0, seen); assertFalse(owner.ready.value)
        } finally { release.complete(Unit); owner.close() }
    }
}
