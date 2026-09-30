package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.*
import io.github.docmorphic.cmuxapp.iroh.IrohRuntime
import io.github.docmorphic.cmuxapp.iroh.IrxClientSession
import io.github.docmorphic.cmuxapp.iroh.IrxWire
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Real native QUIC, admission and input readiness, without altering saved account credentials. */
class NativeTerminalInputLaneTest {
    @Test fun nativeInputUsesIndependentStreamAndControlRpcRemainsUsable() = exercise(inputOnly = true)
    @Test fun nativeDuplexTerminalStreamsReplayChunksAndInputAlongsideControl() = exercise(inputOnly = false)

    @Test fun identifiedInputAcknowledgesOnIndependentNativeLane() = exercise(inputOnly = true, identified = true)
    @Test fun identifiedInputAcknowledgesOnNativeDuplexLaneWithoutRenderingAck() = exercise(inputOnly = false, identified = true)

    private fun exercise(inputOnly: Boolean, identified: Boolean = false) = runBlocking<Unit> {
        IrohRuntime.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        withTimeout(20_000) {
            val options = EndpointOptions(preset = presetMinimal(), bindAddr = "127.0.0.1:0",
                alpns = listOf(IrxWire.ALPN.toByteArray()), portMappingEnabled = false,
                initialMaxConcurrentBiStreams = 1uL, initialMaxConcurrentUniStreams = 0uL)
            val host = Endpoint.bind(options)
            val phoneOptions = options.copy(initialMaxConcurrentBiStreams = 0uL)
            val phone = Endpoint.bind(phoneOptions)
            val finish = CompletableDeferred<Unit>()
            val surface = "382d08b0-890c-4a4f-a26a-3466cda11b81"
            val delivery = TerminalInputDelivery(java.util.UUID.fromString(surface), java.util.UUID.randomUUID(), ULong.MAX_VALUE)
            val acknowledgement = TerminalInputAcknowledgement(TerminalInputAcknowledgement.Status.APPLIED, delivery.stream, delivery.sequence)
            val text = "é😀\u001b[A\r"
            val cursor = ULong.MAX_VALUE - 20u
            try {
                val peer = async {
                    checkNotNull(host.acceptNext()).use { incoming -> incoming.accept().use { accepting ->
                        accepting.connect().use { connection -> connection.acceptBi().use { control ->
                            control.recv().use { read -> control.send().use { send ->
                                assertEquals("control", IrxWire.read { read.read(it.toUInt()) }?.getString("lane"))
                                assertEquals(IrxWire.ALPN, IrxWire.read { read.read(it.toUInt()) }?.getString("proto"))
                                send.writeAll(IrxWire.encode(JSONObject().put("v", 1).put("session", "input-fixture")
                                    .put("keepaliveIntervalMs", 5000).put("keepaliveDeadlineMs", 2000)))
                                connection.setMaxConcurrentBiStreams(16uL)
                                connection.acceptBi().use { input -> input.recv().use { keys -> input.send().use { ready ->
                                    val descriptorSize = ByteBuffer.wrap(keys.readExact(4u)).int
                                    require(descriptorSize in 1..IrxWire.MAX_CONTROL_BYTES)
                                    val descriptorText = keys.readExact(descriptorSize.toUInt()).decodeToString()
                                    val descriptor = MobileJson.objectValue(descriptorText)
                                    assertEquals(if (inputOnly) "terminal_input" else "terminal", descriptor.getString("lane"))
                                    assertEquals("terminal:$surface", descriptor.getString("resource"))
                                    if (inputOnly) assertFalse(descriptor.has("cursor"))
                                    else assertEquals(cursor.toString(), descriptor.get("cursor").toString())
                                    fun envelope(kind: Int, start: ULong, content: String): ByteArray {
                                        val bytes = content.toByteArray()
                                        return ByteBuffer.allocate(36 + bytes.size).putInt(0x434d5854).put(1).put(kind.toByte()).putShort(0)
                                            .putLong(cursor.toLong()).putLong(start.toLong()).putLong((start + bytes.size.toULong()).toLong())
                                            .putInt(bytes.size).put(bytes).array()
                                    }
                                    val baseline = envelope(1, cursor, if (inputOnly) "" else "native")
                                    ready.writeAll(baseline.copyOfRange(0, 11)); ready.writeAll(baseline.copyOfRange(11, baseline.size))
                                    if (!inputOnly) ready.writeAll(envelope(2, cursor + 6u, "!"))
                                    val header = ByteBuffer.wrap(keys.readExact(4u)).int
                                    val length = header and 0x3fffffff
                                    assertEquals(if (identified) 0x40000000 else 0, header and 0x40000000)
                                    assertEquals(text.toByteArray().size + if (identified) 40 else 0, length)
                                    if (identified) assertArrayEquals(delivery.encoded(), keys.readExact(40u))
                                    assertEquals(text, keys.readExact(text.toByteArray().size.toUInt()).decodeToString())
                                    if (identified) {
                                        val ack = ByteBuffer.allocate(70).putInt(0x434d5854).put(1).put(3).putShort(0)
                                            .putLong(0).putLong(0).putLong(34).putInt(34).put(1).put(1)
                                            .putLong(delivery.stream.mostSignificantBits).putLong(delivery.stream.leastSignificantBits)
                                            .putLong(delivery.sequence.toLong()).putLong(0).array()
                                        ready.writeAll(ack.copyOfRange(0, 37)); ready.writeAll(ack.copyOfRange(37, ack.size))
                                    }
                                    val request = checkNotNull(IrxWire.read { read.read(it.toUInt()) })
                                    assertEquals("mobile.host.status", request.getString("method"))
                                    send.writeAll(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id"))
                                        .put("ok", true).put("result", JSONObject().put("input_checked", true)).toString().toByteArray()))
                                    finish.await()
                                } } }
                            } }
                        } }
                    } }
                }
                host.id().use { id -> EndpointAddr(id, null, host.boundSockets()).use { address ->
                    val native = phone.connect(address, IrxWire.ALPN.toByteArray())
                    val transport = IrxMobileRpcTransport({ IrxClientSession.admit(native, id.toBytes()) }, { true },
                        MutableStateFlow(IrxProbeActivity(false)))
                    MobileRpcClient(transport, { null }).use { client ->
                        client.connect()
                        suspend fun checkInput(lane: TerminalInputLane) {
                            if (identified) {
                                lane.sendIdentified(text, delivery)
                                val ack = if (inputOnly) lane.acknowledgements.first() else {
                                    val frame = checkNotNull((lane as TerminalOutputLane).receive())
                                    assertTrue(frame.bytes.isEmpty()); checkNotNull(frame.inputAcknowledgement)
                                }
                                assertEquals(acknowledgement, ack)
                            } else lane.send(text)
                            assertTrue(client.hostStatus().getBoolean("input_checked"))
                            assertFalse(client.isClosed)
                        }
                        if (inputOnly) assertTrue(client.useTerminalInputLane(surface, ::checkInput))
                        else assertTrue(client.useTerminalOutputLane(surface, cursor) { lane ->
                            val replay = checkNotNull(lane.receive())
                            assertTrue(replay.replay); assertEquals(cursor, replay.sequence)
                            assertEquals("native", replay.bytes.decodeToString())
                            val chunk = checkNotNull(lane.receive())
                            assertFalse(chunk.replay); assertEquals(cursor + 6u, chunk.sequence)
                            assertEquals("!", chunk.bytes.decodeToString())
                            checkInput(lane)
                        })
                        finish.complete(Unit)
                        peer.await()
                    }
                } }
            } finally {
                finish.complete(Unit)
                withContext(NonCancellable) {
                    try { withTimeout(5000) { phone.shutdown(); host.shutdown() } }
                    finally { phone.close(); host.close(); phoneOptions.destroy(); options.destroy() }
                }
            }
        }
    }
}
