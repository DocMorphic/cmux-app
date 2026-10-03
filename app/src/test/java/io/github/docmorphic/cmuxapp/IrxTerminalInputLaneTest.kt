package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class IrxTerminalInputLaneTest {
    private class Wire : TerminalLaneWire {
        val incoming = Channel<ByteArray>(16)
        val writes = Channel<ByteArray>(16)
        val retired = CompletableDeferred<Unit>()
        var failWrite = false
        override suspend fun read(): ByteArray = incoming.receiveCatching().getOrNull() ?: byteArrayOf()
        override suspend fun write(bytes: ByteArray) { writes.send(bytes); if (failWrite) throw IOException("partial native write") }
        override suspend fun retire() { retired.complete(Unit); incoming.close() }
        override fun close() { incoming.close() }
    }

    @Test fun fragmentedBaselineGatesReadinessAndInputBypassesRpcSettlement() = runBlocking<Unit> {
        val wire = Wire()
        val opening = async { IrxTerminalInputLane.open(wire) }
        val baseline = terminalEnvelope(start = ULong.MAX_VALUE)
        wire.incoming.send(baseline.copyOfRange(0, 12))
        yield(); assertFalse(opening.isCompleted); assertTrue(wire.writes.tryReceive().isFailure)
        wire.incoming.send(baseline.copyOfRange(12, baseline.size))
        val lane = withTimeout(2000) { opening.await() }
        lane.send("😀\r")
        assertArrayEquals(TerminalLaneProtocol.input("😀\r"), wire.writes.receive())
        wire.incoming.close()
        withTimeout(2000) { lane.closed.first { it } }
        assertTrue(runCatching { lane.send("again") }.isFailure)
        withTimeout(2000) { wire.retired.await() }
    }

    @Test fun nonReplayNonemptyAndTruncatedBaselineNeverEnableInput() = runBlocking<Unit> {
        for (bytes in listOf(terminalEnvelope(2), terminalEnvelope(bytes = "output".toByteArray()), terminalEnvelope().copyOf(35))) {
            val wire = Wire(); wire.incoming.send(bytes); wire.incoming.close()
            assertTrue(runCatching { IrxTerminalInputLane.open(wire) }.isFailure)
            withTimeout(2000) { wire.retired.await() }
            assertTrue(wire.writes.tryReceive().isFailure)
        }
    }

    @Test fun uncertainWriteIsNotRetriedOrReportedAsRpcFallback() = runBlocking<Unit> {
        val wire = Wire(); wire.incoming.send(terminalEnvelope()); wire.failWrite = true
        val lane = IrxTerminalInputLane.open(wire)
        val owner = TerminalInputLaneOwner(this) { use -> try { use(lane); true } finally { lane.close() } }
        try {
            withTimeout(2000) { owner.ready.first { it } }
            assertTrue(runCatching { owner.send("one") }.isFailure)
            assertArrayEquals(TerminalLaneProtocol.input("one"), wire.writes.receive())
            assertTrue(wire.writes.tryReceive().isFailure)
        } finally { owner.close() }
    }

    @Test fun unsupportedOrUnreadyLaneAndOversizeOperationUseUnsentFallback() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val wire = Wire(); wire.incoming.send(terminalEnvelope())
        val owner = TerminalInputLaneOwner(this) { use ->
            gate.await()
            IrxTerminalInputLane.open(wire).use { lane -> use(lane) }; true
        }
        try {
            assertFalse(owner.send("before")); gate.complete(Unit)
            withTimeout(2000) { owner.ready.first { it } }
            assertFalse(owner.send("a".repeat(16385))); assertTrue(wire.writes.tryReceive().isFailure)
        } finally { owner.close() }
        withTimeout(2000) { wire.retired.await() }
        TerminalInputLaneOwner(this) { false }.use { unsupported -> yield(); assertFalse(unsupported.send("legacy")) }
    }

    @Test fun repairedNativeLaneDoesNotReplayUncertainOrQueuedKeysThroughRpc() = runBlocking<Unit> {
        val first = Wire().apply { incoming.send(terminalEnvelope()); failWrite = true }
        val replacement = Wire().apply { incoming.send(terminalEnvelope()) }
        var openings = 0
        val replacementReady = CompletableDeferred<Unit>()
        val owner = TerminalInputLaneOwner(this) { use ->
            val wire = if (openings++ == 0) first else replacement
            IrxTerminalInputLane.open(wire).use { lane ->
                if (wire === replacement) replacementReady.complete(Unit)
                use(lane)
            }
            true
        }
        val rpcFallback = mutableListOf<String>()
        val queue = TerminalInputQueue(this) { entry ->
            if (!owner.send(entry.text)) rpcFallback += entry.text
        }
        try {
            withTimeout(2000) { owner.ready.first { it } }
            assertTrue(queue.offer("possibly delivered"))
            assertTrue(queue.offer("queued behind failed input"))
            withTimeout(2000) { queue.status.first { it.error != null } }
            withTimeout(2000) { replacementReady.await(); owner.ready.first { it } }
            assertArrayEquals(TerminalLaneProtocol.input("possibly delivered"), first.writes.receive())
            assertTrue(first.writes.tryReceive().isFailure)
            assertTrue(replacement.writes.tryReceive().isFailure)
            assertTrue(rpcFallback.isEmpty())
            assertFalse(queue.offer("while paused"))
            assertTrue(queue.resume())
            assertTrue(queue.offer("new explicit input"))
            withTimeout(2000) { queue.awaitIdle() }
            assertArrayEquals(TerminalLaneProtocol.input("new explicit input"), replacement.writes.receive())
            assertTrue(replacement.writes.tryReceive().isFailure)
            assertTrue(rpcFallback.isEmpty())
        } finally { queue.close(); owner.close() }
    }

    @Test fun cancellingBeforeBaselineRetiresOnlyTheCandidate() = runBlocking<Unit> {
        val wire = Wire()
        val opening = launch(start = CoroutineStart.UNDISPATCHED) { IrxTerminalInputLane.open(wire) }
        opening.cancelAndJoin()
        withTimeout(2000) { wire.retired.await() }
        assertTrue(wire.writes.tryReceive().isFailure)
    }

    @Test fun laneReturningAfterLeaseCloseCannotBecomeUsable() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val input = Channel<ByteArray>()
        val lane = object : TerminalInputLane {
            override val closed = MutableStateFlow(false)
            override suspend fun send(text: String) { error("Late lane used") }
            override fun close() { closed.value = true }
        }
        val wire = object : MobileRpcTransport {
            override suspend fun connect() { }
            override suspend fun read() = input.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) { }
            override suspend fun openTerminalInput(surfaceId: String): TerminalInputLane {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                return lane
            }
            override fun close() { input.close() }
        }
        MobileRpcClient(wire, { "fixture-token" }).use { base ->
            base.connect()
            val lease = base.lease { }
            var used = false
            val opening = launch { lease.useTerminalInputLane("surface") { used = true } }
            entered.await(); lease.close(); release.complete(Unit)
            withTimeout(2000) { opening.join() }
            assertFalse(used); assertTrue(lane.closed.value); assertFalse(base.isClosed)
        }
    }

    @Test fun closingOneRpcLeaseClosesOnlyItsTerminalLane() = runBlocking<Unit> {
        val input = Channel<ByteArray>()
        val opened = Channel<TerminalInputLane>(4)
        val wire = object : MobileRpcTransport {
            override suspend fun connect() { }
            override suspend fun read() = input.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) { }
            override suspend fun openTerminalInput(surfaceId: String): TerminalInputLane = object : TerminalInputLane {
                override val closed = MutableStateFlow(false)
                override suspend fun send(text: String) { check(!closed.value) }
                override fun close() { closed.value = true }
            }.also { opened.send(it) }
            override fun close() { input.close() }
        }
        MobileRpcClient(wire, { "fixture-token" }).use { base ->
            base.connect()
            val a = base.lease { }; val b = base.lease { }
            val first = launch { a.useTerminalInputLane("a") { it.closed.first { closed -> closed } } }
            val one = opened.receive()
            val second = launch { b.useTerminalInputLane("b") { it.closed.first { closed -> closed } } }
            val two = opened.receive()
            a.close(); withTimeout(2000) { first.join() }
            assertTrue(one.closed.value); assertFalse(two.closed.value); assertFalse(base.isClosed)
            b.close(); withTimeout(2000) { second.join() }; assertTrue(two.closed.value)
        }
    }
}
