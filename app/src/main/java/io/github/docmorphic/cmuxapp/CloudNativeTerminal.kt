package io.github.docmorphic.cmuxapp

import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

internal class CloudTerminalOutput(val kind: Int, val bytes: ByteArray, val columns: Int, val rows: Int) {
    override fun toString() = "CloudTerminalOutput(kind=$kind, byteCount=${bytes.size}, columns=$columns, rows=$rows)"
}

/** JNI only enqueues here. Never run user code, suspend, or reenter the native client in this callback. */
@Keep
internal class CloudNativeOutputBuffer(private val maxBytes: Int = 64 * 1024 * 1024, private val maxEvents: Int = 1024) {
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val events = ArrayDeque<CloudTerminalOutput>()
    private var bytes = 0L
    private var closed = false
    private var failure: String? = null
    private var generation = 0L

    @Keep fun onOutput(kind: Int, payload: ByteArray, columns: Int, rows: Int) = lock.withLock {
        if (closed || failure != null) return@withLock
        val valid = kind in 1..4 && payload.size <= 16 * 1024 * 1024 && columns in 0..65535 && rows in 0..65535 &&
            (kind < 3 || payload.isEmpty())
        if (!valid || events.size >= maxEvents || bytes + payload.size > maxBytes) {
            // Dropping a VT fragment would silently corrupt every subsequent frame.
            failure = if (!valid) "Invalid Cloud terminal output" else "Cloud terminal output could not keep up"
            events.clear(); bytes = 0
        } else {
            events.addLast(CloudTerminalOutput(kind, payload, columns, rows))
            bytes += payload.size
        }
        available.signalAll()
    }
    fun poll(expectedGeneration: Long, timeoutMillis: Long): CloudTerminalOutput? = lock.withLock {
        require(timeoutMillis >= 0)
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (generation == expectedGeneration && events.isEmpty() && failure == null && !closed && remaining > 0) remaining = available.awaitNanos(remaining)
        check(generation == expectedGeneration) { "Cloud terminal attachment changed" }
        failure?.let { error(it) }
        check(!closed) { "Cloud terminal is closed" }
        events.removeFirstOrNull()?.also { bytes -= it.bytes.size }
    }
    fun reset(): Long = lock.withLock {
        check(!closed && failure == null) { "Cloud terminal output is unavailable" }
        events.clear(); bytes = 0
        generation += 1; available.signalAll(); generation
    }
    fun requireHealthy() = lock.withLock {
        failure?.let { error(it) }
        check(!closed) { "Cloud terminal is closed" }
    }
    fun close() = lock.withLock { closed = true; events.clear(); bytes = 0; available.signalAll() }
}

internal interface CloudNativeCalls {
    fun startTunnel(config: ByteArray): Long
    fun freeTunnel(handle: Long)
    fun routeAllowed(handle: Long, route: ByteArray): Boolean
    fun connect(tunnel: Long, route: ByteArray, directory: ByteArray, device: ByteArray,
        invitation: ByteArray?, trusted: Boolean, timeout: Long, sink: CloudNativeOutputBuffer): Long
    fun disconnect(handle: Long)
    fun outputHealthy(handle: Long): Boolean
    fun attach(handle: Long, terminal: ByteArray, timeout: Long)
    fun detach(handle: Long)
    fun catalog(handle: Long, operation: Int, workspace: ByteArray?, name: ByteArray?, timeout: Long): ByteArray
    fun send(handle: Long, bytes: ByteArray): Boolean
    fun resize(handle: Long, columns: Int, rows: Int): Long
    fun resizeAck(handle: Long): LongArray?
    fun hasExited(handle: Long): Boolean
}

@Keep
internal object CloudNativeBindings : CloudNativeCalls {
    init { System.loadLibrary("cmux_cloud_jni") }
    external fun initialize(context: android.content.Context)
    external override fun startTunnel(config: ByteArray): Long
    external override fun freeTunnel(handle: Long)
    external override fun routeAllowed(handle: Long, route: ByteArray): Boolean
    external override fun connect(tunnel: Long, route: ByteArray, directory: ByteArray, device: ByteArray,
        invitation: ByteArray?, trusted: Boolean, timeout: Long, sink: CloudNativeOutputBuffer): Long
    external override fun disconnect(handle: Long)
    external override fun outputHealthy(handle: Long): Boolean
    external override fun attach(handle: Long, terminal: ByteArray, timeout: Long)
    external override fun detach(handle: Long)
    external override fun catalog(handle: Long, operation: Int, workspace: ByteArray?, name: ByteArray?, timeout: Long): ByteArray
    external override fun send(handle: Long, bytes: ByteArray): Boolean
    external override fun resize(handle: Long, columns: Int, rows: Int): Long
    external override fun resizeAck(handle: Long): LongArray?
    external override fun hasExited(handle: Long): Boolean
}

/** Blocking ownership boundary: call on IO workers. Close waits for admitted operations. */
internal class CloudNativeTunnel private constructor(private val calls: CloudNativeCalls, private var handle: Long) : AutoCloseable {
    private val lock = Any()
    private val clients = mutableSetOf<CloudNativeSession>()

    fun connect(endpoint: CloudAttachEndpoint, stateDirectory: File, deviceName: String, timeoutMillis: Long = 30_000): CloudNativeSession = synchronized(lock) {
        check(handle != 0L) { "Cloud tunnel is closed" }
        require(timeoutMillis in 1..90_000)
        val route = endpoint.route.toByteArray(Charsets.UTF_8)
        if (endpoint.trustedCarrier) check(calls.routeAllowed(handle, route)) { "Cloud route is outside the private tunnel" }
        // This is the Rust daemon enrollment directory, not the WireGuard key store.
        // Let upstream create it with mode 0700; do not pre-create with JVM default permissions.
        val output = CloudNativeOutputBuffer()
        val invitation = endpoint.invitation?.uri?.toByteArray(Charsets.UTF_8)
        val owned = try {
            calls.connect(handle, route, stateDirectory.absolutePath.toByteArray(Charsets.UTF_8), deviceName.toByteArray(Charsets.UTF_8),
                invitation, endpoint.trustedCarrier, timeoutMillis, output).also { check(it != 0L) { "Cloud terminal connection failed" } }
        } catch (failure: Throwable) { output.close(); throw failure }
        finally { invitation?.fill(0) }
        CloudNativeSession(calls, owned, output) { session -> synchronized(lock) { clients.remove(session) } }.also { clients.add(it) }
    }
    override fun close() {
        val retired = synchronized(lock) {
            if (handle == 0L) return
            val result = handle to clients.toList()
            handle = 0; clients.clear(); result
        }
        // No parent lock while closing children: a child may concurrently retire itself.
        retired.second.forEach { it.close() }
        calls.freeTunnel(retired.first)
    }
    companion object {
        fun start(config: CloudWireGuardConfig, calls: CloudNativeCalls = CloudNativeBindings): CloudNativeTunnel {
            val bytes = config.text.toByteArray(Charsets.UTF_8)
            val handle = try { calls.startTunnel(bytes) } finally { bytes.fill(0) }
            check(handle != 0L) { "Cloud tunnel could not start" }
            return CloudNativeTunnel(calls, handle)
        }
    }
}

internal enum class CloudCatalogOperation(val code: Int) { SNAPSHOT(0), WORKSPACES(1), TERMINALS(2), CREATE_WORKSPACE(3), CREATE_TERMINAL(4) }
internal data class CloudResizeAcknowledgment(val requestId: Long, val columns: Int, val rows: Int, val canonicalChanged: Boolean)

/** Catalog/input may run concurrently; attachment changes and disconnect own the exclusive lock. */
internal class CloudNativeSession internal constructor(private val calls: CloudNativeCalls, private var handle: Long,
    private val output: CloudNativeOutputBuffer, private val retired: (CloudNativeSession) -> Unit) : AutoCloseable {
    private val lock = ReentrantReadWriteLock()
    private val retiring = AtomicBoolean(false)
    private var attached: String? = null
    private var generation = 0L
    private fun current(): Long {
        check(!retiring.get() && handle != 0L) { "Cloud terminal is closed" }
        check(calls.outputHealthy(handle)) { "Cloud terminal output delivery failed" }
        output.requireHealthy()
        return handle
    }
    fun attach(terminal: String, timeoutMillis: Long = 30_000): Long = lock.write {
        val live = current()
        require(terminal.isNotEmpty() && timeoutMillis in 1..90_000)
        if (attached == terminal && !calls.hasExited(live)) return@write generation
        if (attached != null) { calls.detach(live); attached = null }
        generation = output.reset()
        calls.attach(live, terminal.toByteArray(Charsets.UTF_8), timeoutMillis)
        attached = terminal
        generation
    }
    fun detach() = lock.write {
        val live = current()
        calls.detach(live); attached = null; generation = output.reset()
    }
    fun catalog(operation: CloudCatalogOperation, workspace: String? = null, name: String? = null,
        timeoutMillis: Long = 30_000): ByteArray = lock.read {
        require(timeoutMillis in 1..90_000)
        if (operation == CloudCatalogOperation.CREATE_TERMINAL) require(workspace?.startsWith("ws_") == true) { "An explicit Cloud workspace is required" }
        calls.catalog(current(), operation.code, workspace?.toByteArray(Charsets.UTF_8), name?.toByteArray(Charsets.UTF_8), timeoutMillis)
    }
    /** False means rejected by the local queue. Never automatically retry accepted input. */
    fun send(bytes: ByteArray): Boolean = lock.read {
        val live = current()
        attached != null && !calls.hasExited(live) && calls.send(live, bytes)
    }
    fun resize(columns: Int, rows: Int): Long = lock.read {
        val live = current()
        require(columns in 1..65535 && rows in 1..65535)
        if (attached == null) 0 else calls.resize(live, columns, rows)
    }
    fun resizeAck(): CloudResizeAcknowledgment? = lock.read {
        calls.resizeAck(current())?.let {
            check(it.size == 4 && it[1] in 1..65535 && it[2] in 1..65535)
            CloudResizeAcknowledgment(it[0], it[1].toInt(), it[2].toInt(), it[3] != 0L)
        }
    }
    /** Bounded wait also checks JNI callback health when no Java event could be delivered. */
    fun nextOutput(attachment: Long, timeoutMillis: Long = 250): CloudTerminalOutput? {
        require(timeoutMillis in 0..1000)
        lock.read { current(); check(attached != null && generation == attachment) { "Cloud terminal attachment changed" } }
        val event = output.poll(attachment, timeoutMillis)
        lock.read { current(); check(generation == attachment) { "Cloud terminal attachment changed" } }
        return event
    }
    /** Fence new input immediately, without waiting for a blocking native catalog call. */
    fun retire() { retiring.set(true); output.close() }
    override fun close() {
        retire()
        lock.write {
            if (handle == 0L) return
            val owned = handle
            handle = 0; attached = null
            try { calls.disconnect(owned) } finally { output.close() }
        }
        retired(this)
    }
}
