package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CloudNativeTerminalTest {
    @get:Rule val temp = TemporaryFolder()
    private class Fake : CloudNativeCalls {
        val calls = CopyOnWriteArrayList<String>()
        val sinks = mutableMapOf<Long, CloudNativeOutputBuffer>()
        var healthy = true; var allowed = true; var exited = false; var next = 10L
        var savedConfig: ByteArray? = null
        var catalogWork: (() -> Unit)? = null
        override fun startTunnel(config: ByteArray): Long { savedConfig = config; calls += "start"; return 1 }
        override fun freeTunnel(handle: Long) { calls += "free:$handle" }
        override fun routeAllowed(handle: Long, route: ByteArray): Boolean { calls += "route"; return allowed }
        override fun connect(tunnel: Long, route: ByteArray, directory: ByteArray, device: ByteArray, invitation: ByteArray?, trusted: Boolean, timeout: Long, sink: CloudNativeOutputBuffer): Long {
            calls += "connect:$trusted"; return next++.also { sinks[it] = sink }
        }
        override fun disconnect(handle: Long) { calls += "disconnect:$handle" }
        override fun outputHealthy(handle: Long) = healthy
        override fun attach(handle: Long, terminal: ByteArray, timeout: Long) { calls += "attach:${terminal.toString(Charsets.UTF_8)}" }
        override fun detach(handle: Long) { calls += "detach:$handle" }
        override fun catalog(handle: Long, operation: Int, workspace: ByteArray?, name: ByteArray?, timeout: Long): ByteArray {
            catalogWork?.invoke(); return "{}".toByteArray()
        }
        override fun send(handle: Long, bytes: ByteArray): Boolean { calls += "send:$handle"; return true }
        override fun resize(handle: Long, columns: Int, rows: Int) = 8L
        override fun resizeAck(handle: Long) = longArrayOf(8, 80, 24, 1)
        override fun hasExited(handle: Long) = exited
    }
    private fun tunnel(fake: Fake) = CloudNativeTunnel.start(CloudWireGuardConfig.complete("[Interface]\n[Peer]", CloudWireGuardKey.generate()), fake)
    private fun endpoint(trusted: Boolean = true) = CloudAttachEndpoint("ws://10.0.0.2:7", "s", null, trusted)

    @Test fun tunnelRetiresAllClientsBeforeFreeAndCloseIsIdempotent() {
        val fake = Fake(); val tunnel = tunnel(fake)
        assertTrue(fake.savedConfig!!.all { it == 0.toByte() })
        val a = tunnel.connect(endpoint(), temp.newFolder(), "Pixel")
        val b = tunnel.connect(endpoint(false), temp.newFolder(), "Pixel")
        a.close(); a.close(); tunnel.close(); tunnel.close(); b.close()
        assertEquals(listOf("disconnect:10", "disconnect:11", "free:1"), fake.calls.filter { it.startsWith("disconnect") || it.startsWith("free") })
        assertTrue(runCatching { b.send(byteArrayOf(1)) }.isFailure)
        assertTrue(runCatching { tunnel.connect(endpoint(), temp.newFolder(), "Pixel") }.isFailure)
    }
    @Test fun explicitTrustedRouteRequiresNativeCoverageBeforeConnect() {
        val fake = Fake().apply { allowed = false }; val tunnel = tunnel(fake)
        assertTrue(runCatching { tunnel.connect(endpoint(), temp.newFolder(), "Pixel") }.isFailure)
        assertFalse(fake.calls.any { it.startsWith("connect") })
        tunnel.close()
    }
    @Test fun attachmentEpochPreventsOldConsumerTakingNewTerminalBytes() {
        val fake = Fake(); val tunnel = tunnel(fake)
        val session = tunnel.connect(endpoint(), temp.newFolder(), "Pixel")
        val first = session.attach("term_first")
        assertEquals(first, session.attach("term_first"))
        fake.sinks.getValue(10).onOutput(1, "old".toByteArray(), 80, 24)
        val second = session.attach("term_second")
        assertNotEquals(first, second)
        fake.sinks.getValue(10).onOutput(1, "new".toByteArray(), 80, 24)
        assertTrue(runCatching { session.nextOutput(first, 0) }.isFailure)
        assertEquals("new", session.nextOutput(second, 0)!!.bytes.toString(Charsets.UTF_8))
        assertTrue(fake.calls.indexOf("detach:10") < fake.calls.indexOf("attach:term_second"))
        tunnel.close()
    }
    @Test fun sendRequiresLiveAttachmentAndNativeCallbackFailureStopsOperations() {
        val fake = Fake(); val tunnel = tunnel(fake)
        val session = tunnel.connect(endpoint(), temp.newFolder(), "Pixel")
        assertFalse(session.send(byteArrayOf(1)))
        val generation = session.attach("term_first")
        assertTrue(session.send(byteArrayOf(1)))
        assertEquals(8L, session.resize(80, 24))
        assertEquals(CloudResizeAcknowledgment(8, 80, 24, true), session.resizeAck())
        fake.exited = true; assertFalse(session.send(byteArrayOf(1)))
        fake.healthy = false
        assertTrue(runCatching { session.nextOutput(generation, 0) }.isFailure)
        assertTrue(runCatching { session.send(byteArrayOf(1)) }.isFailure)
        fake.healthy = true
        fake.sinks.getValue(10).onOutput(99, byteArrayOf(), 0, 0)
        assertTrue(runCatching { session.send(byteArrayOf(1)) }.isFailure)
        tunnel.close()
    }
    @Test fun forceReplayAndGenerationFencesProtectTheReplacementSlot() {
        val fake = Fake(); val tunnel = tunnel(fake)
        try {
            val session = tunnel.connect(endpoint(), temp.newFolder(), "Pixel")
            val old = session.attach("term_a")
            val replay = session.attach("term_a", force = true)
            assertNotEquals(old, replay)
            val detachCount = fake.calls.count { it.startsWith("detach:") }
            session.detachIfCurrent(old)
            assertEquals(detachCount, fake.calls.count { it.startsWith("detach:") })
            assertFalse(session.sendAttached(old, byteArrayOf(1)))
            assertEquals(0L, session.resizeAttached(old, 90, 30))
            assertTrue(session.sendAttached(replay, byteArrayOf(1)))
            assertEquals(8L, session.resizeAttached(replay, 90, 30))
            session.detachIfCurrent(replay)
            assertFalse(session.sendAttached(replay, byteArrayOf(1)))
        } finally { tunnel.close() }
    }
    @Test fun slowCatalogDoesNotBlockInputAndCloseWaitsForTheAdmittedCall() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val fake = Fake().apply { catalogWork = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) } }
        val tunnel = tunnel(fake); val session = tunnel.connect(endpoint(), temp.newFolder(), "Pixel")
        session.attach("term_first")
        val executor = Executors.newFixedThreadPool(3)
        try {
            val catalog = executor.submit<ByteArray> { session.catalog(CloudCatalogOperation.SNAPSHOT) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(executor.submit<Boolean> { session.send(byteArrayOf(1)) }.get(1, TimeUnit.SECONDS))
            session.retire()
            assertTrue(executor.submit<Boolean> { runCatching { session.send(byteArrayOf(2)) }.isFailure }
                .get(1, TimeUnit.SECONDS))
            assertFalse(fake.calls.contains("disconnect:10"))
            val closeStarted = CountDownLatch(1)
            val close = executor.submit { closeStarted.countDown(); session.close() }
            assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
            assertFalse(fake.calls.contains("disconnect:10"))
            release.countDown(); catalog.get(1, TimeUnit.SECONDS); close.get(1, TimeUnit.SECONDS)
            assertTrue(fake.calls.contains("disconnect:10"))
        } finally { release.countDown(); executor.shutdownNow(); tunnel.close() }
    }
    @Test fun bufferFailsExplicitlyOnOverflowInsteadOfDroppingVtBytes() {
        val buffer = CloudNativeOutputBuffer(maxBytes = 4, maxEvents = 2)
        buffer.onOutput(2, byteArrayOf(1, 2, 3), 0, 0)
        buffer.onOutput(2, byteArrayOf(4, 5), 0, 0)
        assertTrue(runCatching { buffer.poll(0, 0) }.isFailure)
        assertTrue(runCatching { buffer.reset() }.isFailure)
        val invalid = CloudNativeOutputBuffer()
        invalid.onOutput(3, byteArrayOf(1), 80, 24)
        assertTrue(runCatching { invalid.poll(0, 0) }.isFailure)
    }
    @Test fun resetWakesWaitingOldConsumerWithoutConsumingNewSnapshot() {
        val buffer = CloudNativeOutputBuffer()
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> { started.countDown(); runCatching { buffer.poll(0, 5000) }.isFailure }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val next = buffer.reset()
            buffer.onOutput(1, byteArrayOf(42), 80, 24)
            assertTrue(result.get(1, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(42), buffer.poll(next, 0)!!.bytes)
        } finally { buffer.close(); executor.shutdownNow() }
    }
    @Test fun resnapshotPinsGridThenResetsAndChunksReplayForGhostty() {
        val reducer = CloudTerminalOutputReducer()
        val first = reducer.reduce(CloudTerminalOutput(1, "first".toByteArray(), 80, 24))
        assertEquals(CloudTerminalOutputReducer.Action.Grid(80, 24), first[0])
        assertEquals("first", (first[1] as CloudTerminalOutputReducer.Action.Write).bytes.toString(Charsets.UTF_8))
        val bytes = ByteArray(2 * 1024 * 1024 + 3) { (it % 127).toByte() }
        val replay = reducer.reduce(CloudTerminalOutput(1, bytes, 90, 30))
        assertEquals(CloudTerminalOutputReducer.Action.Grid(90, 30), replay[0])
        val writes = replay.drop(1).map { (it as CloudTerminalOutputReducer.Action.Write).bytes }
        assertArrayEquals(byteArrayOf(0x1b, 0x63), writes[0])
        assertArrayEquals(bytes, writes[1] + writes[2])
        assertTrue(writes.all { it.size <= 2 * 1024 * 1024 })
        assertTrue(reducer.reduce(CloudTerminalOutput(2, byteArrayOf(), 0, 0)).isEmpty())
        assertTrue(reducer.reduce(CloudTerminalOutput(3, byteArrayOf(), 0, 0)).isEmpty())
        assertEquals(listOf(CloudTerminalOutputReducer.Action.Exited), reducer.reduce(CloudTerminalOutput(4, byteArrayOf(), 0, 0)))
    }
}
