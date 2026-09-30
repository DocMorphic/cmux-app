package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64

class SimStreamTest {
    private val config = SimMessage.Config(SimCodec.HEVC, 1179, 2556, 3f, SimOrientation.PORTRAIT, 4,
        listOf(byteArrayOf(0x40, 1), byteArrayOf(0x42, 1), byteArrayOf(0x44, 1)))
    private fun frame(sequence: ULong = 1u) = SimMessage.Frame(sequence, 1, 123u, byteArrayOf(0, 0, 0, 1, 0x65))
    private fun touch(phase: SimTouchPhase, pointer: Int = 0, x: Float = .5f) = SimInput.Touch(phase, pointer, x, .5f, 100u)
    private fun golden(): JSONObject = JSONObject(checkNotNull(javaClass.getResource("/simulator/wire.json")).readText()).getJSONObject("messages")

    @Test fun codecMatchesBytesExportedByUnmodifiedPinnedSwiftCompiler() {
        val input = listOf(
            SimInput.Touch(SimTouchPhase.BEGAN, 255, .25f, .75f, 1000u),
            SimInput.Touch(SimTouchPhase.MOVED, 255, .3f, .7f, 1016u),
            SimInput.Touch(SimTouchPhase.ENDED, 255, .3f, .7f, 1032u),
            SimInput.Touch(SimTouchPhase.CANCELLED, 1, 0f, 1f, 1048u),
            SimInput.Text("héllo wörld 🚀"), SimInput.Key(40, true), SimInput.Key(40, false)
        ) + SimButton.entries.map { SimInput.Button(it) }
        val expected = mapOf(
            "start" to SimMessage.Start(ULong.MAX_VALUE, 1600, listOf(SimCodec.HEVC, SimCodec.H264)),
            "hevc" to config,
            "h264" to SimMessage.Config(SimCodec.H264, 1920, 1080, 2f, SimOrientation.LANDSCAPE_RIGHT, 2,
                listOf(byteArrayOf(0x67, 1), byteArrayOf(0x68, 1))),
            "frame" to SimMessage.Frame(ULong.MAX_VALUE - 1u, 0x81, 123456789u, byteArrayOf(0, 0, 0, 3, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte())),
            "ack" to SimMessage.Ack(ULong.MAX_VALUE - 1u, 999u), "input" to SimMessage.Input(3u, input),
            "keyframe" to SimMessage.KeyframeRequest, "stop" to SimMessage.Stop,
            "state" to SimMessage.State(SimHostStatus.DEVICE_UNAVAILABLE, "sim shut down 🚀")
        )
        val fixtures = golden()
        for ((name, message) in expected) {
            val wire = Base64.getDecoder().decode(fixtures.getString(name))
            assertArrayEquals(name, wire, SimStreamWire.encode(message))
            assertArrayEquals(name, wire, SimStreamWire.encode(SimStreamWire.decode(wire.copyOfRange(4, wire.size))))
        }
        val decoded = SimStreamWire.decode(Base64.getDecoder().decode(fixtures.getString("frame")).drop(4).toByteArray()) as SimMessage.Frame
        assertEquals(ULong.MAX_VALUE - 1u, decoded.sequence); assertTrue(decoded.keyframe)
    }

    @Test fun fragmentedAndCoalescedFramesPreserveEveryMessageAcrossAllByteSplits() = runBlocking<Unit> {
        val messages = listOf(config, frame(), SimMessage.State(SimHostStatus.STREAMING, "你好"))
        val wire = messages.fold(byteArrayOf()) { bytes, m -> bytes + SimStreamWire.encode(m) }
        for (split in 0..wire.size) {
            val result = mutableListOf<SimMessage>(); val reader = SimStreamFramer()
            reader.feed(wire.copyOfRange(0, split), result::add)
            reader.feed(wire.copyOfRange(split, wire.size), result::add); reader.finish()
            assertEquals(3, result.size)
            result.zip(messages).forEach { (a, b) -> assertArrayEquals(SimStreamWire.encode(b), SimStreamWire.encode(a)) }
        }
        val result = mutableListOf<SimMessage>(); val reader = SimStreamFramer()
        for (byte in wire) reader.feed(byteArrayOf(byte), result::add)
        reader.finish(); assertEquals(3, result.size)
    }

    @Test fun oversizedTruncatedUnknownAndInvalidUtf8BodiesCannotBecomeFrames() = runBlocking<Unit> {
        val bytes = SimStreamWire.encode(config, false)
        for (cut in bytes.indices) assertThrows(IOException::class.java) { SimStreamWire.decode(bytes.copyOf(cut)) }
        assertThrows(IOException::class.java) { SimStreamWire.decode(byteArrayOf(7, 0)) }
        assertThrows(IOException::class.java) { SimStreamWire.decode(byteArrayOf(99)) }
        assertThrows(IOException::class.java) { SimStreamWire.decode(byteArrayOf(8, 99, 0, 0, 0, 0)) }
        assertThrows(IOException::class.java) { SimStreamWire.decode(byteArrayOf(8, 0, 0, 0, 0, 1, 0xFF.toByte())) }
        for (length in listOf(0, -1, SimStreamWire.MAX_BODY + 1)) {
            val reader = SimStreamFramer()
            try { reader.feed(ByteBuffer.allocate(4).putInt(length).array()) { fail("Oversized frame delivered") }; fail("Accepted bad length") }
            catch (_: IOException) { }
            try { reader.feed(byteArrayOf()) { }; fail("Reused corrupt stream") } catch (_: IllegalStateException) { }
        }
        val reader = SimStreamFramer(); reader.feed(byteArrayOf(0, 0, 0, 2, 7)) { }
        assertThrows(IOException::class.java) { reader.finish() }
        assertThrows(IllegalArgumentException::class.java) { SimStreamWire.encode(SimMessage.Frame(1u, 1, 1u, ByteArray(SimStreamWire.MAX_BODY))) }
    }

    @Test fun futureStartCodecIsSkippedButUnknownConfigCodecFails() {
        val body = SimStreamWire.encode(SimMessage.Start(1u, 1200, listOf(SimCodec.HEVC)), false)
        body[12] = 2
        val decoded = SimStreamWire.decode(body + byteArrayOf(0x77)) as SimMessage.Start
        assertEquals(listOf(SimCodec.HEVC), decoded.codecs)
        val invalid = SimStreamWire.encode(config, false); invalid[1] = 0x77
        assertThrows(IOException::class.java) { SimStreamWire.decode(invalid) }
    }

    @Test fun outboxCoalescesOnlyMovesWithoutCrossingPhaseOrKeyBoundaries() {
        val box = SimInputOutbox()
        val begin = touch(SimTouchPhase.BEGAN); val a = touch(SimTouchPhase.MOVED, x = .1f)
        val b = touch(SimTouchPhase.MOVED, 1, .2f); val newest = touch(SimTouchPhase.MOVED, x = .9f)
        val key = SimInput.Key(40, true); val end = touch(SimTouchPhase.ENDED)
        listOf(begin, a, b, newest, key, a, end).forEach(box::enqueue)
        assertEquals(listOf(begin, newest, b, key, a, end), box.drain()!!.events)
        assertNull(box.drain()); box.enqueue(key); box.clear(); box.enqueue(end)
        assertEquals(2uL, box.drain()!!.sequence)
        val limited = SimInputOutbox(maximumEvents = 1)
        limited.enqueue(begin); assertThrows(IOException::class.java) { limited.enqueue(end) }
        assertEquals(listOf(begin), limited.drain()!!.events)
        assertThrows(IOException::class.java) { SimInputOutbox(maximumBytes = 5).enqueue(SimInput.Text("界")) }
    }

    private class Lane : SimStreamLane {
        val incoming = Channel<ByteArray>(32)
        val sent = mutableListOf<SimMessage>()
        var closed = false
        var writing: suspend (SimMessage) -> Unit = { }
        override suspend fun read() = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            check(!closed)
            val message = SimStreamWire.decode(bytes.copyOfRange(4, bytes.size))
            writing(message); sent += message
        }
        override fun close() { closed = true; incoming.close() }
        fun host(vararg messages: SimMessage) { messages.forEach { check(incoming.trySend(SimStreamWire.encode(it)).isSuccess) } }
    }
    private class Presenter : SimFramePresenter {
        var resets = 0; var configured = 0
        var display: suspend (SimMessage.Frame) -> Boolean = { true }
        override suspend fun configure(config: SimMessage.Config) { configured++ }
        override suspend fun present(frame: SimMessage.Frame) = display(frame)
        override suspend fun reset() { resets++ }
    }
    private fun session(presenter: Presenter, events: MutableList<SimViewerEvent> = mutableListOf()) =
        SimStreamSession(presenter, 42u, 1600, listOf(SimCodec.HEVC, SimCodec.H264), { 999u }) { events += it }

    @Test fun sessionAcknowledgesOnlyAfterPresenterConfirmsDisplayAndClosesOnEof() = runBlocking<Unit> {
        val displayed = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val presenter = Presenter().apply { display = { entered.complete(Unit); displayed.await(); true } }
        val lane = Lane(); val events = mutableListOf<SimViewerEvent>()
        lane.host(config, frame(ULong.MAX_VALUE)); lane.incoming.close()
        val run = launch { session(presenter, events).run(lane) }
        entered.await(); assertTrue(lane.sent.none { it is SimMessage.Ack })
        displayed.complete(Unit); withTimeout(2000) { run.join() }
        assertEquals(SimMessage.Ack(ULong.MAX_VALUE, 999u), lane.sent.last())
        assertTrue(events.last() is SimViewerEvent.Presented); assertTrue(lane.closed)
    }

    @Test fun repeatedDisplayFailureRequestsOneKeyframeThenTerminatesWithoutAcknowledging() = runBlocking<Unit> {
        val presenter = Presenter().apply { display = { false } }; val lane = Lane()
        lane.host(config, *Array(6) { frame((it + 1).toULong()) }); lane.incoming.close()
        try { session(presenter).run(lane); fail("Failed display accepted") } catch (_: IOException) { }
        assertEquals(1, presenter.resets)
        assertEquals(1, lane.sent.count { it == SimMessage.KeyframeRequest })
        assertTrue(lane.sent.none { it is SimMessage.Ack }); assertTrue(lane.closed)
    }

    @Test fun viewerMessagesAndFramesBeforeConfigurationFailClosed() = runBlocking<Unit> {
        for (message in listOf(frame(), SimMessage.Stop, SimMessage.Ack(1u, 1u))) {
            val lane = Lane(); lane.host(message); lane.incoming.close()
            try { session(Presenter()).run(lane); fail("Host message accepted") } catch (_: IOException) { }
            assertTrue(lane.closed); assertEquals(1, lane.sent.size)
        }
    }

    @Test fun cancellationDropsPendingInputAndCannotReuseTheSession() = runBlocking<Unit> {
        val lane = Lane(); val blocked = CompletableDeferred<Unit>()
        lane.writing = { if (it is SimMessage.Input) { blocked.complete(Unit); awaitCancellation() } }
        val engine = session(Presenter())
        val run = launch(start = CoroutineStart.UNDISPATCHED) { engine.run(lane) }
        assertTrue(engine.input(SimInput.Text("first"))); blocked.await()
        assertTrue(engine.input(SimInput.Text("must not replay")))
        run.cancelAndJoin(); assertTrue(lane.closed)
        assertFalse(engine.input(SimInput.Button(SimButton.HOME)))
        val next = Lane()
        try { engine.run(next); fail("Reused ended session") } catch (_: IllegalStateException) { }
        assertTrue(lane.sent.none { it is SimMessage.Input }); assertTrue(next.closed)
    }

    @Test fun simulatorLaneUsesCanonicalExactPanelResource() {
        val id = "ABCDEF01-2345-6789-ABCD-EF0123456789"
        val descriptor = simulatorLaneDescriptor(id)
        assertEquals("simulator_stream", descriptor.lane.wire)
        assertEquals("simstream:${id.lowercase()}", descriptor.resource)
        assertNull(descriptor.cursor); assertNull(descriptor.offset)
        for (invalid in listOf("1-2-3-4-5", "../panel", "", "$id/other"))
            assertThrows(IllegalArgumentException::class.java) { simulatorLaneDescriptor(invalid) }
    }

    @Test fun leaseCancellationRetiresLateSimulatorWithoutClosingSharedConnection() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val incoming = Channel<ByteArray>(); val lane = Lane()
        val transport = object : MobileRpcTransport {
            override suspend fun connect() { }
            override suspend fun read() = incoming.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) { }
            override val supportsSimulatorLanes = true
            override suspend fun openSimulator(panelId: String): SimStreamLane {
                assertEquals("panel", panelId)
                withContext(NonCancellable) { entered.complete(Unit); release.await() }; return lane
            }
            override fun close() { incoming.close() }
        }
        MobileRpcClient(transport, { "fixture-token" }).use { base ->
            base.connect(); val lease = base.lease { }; var used = false
            assertTrue(lease.supportsSimulatorLanes)
            val pending = launch { lease.useSimulatorLane("panel") { used = true } }
            entered.await(); lease.close(); release.complete(Unit)
            withTimeout(2000) { pending.join() }
            assertFalse(used); assertTrue(lane.closed); assertFalse(base.isClosed)
        }
    }
}
