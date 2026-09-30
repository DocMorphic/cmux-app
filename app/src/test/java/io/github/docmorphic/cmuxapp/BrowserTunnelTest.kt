package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.iroh.IrxWire
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class BrowserTunnelTest {
    private val golden = JSONObject(checkNotNull(javaClass.getResource("/browser/tunnel-204a11d.json")).readText())
    private fun frame(value: JSONObject) = IrxWire.encode(value)
    private fun connected() = frame(JSONObject().put("v", 1).put("status", "connected"))
    private fun fields(value: JSONObject) = value.keys().asSequence().associateWith { value.get(it) }
    private class Wire(private val bytes: ByteArray = byteArrayOf(), private val chunk: Int = Int.MAX_VALUE) : BrowserTunnelWire {
        var position = 0
        val closed = AtomicBoolean()
        val released = CompletableDeferred<Unit>()
        val writes = mutableListOf<ByteArray>()
        var finishes = 0
        var onRead: (suspend (Int) -> ByteArray)? = null
        var writeFailure = false
        override suspend fun read(maximumBytes: Int): ByteArray {
            onRead?.let { return it(maximumBytes) }
            val end = (position + minOf(maximumBytes, chunk)).coerceAtMost(bytes.size)
            return bytes.copyOfRange(position, end).also { position = end }
        }
        override suspend fun write(bytes: ByteArray) { writes += bytes; if (writeFailure) throw IOException("partial tunnel write") }
        override suspend fun finishSending() { finishes++ }
        override fun close() { if (closed.compareAndSet(false, true)) released.complete(Unit) }
    }

    @Test fun descriptorsAndEveryReplyMatchUnchangedSwiftDefinitions() {
        val descriptors = golden.getJSONArray("descriptors")
        assertEquals(BrowserTunnelProtocol.CAPABILITY, golden.getString("capability"))
        assertEquals(fields(descriptors.getJSONObject(0)), fields(BrowserTunnelProtocol.connect("localhost", 3000).json()))
        assertEquals(fields(descriptors.getJSONObject(1)), fields(BrowserTunnelProtocol.connect("::1", 65535).json()))
        assertEquals(fields(descriptors.getJSONObject(2)), fields(IrxWire.Descriptor(IrxWire.Lane.LISTENING_PORTS).json()))
        val replies = golden.getJSONArray("replies")
        assertEquals(BrowserTunnelProtocol.Status.entries.size, replies.length())
        for (index in 0 until replies.length()) {
            val reply = replies.getJSONObject(index)
            assertEquals(reply.getString("status"), BrowserTunnelProtocol.status(reply).wire)
        }
    }

    @Test fun fragmentedReplyConsumesNoFollowingTcpBytes() = runBlocking<Unit> {
        val response = connected(); val raw = byteArrayOf(0, -1, 13, 10, 65, 66)
        for (fragment in 1..response.size) {
            val wire = Wire(response + raw, fragment)
            IrxBrowserTunnel.open(wire, {}).use { lane ->
                assertEquals(response.size, wire.position)
                val received = mutableListOf<Byte>()
                while (true) received += (lane.read() ?: break).toList()
                assertArrayEquals(raw, received.toByteArray())
            }
            assertTrue(wire.closed.get())
        }
    }

    @Test fun everyRefusalClosesOnlyItsLaneAndPreservesTheStatus() = runBlocking<Unit> {
        val replies = golden.getJSONArray("replies")
        for (index in 0 until replies.length()) {
            val reply = replies.getJSONObject(index)
            if (reply.getString("status") == "connected") continue
            val wire = Wire(frame(reply))
            val failure = runCatching { IrxBrowserTunnel.open(wire, {}) }.exceptionOrNull()
            assertTrue(failure is BrowserTunnelProtocol.OpenFailure)
            assertEquals(reply.getString("status"), (failure as BrowserTunnelProtocol.OpenFailure).status.wire)
            assertTrue(wire.closed.get()); assertTrue(wire.writes.isEmpty())
        }
    }

    @Test fun halfCloseKeepsReceivingAndPartialWriteIsNeverRetried() = runBlocking<Unit> {
        val wire = Wire(connected() + "server response".toByteArray())
        IrxBrowserTunnel.open(wire, {}).use { lane ->
            lane.write("request".toByteArray()); lane.finishSending(); lane.finishSending()
            assertEquals(1, wire.finishes)
            assertEquals("server response", lane.read()!!.toString(Charsets.UTF_8))
            assertNull(lane.read()); assertFalse(wire.closed.get())
        }
        val partial = Wire(connected()).apply { writeFailure = true }
        val lane = IrxBrowserTunnel.open(partial, {})
        assertTrue(runCatching { lane.write("once".toByteArray()) }.isFailure)
        assertEquals(1, partial.writes.size); assertTrue(partial.closed.get())
        assertTrue(runCatching { lane.write("again".toByteArray()) }.isFailure)
        assertEquals(1, partial.writes.size)
    }

    @Test fun deadlineClosesEvenANonCancellableStartedReplyRead() = runBlocking<Unit> {
        val wire = Wire()
        wire.onRead = { withContext(NonCancellable) { wire.released.await() }; byteArrayOf() }
        val result = withTimeout(2000) { runCatching { IrxBrowserTunnel.open(wire, {}, replyTimeoutMillis = 30) } }
        assertTrue(result.isFailure); assertTrue(wire.closed.get())
    }

    @Test fun staleReadAfterRevocationIsNotDelivered() = runBlocking<Unit> {
        var allowed = true
        val wire = Wire(connected())
        val lane = IrxBrowserTunnel.open(wire, { check(allowed) })
        wire.onRead = { allowed = false; "late data".toByteArray() }
        assertTrue(runCatching { lane.read() }.isFailure)
        assertTrue(wire.closed.get())
    }

    @Test fun malformedRepliesAndTruncationCloseWithoutEnablingRawTraffic() = runBlocking<Unit> {
        val invalid = listOf(byteArrayOf(), connected().dropLast(1).toByteArray(), byteArrayOf(0x7f, -1, -1, -1),
            frame(JSONObject().put("v", 2).put("status", "connected")),
            frame(JSONObject().put("v", "1").put("status", "connected")),
            frame(JSONObject().put("v", 1).put("status", "future")))
        for (bytes in invalid) {
            val wire = Wire(bytes)
            assertTrue(runCatching { IrxBrowserTunnel.open(wire, {}) }.isFailure)
            assertTrue(wire.closed.get()); assertTrue(wire.writes.isEmpty())
        }
    }

    @Test fun listeningPortsKeepDuplicateAddressChoicesAndRejectNonLoopbackOrCoercedFields() = runBlocking<Unit> {
        val listing = golden.getJSONObject("listing")
        val wire = Wire(frame(listing), 1)
        val result = IrxBrowserTunnel.listeningPorts(wire, {})
        assertEquals(listOf(80, 3000, 3000), result.ports.map { it.port })
        assertEquals(listOf("127.0.0.1", "::1", "127.9.8.7"), result.ports.map { it.address })
        assertTrue(result.allowsNonLoopbackHosts); assertTrue(wire.closed.get())
        for (address in listOf("localhost", "192.168.1.2", "169.254.169.254", "127.1", "127.0.0.01", "127.0.0.999", "::")) {
            val bad = JSONObject(listing.toString())
            bad.getJSONArray("ports").getJSONObject(0).put("address", address)
            assertTrue(address, runCatching { BrowserTunnelProtocol.ports(bad) }.isFailure)
        }
        for (port in listOf<Any>(0, 65536, "80", 80.5, true)) {
            val bad = JSONObject(listing.toString()); bad.getJSONArray("ports").getJSONObject(0).put("port", port)
            assertTrue(runCatching { BrowserTunnelProtocol.ports(bad) }.isFailure)
        }
        assertTrue(runCatching { BrowserTunnelProtocol.ports(JSONObject(listing.toString()).put("allowsNonLoopbackHosts", "true")) }.isFailure)
    }

    @Test fun leaseClosureCancelsItsTunnelWithoutClosingSharedControl() = runBlocking<Unit> {
        val control = PoolTestTransport(); val wire = Wire(connected())
        val transport = object : MobileRpcTransport by control {
            override val supportsBrowserTunnels = true
            override suspend fun openBrowserTunnel(host: String, port: Int) = IrxBrowserTunnel.open(wire, {})
        }
        MobileRpcClient(transport, { "fixture" }).use { client ->
            client.connect()
            val lease = client.lease {}; val started = CompletableDeferred<Unit>()
            val task = launch { lease.useBrowserTunnel("localhost", 3000) { started.complete(Unit); awaitCancellation() } }
            withTimeout(2000) { started.await() }
            lease.close(); withTimeout(2000) { task.join() }
            assertTrue(wire.closed.get()); assertFalse(client.isClosed); assertEquals(0, control.closes.get())
            val response = async { client.workspaces() }
            control.answer(withTimeout(2000) { control.sent.receive() })
            assertEquals("mobile.workspace.list", withTimeout(2000) { response.await() }.getString("method"))
        }
    }

    @Test fun invalidDestinationsFailBeforeOpeningAndLegacyRouteRemainsUnsupported() = runBlocking<Unit> {
        for (host in listOf("", "a".repeat(254), "host\nname", "host name", "host\u0000name"))
            assertTrue(runCatching { BrowserTunnelProtocol.connect(host, 3000) }.isFailure)
        for (port in listOf(-1, 0, 65536)) assertTrue(runCatching { BrowserTunnelProtocol.connect("localhost", port) }.isFailure)
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect(); assertFalse(client.supportsBrowserTunnels)
            assertFalse(client.useBrowserTunnel("localhost", 3000) { fail("Legacy route opened a tunnel") })
            assertNull(client.browserListeningPorts())
        }
    }
}
