package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.*
import io.github.docmorphic.cmuxapp.iroh.IrohRuntime
import io.github.docmorphic.cmuxapp.iroh.IrxClientSession
import io.github.docmorphic.cmuxapp.iroh.IrxWire
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Generated loopback QUIC peers only: no directory, accounts, relay, real Macs or phone fixtures. */
class NativeBrowserTunnelTest {
    private suspend fun until(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(10) }
    private suspend fun frame(reader: RecvStream) = checkNotNull(IrxWire.read { reader.read(it.toUInt()) })
    private fun status(value: String) = IrxWire.encode(JSONObject().put("v", 1).put("status", value))
    private suspend fun fragmented(writer: SendStream, bytes: ByteArray) {
        writer.writeAll(bytes.copyOfRange(0, 1)); writer.writeAll(bytes.copyOfRange(1, 5)); writer.writeAll(bytes.copyOfRange(5, bytes.size))
    }
    private suspend fun hostStatus(reader: RecvStream, writer: SendStream) {
        val request = frame(reader)
        assertEquals("mobile.host.status", request.getString("method"))
        writer.writeAll(IrxWire.encode(JSONObject().put("id", request.getString("id")).put("ok", true)
            .put("result", JSONObject().put("healthy", true))))
    }
    private fun fixture(server: suspend CoroutineScope.(Connection, RecvStream, SendStream) -> Unit,
        client: suspend CoroutineScope.(MobileRpcClient) -> Unit) = runBlocking<Unit> {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Disposable emulator required" }
        IrohRuntime.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        withTimeout(30_000) {
            val options = EndpointOptions(preset = presetMinimal(), bindAddr = "127.0.0.1:0", relayMode = RelayMode.disabled(),
                alpns = listOf(IrxWire.ALPN.toByteArray()), portMappingEnabled = false,
                initialMaxConcurrentBiStreams = 1uL, initialMaxConcurrentUniStreams = 0uL)
            val phoneOptions = options.copy(initialMaxConcurrentBiStreams = 0uL)
            val host = Endpoint.bind(options)
            val phone = Endpoint.bind(phoneOptions)
            val finish = CompletableDeferred<Unit>()
            var primaryFailure: Throwable? = null
            try {
                val peer = async {
                    checkNotNull(host.acceptNext()).use { incoming -> incoming.accept().use { accepting ->
                        accepting.connect().use { connection -> connection.acceptBi().use { control ->
                            control.recv().use { reader -> control.send().use { writer ->
                                assertEquals("control", frame(reader).getString("lane"))
                                assertEquals(IrxWire.ALPN, frame(reader).getString("proto"))
                                writer.writeAll(IrxWire.encode(JSONObject().put("v", 1).put("session", "browser-native-fixture")
                                    .put("keepaliveIntervalMs", 5000).put("keepaliveDeadlineMs", 2000)))
                                connection.setMaxConcurrentBiStreams(40uL)
                                server(connection, reader, writer)
                                finish.await()
                            } }
                        } }
                    } }
                }
                try {
                    host.id().use { id -> EndpointAddr(id, null, host.boundSockets()).use { address ->
                        val native = phone.connect(address, IrxWire.ALPN.toByteArray())
                        val transport = IrxMobileRpcTransport({ IrxClientSession.admit(native, id.toBytes()) }, { true },
                            MutableStateFlow(IrxProbeActivity(false)))
                        MobileRpcClient(transport, { "browser-native-fixture-token" }).use { rpc -> rpc.connect(); client(rpc); finish.complete(Unit); peer.await() }
                    } }
                } finally { finish.complete(Unit); peer.cancelAndJoin() }
            } catch (failure: Throwable) { primaryFailure = failure; throw failure }
            finally {
                finish.complete(Unit)
                withContext(NonCancellable) {
                    val cleanup = runCatching { withTimeout(5_000) { phone.shutdown(); host.shutdown() } }
                    phone.close(); host.close(); phoneOptions.destroy(); options.destroy()
                    cleanup.exceptionOrNull()?.let { failure ->
                        if (primaryFailure != null) primaryFailure.addSuppressed(failure) else throw failure
                    }
                }
            }
        }
    }

    @Test fun nativeListingAndDuplexBytesPreserveCoalescedDataAndHalfClosedResponseTail() {
        val upload = ByteArray(196731) { ((it * 17) % 251).toByte() }
        val download = ByteArray(262259) { ((it * 29) % 253).toByte() }
        val tail = "response after client EOF".toByteArray()
        fixture(server = { connection, reader, writer ->
            connection.acceptBi().use { stream -> stream.recv().use { input -> stream.send().use { output ->
                val descriptor = frame(input)
                assertEquals(setOf("v", "lane"), descriptor.keys().asSequence().toSet())
                assertEquals("listening_ports", descriptor.getString("lane"))
                fragmented(output, IrxWire.encode(JSONObject().put("v", 1).put("allowsNonLoopbackHosts", true)
                    .put("ports", JSONArray().put(JSONObject().put("port", 34876).put("address", "127.0.0.1")))))
                output.finish()
            } } }
            connection.acceptBi().use { stream -> stream.recv().use { input -> stream.send().use { output ->
                val descriptor = frame(input)
                assertEquals(setOf("v", "lane", "host", "port"), descriptor.keys().asSequence().toSet())
                assertEquals("tcp_connect", descriptor.getString("lane")); assertEquals("dev.localhost", descriptor.getString("host"))
                assertEquals(34876, descriptor.getInt("port"))
                // One write ends the frame and begins raw bytes. Frame reading must not eat the prefix.
                val reply = status("connected")
                output.writeAll(reply.copyOfRange(0, 3))
                output.writeAll(reply.copyOfRange(3, reply.size) + download.copyOfRange(0, 131))
                coroutineScope {
                    val body = launch { output.writeAll(download.copyOfRange(131, download.size)) }
                    val received = ByteArrayOutputStream()
                    while (true) { val bytes = input.read(65536u); if (bytes.isEmpty()) break; received.write(bytes) }
                    assertArrayEquals(upload, received.toByteArray())
                    body.join(); output.writeAll(tail); output.finish()
                }
                hostStatus(reader, writer)
            } } }
        }, client = { rpc ->
            assertEquals(BrowserTunnelProtocol.ListeningPorts(listOf(BrowserTunnelProtocol.ListeningPort(34876, "127.0.0.1")), true), rpc.browserListeningPorts())
            assertTrue(rpc.useBrowserTunnel("dev.localhost", 34876) { lane ->
                coroutineScope {
                    val sending = launch {
                        for (offset in upload.indices step 65536) lane.write(upload.copyOfRange(offset, minOf(offset + 65536, upload.size)))
                        lane.finishSending(); lane.finishSending()
                    }
                    val received = ByteArrayOutputStream()
                    while (true) { val bytes = lane.read(8191) ?: break; assertTrue(bytes.size <= 8191); received.write(bytes) }
                    sending.join(); assertArrayEquals(download + tail, received.toByteArray())
                }
            })
            assertTrue(rpc.hostStatus().getBoolean("healthy")); assertFalse(rpc.isClosed)
        })
    }

    @Test fun rejectionAndCancellingPendingBorrowedHandshakeLeaveNativeControlUsable() {
        val pending = CompletableDeferred<Unit>(); val reset = CompletableDeferred<Unit>()
        val releases = AtomicInteger()
        fixture(server = { connection, reader, writer ->
            for (rejected in listOf("denied", "refused", "busy")) {
                connection.acceptBi().use { stream -> stream.recv().use { input -> stream.send().use { output ->
                    assertEquals(rejected, frame(input).getString("host"))
                    output.writeAll(status(rejected)); output.finish()
                } } }
            }
            connection.acceptBi().use { stream -> stream.recv().use { input ->
                assertEquals("pending.localhost", frame(input).getString("host")); pending.complete(Unit)
                // The client is still waiting for the status. Its lease cancellation must reset this lane.
                val ending = runCatching { withTimeout(5_000) { input.read(1u) } }
                if (ending.exceptionOrNull() is TimeoutCancellationException) throw checkNotNull(ending.exceptionOrNull())
                assertTrue(ending.isFailure || ending.getOrThrow().isEmpty()); reset.complete(Unit)
            } }
            hostStatus(reader, writer)
        }, client = { rpc ->
            for (rejected in listOf(BrowserTunnelProtocol.Status.DENIED, BrowserTunnelProtocol.Status.REFUSED, BrowserTunnelProtocol.Status.BUSY)) {
                val failure = runCatching { rpc.useBrowserTunnel(rejected.wire, 34876) { fail("Rejected tunnel was handed out") } }.exceptionOrNull()
                assertTrue(failure is BrowserTunnelProtocol.OpenFailure)
                assertEquals(rejected, (failure as BrowserTunnelProtocol.OpenFailure).status)
            }
            val lease = rpc.lease { releases.incrementAndGet() }
            val operation = async { runCatching { lease.useBrowserTunnel("pending.localhost", 34876) { fail("Unacknowledged lane handed out") } } }
            pending.await(); lease.close()
            assertTrue(operation.await().exceptionOrNull() is CancellationException)
            withTimeout(5_000) { reset.await() }
            assertEquals(1, releases.get()); assertFalse(rpc.isClosed)
            assertTrue(rpc.hostStatus().getBoolean("healthy"))
        })
    }

    @Test fun admittedCoordinatorAndOwnerProxyReachHttpServerThroughNativeLane() {
        val mac = NativeCredentialStore.PairedMac("generated-route", "fixture-mac", "Fixture Mac", "default", "fixture-user", "fixture-team", "fixture-origin")
        val body = "native browser HTTP body\n".repeat(4097)
        val requests = AtomicInteger()
        MockWebServer().use { site ->
            site.enqueue(MockResponse().setHeader("Connection", "close").setBody(body)); site.start()
            fixture(server = { connection, reader, writer ->
                val control = launch {
                    while (true) {
                        val request = frame(reader)
                        assertEquals("browser-native-fixture-token", request.getJSONObject("auth").getString("stack_access_token"))
                        val result = when (request.getString("method")) {
                            "mobile.host.status" -> JSONObject().put("mac_device_id", mac.deviceId).put("mac_instance_tag", mac.instanceTag)
                                .put("capabilities", JSONArray().put(BrowserTunnelProtocol.CAPABILITY))
                            "mobile.workspace.list" -> JSONObject().put("workspaces", JSONArray())
                            "mobile.events.subscribe", "mobile.events.unsubscribe" -> JSONObject()
                            "notification.feed.list" -> JSONObject().put("revision", 1).put("notifications", JSONArray())
                            else -> error("Unexpected control request: " + request.getString("method"))
                        }
                        writer.writeAll(IrxWire.encode(JSONObject().put("id", request.getString("id")).put("ok", true).put("result", result)))
                    }
                }
                try {
                    connection.acceptBi().use { stream -> stream.recv().use { input -> stream.send().use { output ->
                        assertEquals("listening_ports", frame(input).getString("lane"))
                        output.writeAll(IrxWire.encode(JSONObject().put("v", 1).put("allowsNonLoopbackHosts", false)
                            .put("ports", JSONArray().put(JSONObject().put("port", site.port).put("address", "127.0.0.1")))))
                        output.finish()
                    } } }
                    connection.acceptBi().use { stream -> stream.recv().use { input -> stream.send().use { output ->
                        val descriptor = frame(input)
                        assertEquals("tcp_connect", descriptor.getString("lane")); assertEquals("project.localhost", descriptor.getString("host"))
                        assertEquals(site.port, descriptor.getInt("port")); requests.incrementAndGet()
                        NioBrowserSocket.direct.use("127.0.0.1", site.port) { tcp ->
                            output.writeAll(status("connected"))
                            relayBrowser(object : BrowserTunnelLane {
                                override suspend fun read(maximumBytes: Int) = input.read(maximumBytes.toUInt()).takeIf { it.isNotEmpty() }
                                override suspend fun write(bytes: ByteArray) = output.writeAll(bytes)
                                override suspend fun finishSending() = output.finish()
                                override fun close() { input.close(); output.close() }
                            }, tcp)
                        }
                    } } }
                } finally { control.cancelAndJoin() }
            }, client = { rpc ->
                val coordinator = NativeFeedCoordinator(this, { rpc.lease {} }, { true })
                try {
                    coordinator.updateMacs(listOf(mac))
                    try { until { coordinator.sources.value[mac.origin]?.availability == NativeFeedAvailability.CONNECTED } }
                    catch (failure: TimeoutCancellationException) {
                        throw AssertionError("Coordinator did not become ready: " + coordinator.sources.value[mac.origin], failure)
                    }
                    NativeMacBrowserNetwork(this, coordinator.browserAccess(mac) { true }, { true }).use { network ->
                        assertEquals(MacBrowserAvailability.AVAILABLE, network.availability())
                        val port = network.prepare(site.port)
                        val received = withContext(Dispatchers.IO) {
                            Socket("127.0.0.1", port).use { socket ->
                                socket.soTimeout = 10_000
                                val out = socket.getOutputStream(); val input = DataInputStream(socket.getInputStream())
                                out.write(byteArrayOf(5, 1, 0)); assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                                val host = "project.localhost".toByteArray()
                                out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf((site.port ushr 8).toByte(), site.port.toByte()))
                                val reply = ByteArray(10); input.readFully(reply); assertEquals(0, reply[1].toInt())
                                out.write("GET /native-browser HTTP/1.1\r\nHost: project.localhost\r\nConnection: close\r\n\r\n".toByteArray()); socket.shutdownOutput()
                                input.readBytes().decodeToString()
                            }
                        }
                        assertTrue(received.startsWith("HTTP/1.1 200")); assertEquals(body, received.substringAfter("\r\n\r\n"))
                        assertEquals("/native-browser", site.takeRequest().path); assertEquals(1, requests.get())
                        assertFalse(rpc.isClosed)
                    }
                } finally { coordinator.close() }
            })
        }
    }
}
