package io.github.docmorphic.cmuxapp.iroh

import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer

class IrxAdmissionTest {
    @Before fun initialize() = IrohRuntime.initialize(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun admitsAndPreservesRawBytesAfterFragmentedControlFrame() = runBlocking {
        fixture(server = { connection ->
            connection.acceptBi().use { stream ->
                stream.recv().use { receive ->
                    val descriptor = checkNotNull(IrxWire.read { receive.read(it.toUInt()) })
                    assertEquals(1, descriptor.getInt("v")); assertEquals("control", descriptor.getString("lane"))
                    val hello = checkNotNull(IrxWire.read { receive.read(it.toUInt()) })
                    assertEquals("cmux/irx/1", hello.getString("proto")); assertFalse(hello.has("grant"))
                    stream.send().use { send ->
                        val admit = """{"v":1,"session":"fixture-session","keepaliveIntervalMs":5000,"keepaliveDeadlineMs":2000}""".toByteArray()
                        val frame = ByteBuffer.allocate(4 + admit.size).putInt(admit.size).put(admit).array()
                        send.writeAll(frame.copyOfRange(0, 1))
                        send.writeAll(frame.copyOfRange(1, 7))
                        send.writeAll(frame.copyOfRange(7, frame.size) + "ready".toByteArray())
                        assertEquals("ping", receive.readExact(4u).decodeToString())
                        send.writeAll("pong".toByteArray())
                    }
                }
            }
        }) { connection, expected ->
            IrxClientSession.admit(connection, expected).use { session ->
                assertEquals("fixture-session", session.admission.session)
                assertEquals(5000L, session.admission.keepaliveIntervalMs)
                assertEquals("ready", readRaw(session.control, 5).decodeToString())
                session.control.write("ping".toByteArray())
                assertEquals("pong", readRaw(session.control, 4).decodeToString())
            }
        }
    }

    @Test fun remoteRevocationRemainsTerminalForAutomaticRedial() = runBlocking {
        fixture(server = { connection ->
            connection.acceptBi().use { stream ->
                stream.recv().use { receive -> IrxWire.read { receive.read(it.toUInt()) } }
                connection.close(0L, "irx:revoked".toByteArray())
            }
        }) { connection, expected ->
            val error = rejected { IrxClientSession.admit(connection, expected) }
            assertEquals(IrxWire.CloseCode.REVOKED, error.code)
            assertTrue(error.code.terminalForRedial)
        }
    }

    @Test fun acceptsSharedAndSurfaceEventStreamsAfterAdmissionWithoutConsumingTheirPayload() = runBlocking<Unit> {
        fixture(server = { connection ->
            IrxDuplexLane(connection.acceptBi()).use { control ->
                assertEquals("control", control.readFrame()?.getString("lane"))
                assertEquals(IrxWire.ALPN, control.readFrame()?.getString("proto"))
                control.writeFrame(JSONObject().put("v", 1).put("session", "events-session")
                    .put("keepaliveIntervalMs", 5000).put("keepaliveDeadlineMs", 2000))
                for ((resource, payload) in listOf(null to "shared", "terminal:surface-a" to "surface")) {
                    connection.openUni().use { send ->
                        val header = IrxWire.encode(IrxWire.Descriptor(IrxWire.Lane.EVENTS, resource).json())
                        send.writeAll(header.copyOfRange(0, 3))
                        send.writeAll(header.copyOfRange(3, header.size) + payload.toByteArray())
                        send.finish()
                    }
                }
                assertEquals("done", readRaw(control, 4).decodeToString())
            }
        }) { connection, expected ->
            IrxClientSession.admit(connection, expected).use { session ->
                val values = mutableMapOf<String?, String>()
                repeat(2) {
                    session.acceptEvents().use { lane ->
                        val output = java.io.ByteArrayOutputStream()
                        while (true) {
                            val bytes = lane.read()
                            if (bytes.isEmpty()) break
                            output.write(bytes)
                        }
                        values[lane.resource] = output.toString("UTF-8")
                    }
                }
                assertEquals(mapOf(null to "shared", "terminal:surface-a" to "surface"), values)
                session.control.write("done".toByteArray())
            }
        }
    }

    @Test fun badOptionalDescriptorsAreSkippedAndLaterEventsAndControlStillWork() = runBlocking<Unit> {
        val finished = CompletableDeferred<Unit>()
        fixture(server = { connection ->
            IrxDuplexLane(connection.acceptBi()).use { control ->
                control.readFrame(); control.readFrame()
                control.writeFrame(JSONObject().put("v", 1).put("session", "optional-events")
                    .put("keepaliveIntervalMs", 5000).put("keepaliveDeadlineMs", 2000))
                val bad = listOf(
                    byteArrayOf(0, 0, 0, 4, 123), // truncated JSON payload
                    IrxWire.encode(JSONObject().put("v", 2).put("lane", "events")),
                    IrxWire.encode(JSONObject().put("v", 1).put("lane", "unsupported")),
                    IrxWire.encode(JSONObject().put("v", 1).put("lane", "events").put("resource", "bad"))
                )
                for (bytes in bad) connection.openUni().use { send -> send.writeAll(bytes); send.finish() }
                connection.openUni().use { send ->
                    send.writeAll(IrxWire.encode(IrxWire.Descriptor(IrxWire.Lane.EVENTS).json()) + "event".toByteArray())
                    send.finish()
                }
                assertEquals("done", readRaw(control, 4).decodeToString())
                control.write("live".toByteArray())
                finished.await()
            }
        }) { connection, expected ->
            IrxClientSession.admit(connection, expected).use { session ->
                session.acceptEvents().use { lane ->
                    assertNull(lane.resource)
                    val output = java.io.ByteArrayOutputStream()
                    while (true) {
                        val bytes = lane.read()
                        if (bytes.isEmpty()) break
                        output.write(bytes)
                    }
                    assertEquals("event", output.toString("UTF-8"))
                }
                assertFalse(session.connectionIsClosed())
                session.control.write("done".toByteArray())
                assertEquals("live", readRaw(session.control, 4).decodeToString())
                finished.complete(Unit)
            }
        }
    }

    @Test fun keepaliveFramesUpdateActivityAndRetirementPreservesControl() = runBlocking<Unit> {
        val controlFinished = CompletableDeferred<Unit>()
        fixture(server = { connection ->
            IrxDuplexLane(connection.acceptBi()).use { control ->
                assertEquals("control", control.readFrame()?.getString("lane"))
                control.readFrame()
                control.writeFrame(JSONObject().put("v", 1).put("session", "probe-session")
                    .put("keepaliveIntervalMs", 5000).put("keepaliveDeadlineMs", 2000))
                IrxDuplexLane(connection.acceptBi()).use { probe ->
                    assertEquals("keepalive", probe.readFrame()?.getString("lane"))
                    repeat(2) {
                        val ping = checkNotNull(probe.readFrame())
                        assertFalse(ping.getBoolean("pong"))
                        probe.writeFrame(JSONObject().put("v", 1).put("seq", ping.get("seq")).put("pong", true))
                    }
                    assertEquals("done", readRaw(control, 4).decodeToString())
                    control.write("live".toByteArray())
                    controlFinished.await()
                }
            }
        }) { connection, expected ->
            IrxClientSession.admit(connection, expected).use { session ->
                var previous = checkNotNull(session.lastInboundNanos)
                session.openLane(IrxWire.Descriptor(IrxWire.Lane.KEEPALIVE)).use { probe ->
                    repeat(2) { index ->
                        probe.writeFrame(JSONObject().put("v", 1).put("seq", index + 1).put("pong", false))
                        val pong = checkNotNull(probe.readFrame())
                        assertTrue(pong.getBoolean("pong")); assertEquals(index + 1, pong.getInt("seq"))
                        val current = checkNotNull(session.lastInboundNanos)
                        assertTrue(current > previous); previous = current
                    }
                    probe.retire(0uL)
                }
                assertFalse(session.connectionIsClosed())
                session.control.write("done".toByteArray())
                assertEquals("live", readRaw(session.control, 4).decodeToString())
                assertTrue(checkNotNull(session.lastInboundNanos) > previous)
                controlFinished.complete(Unit)
            }
        }
    }

    @Test fun wrongDirectoryKeyFailsBeforeApplicationStream() = runBlocking {
        fixture(server = { connection ->
            assertTrue(connection.closed().contains("irx:identity-mismatch"))
        }) { connection, expected ->
            expected[0] = (expected[0].toInt() xor 1).toByte()
            assertEquals(IrxWire.CloseCode.IDENTITY_MISMATCH,
                rejected { IrxClientSession.admit(connection, expected) }.code)
        }
    }

    @Test fun controlReplacementConsumesAckPreservesBytesAndObservesWholeConnectionClose() = runBlocking<Unit> {
        fixture(server = { connection ->
            IrxDuplexLane(connection.acceptBi()).use { original ->
                assertEquals("control", original.readFrame()?.getString("lane"))
                original.readFrame()
                original.writeFrame(JSONObject().put("v", 1).put("session", "repair-session")
                    .put("keepaliveIntervalMs", 5000).put("keepaliveDeadlineMs", 2000))
                IrxDuplexLane(connection.acceptBi()).use { replacement ->
                    assertEquals("control_repair", replacement.readFrame()?.getString("lane"))
                    val ack = IrxWire.encode(JSONObject().put("v", 1))
                    replacement.write(ack.copyOfRange(0, 2))
                    replacement.write(ack.copyOfRange(2, ack.size) + "ready".toByteArray())
                    assertEquals("done", readRaw(replacement, 4).decodeToString())
                    connection.close(0, IrxWire.CloseCode.HOST_SHUTDOWN.reason())
                }
            }
        }) { connection, expected ->
            IrxClientSession.admit(connection, expected).use { session ->
                val originalRead = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.control.read(64) } }
                withTimeout(5000) { session.openControlReplacement() }.use { replacement ->
                    assertEquals("ready", readRaw(replacement, 5).decodeToString())
                    originalRead.cancelAndJoin()
                    withTimeout(3000) { session.control.retire() }
                    val closure = async { session.awaitConnectionClosed() }
                    replacement.write("done".toByteArray())
                    withTimeout(3000) { closure.await() }
                    assertTrue(session.connectionIsClosed())
                }
            }
        }
    }

    @Test fun stalledAdmissionClosesNativeConnectionAtDeadline() = runBlocking {
        fixture(server = { connection ->
            connection.acceptBi().use { stream ->
                stream.recv().use { receive -> IrxWire.read { receive.read(it.toUInt()) } }
                assertTrue(connection.closed().contains("irx:admission-timeout"))
            }
        }) { connection, expected ->
            val start = System.nanoTime()
            val error = rejected { IrxClientSession.admit(connection, expected) }
            assertEquals(IrxWire.CloseCode.ADMISSION_TIMEOUT, error.code)
            assertFalse(error.code.terminalForRedial)
            assertTrue("Native read must unblock at deadline", (System.nanoTime() - start) / 1_000_000 < 8_000)
        }
    }

    @Test fun frameBoundsEofVersionAndUnsignedCursorsAreEnforced() = runBlocking<Unit> {
        for (length in listOf(0L, 262145L, 4294967295L)) {
            var calls = 0
            try {
                IrxWire.read { calls++; ByteBuffer.allocate(4).putInt(length.toInt()).array() }
                fail("Invalid length accepted")
            } catch (_: IOException) { assertEquals("Must reject before reading body", 1, calls) }
        }
        assertNull(IrxWire.read { byteArrayOf() })
        var calls = 0
        try {
            IrxWire.read { if (calls++ == 0) byteArrayOf(0) else byteArrayOf() }
            fail("Truncated header accepted")
        } catch (_: IOException) { }
        val json = IrxWire.Descriptor(IrxWire.Lane.TERMINAL, "terminal:test", ULong.MAX_VALUE).json().toString()
        assertTrue(json.contains("\"cursor\":18446744073709551615"))
        assertThrows(IOException::class.java) { IrxWire.admission(JSONObject(
            """{"v":2,"session":"s","keepaliveIntervalMs":5000,"keepaliveDeadlineMs":2000}""")) }
    }

    private suspend fun rejected(block: suspend () -> IrxClientSession): IrxWire.AdmissionRejected {
        try { block().close() } catch (error: IrxWire.AdmissionRejected) { return error }
        throw AssertionError("Expected admission rejection")
    }

    private suspend fun readRaw(lane: IrxDuplexLane, size: Int): ByteArray {
        val result = ByteArray(size)
        var position = 0
        while (position < size) {
            val bytes = lane.read(size - position)
            check(bytes.isNotEmpty())
            bytes.copyInto(result, position); position += bytes.size
        }
        return result
    }

    private suspend fun fixture(server: suspend (Connection) -> Unit,
                                client: suspend (Connection, ByteArray) -> Unit) = withTimeout(15_000) {
        val hostOptions = EndpointOptions(preset = presetMinimal(), bindAddr = "127.0.0.1:0",
            alpns = listOf(IrxWire.ALPN.toByteArray()), portMappingEnabled = false,
            initialMaxConcurrentBiStreams = 1uL, initialMaxConcurrentUniStreams = 0uL)
        val clientOptions = hostOptions.copy(initialMaxConcurrentBiStreams = 0uL)
        val host = Endpoint.bind(hostOptions)
        try {
            val phone = Endpoint.bind(clientOptions)
            try {
                coroutineScope {
                    val clientFinished = CompletableDeferred<Unit>()
                    val peer = async {
                        checkNotNull(host.acceptNext()).use { incoming -> incoming.accept().use { accepting ->
                            accepting.connect().use { connection -> server(connection); clientFinished.await() }
                        } }
                    }
                    try {
                        host.id().use { id -> EndpointAddr(id, null, host.boundSockets()).use { address ->
                            // Ownership of this native connection transfers to IrxClientSession.admit.
                            client(phone.connect(address, IrxWire.ALPN.toByteArray()), id.toBytes())
                        } }
                    } finally { clientFinished.complete(Unit) }
                    peer.await()
                }
            } finally { shutdown(phone) }
        } finally { shutdown(host); hostOptions.destroy() }
    }

    private suspend fun shutdown(endpoint: Endpoint) {
        try { withContext(NonCancellable) { withTimeout(5_000) { endpoint.shutdown() } } }
        finally { endpoint.close() }
    }
}
