package io.github.docmorphic.cmuxapp.iroh

import computer.iroh.BiStream
import computer.iroh.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Owns a bidirectional stream. Frames precede raw application bytes on each lane. */
class IrxDuplexLane internal constructor(private val stream: BiStream) : AutoCloseable {
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
        IrxWire.read { receive.read(it.toUInt()) }
    }
    suspend fun read(maxBytes: Int): ByteArray = readMutex.withLock {
        require(maxBytes in 1..(1024 * 1024))
        check(!closed.get()) { "Irx lane closed" }
        receive.read(maxBytes.toUInt())
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        send.close(); receive.close(); stream.close()
    }
}

/** Owns the native connection after authenticated admission; never replays application input. */
class IrxClientSession private constructor(
    private val connection: Connection,
    val admission: IrxWire.Admission,
    val control: IrxDuplexLane
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    suspend fun openLane(descriptor: IrxWire.Descriptor): IrxDuplexLane {
        require(descriptor.lane != IrxWire.Lane.CONTROL && descriptor.lane != IrxWire.Lane.EVENTS)
        check(!closed.get()) { "Irx session closed" }
        val lane = IrxDuplexLane(connection.openBi())
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
        /**
         * Takes ownership even on failure. expectedPeer comes from the authenticated directory.
         * The endpoint must bind with initial remote bi/uni credit zero and deferred NAT traversal.
         */
        suspend fun admit(connection: Connection, expectedPeer: ByteArray): IrxClientSession {
            var lane: IrxDuplexLane? = null
            try {
                require(expectedPeer.size == 32) { "Expected peer identity must be 32 bytes" }
                connection.remoteId().use { peer ->
                    if (!peer.toBytes().contentEquals(expectedPeer))
                        throw IrxWire.AdmissionRejected(IrxWire.CloseCode.IDENTITY_MISMATCH)
                }
                if (!connection.alpn().contentEquals(IrxWire.ALPN.toByteArray()))
                    throw IrxWire.AdmissionRejected(IrxWire.CloseCode.PROTOCOL_MISMATCH)
                val admission = withTimeout(IrxWire.ADMISSION_TIMEOUT_MS) {
                    val control = IrxDuplexLane(connection.openBi()).also { lane = it }
                    control.writeFrame(IrxWire.Descriptor(IrxWire.Lane.CONTROL).json())
                    control.writeFrame(JSONObject().put("v", 1).put("proto", IrxWire.ALPN))
                    IrxWire.admission(control.readFrame() ?: throw EOFException("Host closed before Irx admission"))
                }
                // Match iOS: shared events + 16 surface output lanes, with replacement headroom.
                connection.setMaxConcurrentBiStreams(0uL)
                connection.setMaxConcurrentUniStreams(40uL)
                return IrxClientSession(connection, admission, checkNotNull(lane))
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
}
