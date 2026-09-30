package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.iroh.IrxClientSession
import io.github.docmorphic.cmuxapp.iroh.IrxDuplexLane
import io.github.docmorphic.cmuxapp.iroh.IrxWire
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface MobileEventLane : AutoCloseable {
    val resource: String?
    suspend fun read(): ByteArray
    suspend fun stop(errorCode: ULong = 0uL)
}

/** Each native stream has its own decoder; only complete frames enter the common event queue. */
internal class IrxEventMultiplexer(private val accept: suspend () -> MobileEventLane,
                                  private val permits: () -> Boolean) {
    private class Reader(val lane: MobileEventLane, var job: Job? = null)

    val frames: Flow<ByteArray> = channelFlow {
        val lanes = mutableSetOf<Reader>()
        fun allowed() { if (!permits()) throw CancellationException("Mac access changed") }
        suspend fun retire(lane: MobileEventLane, code: ULong) = withContext(NonCancellable) {
            try { runCatching { withTimeout(2000) { lane.stop(code) } } }
            finally { lane.close() }
        }
        try {
            while (true) {
                allowed()
                val lane = accept()
                val reader = Reader(lane)
                val admitted = synchronized(lanes) {
                    // Match the iOS hub's 32 surface readers, bounded by native uni credit.
                    // Replacement streams coexist until their own EOF/reset; complete old
                    // frames remain valid and each unfinished frame stays with its reader.
                    if (lanes.size >= 40 || (lane.resource != null && lanes.count { it.lane.resource != null } >= 32)) false
                    else { lanes.add(reader); true }
                }
                if (!admitted) { retire(lane, 3uL); continue }
                reader.job = launch(start = CoroutineStart.UNDISPATCHED) {
                    var stopCode = 0uL
                    try {
                        val decoder = MobileFrameDecoder()
                        while (true) {
                            val bytes = lane.read()
                            if (bytes.isEmpty()) break
                            allowed()
                            val frames = try { decoder.feed(bytes) }
                            catch (failure: IllegalArgumentException) { stopCode = 5uL; throw failure }
                            for (frame in frames) send(frame)
                        }
                    } catch (error: Exception) {
                        // A reset/invalid optional lane loses only its own unfinished frame.
                        currentCoroutineContext().ensureActive()
                        allowed()
                    } finally {
                        retire(lane, stopCode)
                        synchronized(lanes) { lanes.remove(reader) }
                    }
                }
            }
        } finally {
            val remaining = synchronized(lanes) { lanes.toList().also { lanes.clear() } }
            remaining.forEach { it.job?.cancel() }
            // Reader finally blocks own stream cleanup, including cancellation during acceptance.
            withContext(NonCancellable) {
                remaining.forEach { reader ->
                    reader.job?.join()
                    if (reader.job == null) retire(reader.lane, 0uL)
                }
            }
        }
    }.buffer(8)
}

/** Retires this admitted QUIC session on close; the account runtime owns the shared endpoint. */
internal class IrxMobileRpcTransport(
    private val establish: suspend () -> IrxClientSession,
    private val permits: () -> Boolean,
    private val applicationActive: StateFlow<IrxProbeActivity> = MutableStateFlow(IrxProbeActivity(true))
) : MobileRpcTransport {
    private val lock = Any()
    private val connecting = Mutex()
    private var session: IrxClientSession? = null
    private var control: IrxControlChannel? = null
    private var keepalive: IrxKeepalive? = null
    private var dial: Job? = null
    private var closed = false
    private data class Closing(val session: IrxClientSession?, val dial: Job?,
                               val control: IrxControlChannel?, val probes: IrxKeepalive?)
    override val surfaceEventLanes = true
    override fun diagnostics(): MobileTransportDiagnostics {
        requireAccess()
        return MobileTransportDiagnostics.fromIroh(active().diagnostics()).also { requireAccess() }
    }
    override suspend fun openTerminalInput(surfaceId: String): TerminalInputLane =
        IrxTerminalInputLane.open(openTerminalWire(surfaceId, IrxWire.Lane.TERMINAL_INPUT, null), surfaceId)

    override suspend fun openTerminalOutput(surfaceId: String, cursor: ULong?): TerminalOutputLane =
        IrxTerminalOutputLane(openTerminalWire(surfaceId, IrxWire.Lane.TERMINAL, cursor), cursor, surfaceId)

    private suspend fun openTerminalWire(surfaceId: String, kind: IrxWire.Lane, cursor: ULong?): TerminalLaneWire {
        val surface = java.util.UUID.fromString(surfaceId).toString()
        require(surface.equals(surfaceId, ignoreCase = true)) { "Invalid terminal surface" }
        val lane = openFeatureLane(IrxWire.Descriptor(kind, "terminal:$surface", cursor))
        return object : TerminalLaneWire {
            override suspend fun read(): ByteArray { requireAccess(); return lane.read(64 * 1024) }
            override suspend fun write(bytes: ByteArray) { requireAccess(); lane.write(bytes) }
            override suspend fun retire() = lane.retire(0uL)
            override fun close() = lane.close()
        }
    }

    override val supportsArtifactLanes = true
    override val supportsSimulatorLanes = true
    override suspend fun openSimulator(panelId: String): SimStreamLane {
        val descriptor = simulatorLaneDescriptor(panelId)
        val lane = openFeatureLane(descriptor)
        return IrxSimulatorLane(lane) { requireAccess(); active(); Unit }
    }

    override suspend fun openArtifact(resource: String): ArtifactLane {
        require(resource.isNotBlank() && resource.length <= 8192 && '\u0000' !in resource)
        return IrxArtifactLane(openFeatureLane(IrxWire.Descriptor(IrxWire.Lane.ARTIFACT, resource, offset = 0uL)), ::requireAccess)
    }

    private suspend fun openFeatureLane(descriptor: IrxWire.Descriptor): IrxDuplexLane {
        requireAccess()
        var candidate: IrxDuplexLane? = null
        val lane = try {
            withTimeout(5000) {
                active().openLane(descriptor).also { candidate = it }
            }
        } catch (failure: Throwable) {
            candidate?.let { abandoned ->
                withContext(NonCancellable) { runCatching { abandoned.retire(0uL) } }
            }
            throw failure
        }
        return lane
    }
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
            override suspend fun stop(errorCode: ULong) = lane.stop(errorCode)
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
                    val probes = IrxKeepalive(applicationActive, open = {
                        val native = admitted.openLane(IrxWire.Descriptor(IrxWire.Lane.KEEPALIVE))
                        object : IrxProbeLane {
                            override suspend fun write(value: JSONObject) = native.writeFrame(value)
                            override suspend fun read() = native.readFrame()
                            override suspend fun retire() = native.retire(0uL)
                            override fun close() = native.close()
                        }
                    }, permits = { !synchronized(lock) { closed } && permits() },
                        lastInboundNanos = { admitted.lastInboundNanos },
                        intervalMillis = admitted.admission.keepaliveIntervalMs.coerceIn(1000, 60_000),
                        deadlineMillis = admitted.admission.keepaliveDeadlineMs.coerceIn(250, 30_000))
                    keepalive = probes
                    control = IrxControlChannel(admitted.control.asControlLane(),
                        replacement = { admitted.openControlReplacement().asControlLane() },
                        permits = { !synchronized(lock) { closed } && permits() },
                        connectionClosed = admitted::connectionIsClosed, positiveSilence = probes::positiveSilenceSince)
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
    override suspend fun repairControl(silentSinceNanos: Long): MobileControlRepair { requireAccess(); return channel().repair(silentSinceNanos) }
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
            Closing(session, dial, control, keepalive).also { session = null; dial = null; control = null; keepalive = null }
        }
        old.probes?.close()
        old.dial?.cancel(CancellationException("Irx connection closed"))
        old.control?.close()
        old.session?.close()
    }
}
