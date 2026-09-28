package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class IrxKeepaliveTest {
    private class Fixture {
        val active = MutableStateFlow(IrxProbeActivity(true))
        val sent = Channel<Pair<Lane, JSONObject>>(Channel.UNLIMITED)
        val opened = Channel<Lane>(Channel.UNLIMITED)
        val inbound = AtomicLong(Long.MIN_VALUE)
        var clock: () -> Long = System::nanoTime
        inner class Lane : IrxProbeLane {
            val replies = Channel<JSONObject>(Channel.UNLIMITED)
            val retired = CompletableDeferred<Unit>()
            override suspend fun write(value: JSONObject) { sent.send(this to value) }
            override suspend fun read() = replies.receive().also { inbound.set(clock()) }
            override suspend fun retire() { retired.complete(Unit); close() }
            override fun close() { replies.close() }
            suspend fun pong(seq: String) { replies.send(JSONObject().put("v", 1).put("seq", java.math.BigInteger(seq)).put("pong", true)) }
        }
        fun start(interval: Long = 20, deadline: Long = 40) = IrxKeepalive(active,
            open = { Lane().also { opened.send(it) } }, permits = { true },
            lastInboundNanos = { inbound.get().takeIf { it != Long.MIN_VALUE } },
            intervalMillis = interval, deadlineMillis = deadline, now = { clock() })
    }

    @Test fun correlatedPongsReuseLaneAndStalePongCannotSatisfyNextProbe() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.start().use {
            val (lane, first) = withTimeout(2000) { fixture.sent.receive() }
            assertFalse(first.getBoolean("pong")); assertEquals(1, first.getInt("v"))
            lane.pong(first.get("seq").toString())
            val (same, second) = withTimeout(2000) { fixture.sent.receive() }
            assertSame(lane, same)
            assertNotEquals(first.get("seq"), second.get("seq"))
            lane.pong(first.get("seq").toString())
            withTimeout(2000) { lane.retired.await() }
            val (fresh, third) = withTimeout(2000) { fixture.sent.receive() }
            assertNotSame(lane, fresh)
            assertNotEquals(second.get("seq"), third.get("seq"))
            fresh.pong(third.get("seq").toString())
        }
    }

    @Test fun pauseRetiresProbeAndResumeStartsFreshWithoutReusingSilenceEvidence() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.start().use { probes ->
            val (old, _) = withTimeout(2000) { fixture.sent.receive() }
            val start = System.nanoTime()
            fixture.active.value = IrxProbeActivity(false, 1)
            assertFalse(probes.positiveSilenceSince(start))
            withTimeout(2000) { old.retired.await() }
            delay(100)
            assertTrue(fixture.sent.tryReceive().isFailure)
            fixture.active.value = IrxProbeActivity(true, 2)
            val (next, _) = withTimeout(2000) { fixture.sent.receive() }
            assertNotSame(old, next)
            assertFalse(probes.positiveSilenceSince(start))
        }
    }

    @Test fun silenceNeedsCompletedCyclesAndNoActivitySinceTheRequest() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.start().use { probes ->
            withTimeout(2000) { fixture.sent.receive() }
            val start = System.nanoTime()
            assertFalse(probes.positiveSilenceSince(start))
            withTimeout(2500) { while (!probes.positiveSilenceSince(start)) delay(5) }
            fixture.inbound.set(System.nanoTime())
            assertFalse(probes.positiveSilenceSince(start))
            fixture.inbound.set(Long.MIN_VALUE)
            // Even a false→true change conflated by StateFlow invalidates the old run immediately.
            fixture.active.value = IrxProbeActivity(false, 1)
            fixture.active.value = IrxProbeActivity(true, 2)
            assertFalse(probes.positiveSilenceSince(start))
        }
    }

    @Test fun suspendedSchedulerCannotCountUnsentProbesAsSilence() = runBlocking<Unit> {
        val fixture = Fixture()
        val time = AtomicLong(1_000_000_000)
        fixture.clock = time::get
        fixture.start().use { probes ->
            val (lane, first) = withTimeout(2000) { fixture.sent.receive() }
            lane.pong(first.get("seq").toString())
            withTimeout(2000) { while (fixture.inbound.get() == Long.MIN_VALUE) delay(1) }
            val start = time.get() + 1
            time.addAndGet(5_000_000_000)
            val (fresh, _) = withTimeout(2000) { fixture.sent.receive() }
            assertNotSame(lane, fresh)
            withTimeout(2000) { lane.retired.await() }
            repeat(3) { withTimeout(2000) { fixture.sent.receive() } }
            assertFalse(probes.positiveSilenceSince(start))
        }
    }

    @Test fun malformedReplyRetiresOnlyItsDiagnosticStream() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.start().use {
            val (lane, ping) = withTimeout(2000) { fixture.sent.receive() }
            lane.replies.send(JSONObject().put("v", 1).put("seq", ping.get("seq").toString()).put("pong", true))
            withTimeout(2000) { lane.retired.await() }
            val (fresh, _) = withTimeout(2000) { fixture.sent.receive() }
            assertNotSame(lane, fresh)
        }
    }

    @Test fun inactiveOwnerDoesNotOpenProbeStreams() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.active.value = IrxProbeActivity(false)
        fixture.start().use {
            delay(120)
            assertTrue(fixture.opened.tryReceive().isFailure)
        }
    }
}
