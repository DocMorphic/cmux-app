package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.iroh.IrxClientSession
import io.github.docmorphic.cmuxapp.iroh.IrxDuplexLane
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

internal interface MobileEventLane : AutoCloseable {
    val resource: String?
    suspend fun read(): ByteArray
    suspend fun stop()
}

/** Each native stream has its own decoder; only complete frames enter the common event queue. */
internal class IrxEventMultiplexer(private val accept: suspend () -> MobileEventLane,
                                  private val permits: () -> Boolean) {
    private class Reader(val lane: MobileEventLane, var job: Job? = null)

    val frames: Flow<ByteArray> = channelFlow {
        val lanes = mutableMapOf<String?, Reader>()
        fun allowed() { if (!permits()) throw CancellationException("Mac access changed") }
        try {
            while (true) {
                allowed()
                val lane = accept()
                val reader = Reader(lane)
                val previous = synchronized(lanes) {
                    if (!lanes.containsKey(lane.resource) && lanes.size >= 17) {
                        lane.close()
                        throw IOException("Too many Irx event lanes")
                    }
                    lanes.put(lane.resource, reader)
                }
                previous?.job?.cancel()
                previous?.let { runCatching { it.lane.stop() } }
                reader.job = launch(start = CoroutineStart.LAZY) {
                    try {
                        val decoder = MobileFrameDecoder()
                        while (true) {
                            val bytes = lane.read()
                            if (bytes.isEmpty()) break
                            allowed()
                            for (frame in decoder.feed(bytes)) {
                                if (synchronized(lanes) { lanes[lane.resource] !== reader }) return@launch
                                send(frame)
                            }
                        }
                    } catch (error: Exception) {
                        if (synchronized(lanes) { lanes[lane.resource] === reader }) throw error
                    } finally {
                        withContext(NonCancellable) { runCatching { lane.stop() } }
                        lane.close()
                        synchronized(lanes) { if (lanes[lane.resource] === reader) lanes.remove(lane.resource) }
                    }
                }
                reader.job!!.start()
            }
        } finally {
            val remaining = synchronized(lanes) { lanes.values.toList().also { lanes.clear() } }
            remaining.forEach { it.job?.cancel() }
            withContext(NonCancellable) {
                remaining.forEach { runCatching { it.lane.stop() }; it.lane.close() }
            }
        }
    }.buffer(8)
}

/** Retires this admitted QUIC session on close; the account runtime owns the shared endpoint. */
internal class IrxMobileRpcTransport(
    private val establish: suspend () -> IrxClientSession,
    private val permits: () -> Boolean
) : MobileRpcTransport {
    private val lock = Any()
    private val connecting = Mutex()
    private var session: IrxClientSession? = null
    private var control: IrxControlChannel? = null
    private var dial: Job? = null
    private var closed = false
    override val surfaceEventLanes = true
    override val supportsControlRepair = true
    override val disconnections = flow<Throwable> {
        active().awaitConnectionClosed()
        emit(java.io.EOFException("cmux connection closed"))
    }
    override val independentEvents: Flow<ByteArray> = IrxEventMultiplexer(accept = {
        val lane = active().acceptEvents()
        object : MobileEventLane {
            override val resource = lane.resource
            override suspend fun read() = lane.read()
            override suspend fun stop() = lane.stop()
            override fun close() = lane.close()
        }
    }, permits = { !synchronized(lock) { closed } && permits() }).frames

    override suspend fun connect(): Unit = connecting.withLock { coroutineScope {
        synchronized(lock) { check(!closed); if (session != null) return@coroutineScope }
        val operation = checkNotNull(currentCoroutineContext()[Job])
        synchronized(lock) { check(!closed); dial = operation }
        try {
            requireAccess()
            val admitted = establish()
            try {
                currentCoroutineContext().ensureActive()
                requireAccess()
                synchronized(lock) {
                    check(!closed)
                    session = admitted
                    control = IrxControlChannel(admitted.control.asControlLane(),
                        replacement = { admitted.openControlReplacement().asControlLane() },
                        permits = { !synchronized(lock) { closed } && permits() },
                        connectionClosed = admitted::connectionIsClosed)
                }
            } catch (error: Throwable) { admitted.close(); throw error }
        } finally { synchronized(lock) { if (dial === operation) dial = null } }
    } }
    override suspend fun read(): ByteArray? {
        requireAccess()
        val bytes = channel().read()
        requireAccess()
        return bytes.takeIf { it.isNotEmpty() }
    }
    override suspend fun write(bytes: ByteArray) {
        writeWithGeneration(bytes)
    }
    override suspend fun writeWithGeneration(bytes: ByteArray): Long {
        requireAccess()
        return channel().write(bytes)
    }
    override suspend fun repairControl(): MobileControlRepair { requireAccess(); return channel().repair() }
    private fun requireAccess() {
        if (!permits()) { close(); throw CancellationException("Mac access changed") }
    }
    private fun active() = synchronized(lock) { check(!closed); checkNotNull(session) { "Irx connection not admitted" } }
    private fun channel() = synchronized(lock) { check(!closed); checkNotNull(control) { "Irx connection not admitted" } }
    private fun IrxDuplexLane.asControlLane() = object : MobileControlLane {
        override suspend fun read() = this@asControlLane.read(64 * 1024)
        override suspend fun write(bytes: ByteArray) = this@asControlLane.write(bytes)
        override suspend fun retire() = this@asControlLane.retire()
        override fun close() = this@asControlLane.close()
    }
    override fun close() {
        val old = synchronized(lock) {
            closed = true
            Triple(session, dial, control).also { session = null; dial = null; control = null }
        }
        old.second?.cancel(CancellationException("Irx connection closed"))
        old.third?.close()
        old.first?.close()
    }
}
