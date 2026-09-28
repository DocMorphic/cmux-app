package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.*
import io.github.docmorphic.cmuxapp.iroh.IrohRuntime
import io.github.docmorphic.cmuxapp.iroh.IrxClientSession
import io.github.docmorphic.cmuxapp.iroh.IrxWire
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Real native QUIC, admission and input readiness, without altering saved account credentials. */
class NativeTerminalInputLaneTest {
    @Test fun nativeInputUsesIndependentStreamAndControlRpcRemainsUsable() = runBlocking<Unit> {
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
            val text = "é😀\u001b[A\r"
            try {
                val peer = async {
                    checkNotNull(host.acceptNext()).use { incoming -> incoming.accept().use { accepting ->
                        accepting.connect().use { connection -> connection.acceptBi().use { control ->
                            control.recv().use { read -> control.send().use { send ->
                                assertEquals("control", IrxWire.read { read.read(it.toUInt()) }?.getString("lane"))
                                assertEquals(IrxWire.ALPN, IrxWire.read { read.read(it.toUInt()) }?.getString("proto"))
                                send.writeAll(IrxWire.encode(JSONObject().put("v", 1).put("session", "input-fixture")
                                    .put("keepaliveIntervalMs", 5000).put("keepaliveDeadlineMs", 2000)))
                                connection.acceptBi().use { input -> input.recv().use { keys -> input.send().use { ready ->
                                    val descriptor = checkNotNull(IrxWire.read { keys.read(it.toUInt()) })
                                    assertEquals("terminal_input", descriptor.getString("lane"))
                                    assertEquals("terminal:$surface", descriptor.getString("resource"))
                                    assertFalse(descriptor.has("cursor"))
                                    // Independent host baseline deliberately exceeds signed Long range.
                                    val baseline = ByteBuffer.allocate(36).putInt(0x434d5854).put(1).put(1).putShort(0)
                                        .putLong(-2).putLong(-2).putLong(-2).putInt(0).array()
                                    ready.writeAll(baseline.copyOfRange(0, 11)); ready.writeAll(baseline.copyOfRange(11, 36))
                                    val length = ByteBuffer.wrap(keys.readExact(4u)).int
                                    assertEquals(text.toByteArray().size, length)
                                    assertEquals(text, keys.readExact(length.toUInt()).decodeToString())
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
                        assertTrue(client.useTerminalInputLane(surface) { lane ->
                            lane.send(text)
                            assertTrue(client.hostStatus().getBoolean("input_checked"))
                            assertFalse(client.isClosed)
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
