package io.github.docmorphic.cmuxapp.iroh

import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.RecvStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Owns a bidirectional stream. Frames precede raw application bytes on each lane. */
class IrxDuplexLane internal constructor(private val stream: BiStream, private val inbound: () -> Unit = {}) : AutoCloseable {
    private val send = stream.send()
    private val receive = stream.recv()
    private val writeMutex = Mutex()
    private val readMutex = Mutex()
    private val closed = AtomicBoolean(false)

    suspend fun write(bytes: ByteArray) = writeMutex.withLock {
        check(!closed.get()) { "Irx lane closed" }
        send.writeAll(bytes)
    }
    suspend fun writeFrame(value: JSONObject) = write(IrxWire.encode(value))
    suspend fun readFrame(): JSONObject? = readMutex.withLock {
        check(!closed.get()) { "Irx lane closed" }
        IrxWire.read { receive.read(it.toUInt()).also { bytes -> if (bytes.isNotEmpty()) inbound() } }
    }
    suspend fun read(maxBytes: Int): ByteArray = readMutex.withLock {
        require(maxBytes in 1..(1024 * 1024))
        check(!closed.get()) { "Irx lane closed" }
        receive.read(maxBytes.toUInt()).also { if (it.isNotEmpty()) inbound() }
    }
    /** Reset both halves independently: a parked reader must not prevent retiring the writer. */
    suspend fun retire(errorCode: ULong = 7uL) {
        try {
            withTimeout(2000) { coroutineScope {
                launch { runCatching { send.reset(errorCode) } }
                launch { runCatching { receive.stop(errorCode) } }
            } }
        } finally { close() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        send.close(); receive.close(); stream.close()
    }
}

/** An independent host event stream. Descriptor bytes are consumed before exposing its payload. */
class IrxIncomingEvents internal constructor(val resource: String?, private val receive: RecvStream,
                                           private val inbound: () -> Unit = {}) : AutoCloseable {
    private val reading = Mutex()
    suspend fun read(maxBytes: Int = 64 * 1024): ByteArray = reading.withLock {
        require(maxBytes in 1..(1024 * 1024))
        receive.read(maxBytes.toUInt()).also { if (it.isNotEmpty()) inbound() }
    }
    suspend fun stop(errorCode: ULong = 0uL) { receive.stop(errorCode) }
    override fun close() { receive.close() }
}

/** Owns the native connection after authenticated admission; never replays application input. */
class IrxClientSession private constructor(
    private val connection: Connection,
    val admission: IrxWire.Admission,
    val control: IrxDuplexLane,
    private val activity: InboundActivity
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()
    val lastInboundNanos: Long? get() = activity.last
    fun connectionIsClosed(): Boolean = closed.get() || connection.closeReason() != null
    suspend fun awaitConnectionClosed() { connection.closed() }

    /** The repair acknowledgement must be consumed before exposing any new RPC bytes. */
    suspend fun openControlReplacement(): IrxDuplexLane {
        val lane = openLane(IrxWire.Descriptor(IrxWire.Lane.CONTROL_REPAIR))
        try {
            IrxWire.requireVersion(lane.readFrame() ?: throw EOFException("Missing control replacement acknowledgement"))
            check(!closed.get())
            return lane
        } catch (failure: Throwable) {
            cleanup.launch { runCatching { lane.retire() } }
            throw failure
        }
    }

    /** Direct path promotion is optional: failure must preserve the admitted relay session. */
    suspend fun authorizeDirectPaths(): Boolean {
        check(!closed.get())
        return try {
            connection.authorizeNatTraversal()
            currentCoroutineContext().ensureActive()
            true
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            false
        }
    }

    suspend fun acceptEvents(): IrxIncomingEvents {
        while (true) {
            currentCoroutineContext().ensureActive()
            check(!closed.get())
            // Failure to accept is a connection failure; an individual descriptor is optional.
            val stream = connection.acceptUni()
            try {
                val descriptor = withTimeout(IrxWire.ADMISSION_TIMEOUT_MS) {
                    IrxWire.read { stream.read(it.toUInt()).also { bytes -> if (bytes.isNotEmpty()) activity.record() } }
                        ?: throw EOFException("Missing Irx event descriptor")
                }
                IrxWire.requireVersion(descriptor)
                if (descriptor.getString("lane") != IrxWire.Lane.EVENTS.wire) throw IOException("Unexpected Irx incoming lane")
                val resource = if (descriptor.isNull("resource")) null else descriptor.getString("resource")
                if (resource != null && (!resource.startsWith("terminal:") || resource.length !in 10..128))
                    throw IOException("Invalid Irx event resource")
                currentCoroutineContext().ensureActive()
                check(!closed.get())
                return IrxIncomingEvents(resource, stream, activity::record)
            } catch (error: Throwable) {
                // Bounded cleanup releases credit without wedging later valid streams.
                withContext(NonCancellable) {
                    try { runCatching { withTimeout(2000) { stream.stop(2uL) } } }
                    finally { stream.close() }
                }
                currentCoroutineContext().ensureActive()
                if (error !is Exception || connectionIsClosed()) throw error
            }
        }
    }

    suspend fun openLane(descriptor: IrxWire.Descriptor): IrxDuplexLane {
        require(descriptor.lane != IrxWire.Lane.CONTROL && descriptor.lane != IrxWire.Lane.EVENTS)
        check(!closed.get()) { "Irx session closed" }
        val lane = IrxDuplexLane(connection.openBi(), activity::record)
        try {
            check(!closed.get()) { "Irx session closed" }
            lane.writeFrame(descriptor.json())
            return lane
        } catch (error: Throwable) {
            lane.close()
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { connection.close(0L, IrxWire.CloseCode.USER_REQUESTED.reason()) }
        finally { control.close(); connection.close() }
    }

    companion object {
        private val cleanup = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        /**
         * Takes ownership even on failure. expectedPeer comes from the authenticated directory.
         * The endpoint must bind with initial remote bi/uni credit zero and deferred NAT traversal.
         */
        suspend fun admit(connection: Connection, expectedPeer: ByteArray): IrxClientSession {
            var lane: IrxDuplexLane? = null
            val activity = InboundActivity()
            try {
                require(expectedPeer.size == 32) { "Expected peer identity must be 32 bytes" }
                connection.remoteId().use { peer ->
                    if (!peer.toBytes().contentEquals(expectedPeer))
                        throw IrxWire.AdmissionRejected(IrxWire.CloseCode.IDENTITY_MISMATCH)
                }
                if (!connection.alpn().contentEquals(IrxWire.ALPN.toByteArray()))
                    throw IrxWire.AdmissionRejected(IrxWire.CloseCode.PROTOCOL_MISMATCH)
                val admission = withTimeout(IrxWire.ADMISSION_TIMEOUT_MS) {
                    val control = IrxDuplexLane(connection.openBi(), activity::record).also { lane = it }
                    control.writeFrame(IrxWire.Descriptor(IrxWire.Lane.CONTROL).json())
                    control.writeFrame(JSONObject().put("v", 1).put("proto", IrxWire.ALPN))
                    IrxWire.admission(control.readFrame() ?: throw EOFException("Host closed before Irx admission"))
                }
                // Match iOS: shared events + 16 surface output lanes, with replacement headroom.
                connection.setMaxConcurrentBiStreams(0uL)
                connection.setMaxConcurrentUniStreams(40uL)
                return IrxClientSession(connection, admission, checkNotNull(lane), activity)
            } catch (error: Throwable) {
                val remoteCode = IrxWire.CloseCode.parse(runCatching { connection.closeReason() }.getOrNull())
                val code = remoteCode ?: (error as? IrxWire.AdmissionRejected)?.code
                    ?: if (error is TimeoutCancellationException) IrxWire.CloseCode.ADMISSION_TIMEOUT else null
                try {
                    connection.close(0L, (code ?: IrxWire.CloseCode.USER_REQUESTED).reason())
                } finally {
                    lane?.close()
                    connection.close()
                }
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (code != null) throw IrxWire.AdmissionRejected(code, error)
                throw IOException("Irx admission failed", error)
            }
        }
    }

    private class InboundActivity {
        @Volatile var last: Long? = null
            private set
        @Synchronized fun record() { last = System.nanoTime() }
    }
}
