package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean

internal interface TerminalOutputLane : TerminalInputLane {
    suspend fun receive(): TerminalLaneProtocol.Output?
}

/** A duplex terminal stream. Readiness belongs to the consumer after accepting its replay. */
internal class IrxTerminalOutputLane(private val wire: TerminalLaneWire, private val cursor: ULong?, surfaceId: String? = null) : TerminalOutputLane {
    private val ended = AtomicBoolean()
    private val state = MutableStateFlow(false)
    override val closed = state.asStateFlow()
    private val reads = Mutex()
    private val writes = Mutex()
    private val surface = surfaceId?.let { runCatching { java.util.UUID.fromString(it).takeIf { id -> id.toString().equals(it, true) } }.getOrNull() }
    override val supportsIdentifiedInput get() = surface != null
    private val decoder = TerminalLaneProtocol.Decoder(acceptInputAcknowledgements = surface != null)
    private val frames = ArrayDeque<TerminalLaneProtocol.Output>()
    private var first = true

    override suspend fun receive(): TerminalLaneProtocol.Output? = reads.withLock {
        try { if (first) withTimeout(5000) { nextFrame() } else nextFrame() }
        catch (failure: Throwable) { close(); throw failure }
    }

    private suspend fun nextFrame(): TerminalLaneProtocol.Output? {
        while (frames.isEmpty()) {
            if (ended.get()) return null
            val bytes = wire.read()
            currentCoroutineContext().ensureActive()
            if (bytes.isEmpty()) {
                if (decoder.partial || first) throw EOFException("Incomplete terminal replay/output")
                close(); return null
            }
            frames.addAll(decoder.feed(bytes))
        }
        return frames.removeFirst().also { frame ->
            if (first) require(frame.replay && (cursor == null || frame.sequence == cursor)) { "Terminal replay cursor mismatch" }
            else require(!frame.replay) { "Unexpected terminal replay" }
            first = false
        }
    }

    override suspend fun send(text: String) = sendFrame(TerminalLaneProtocol.input(text))

    override suspend fun sendIdentified(text: String, delivery: TerminalInputDelivery) {
        require(surface != null && delivery.surface == surface) { "Input delivery terminal mismatch" }
        sendFrame(TerminalLaneProtocol.input(text, delivery = delivery))
    }

    private suspend fun sendFrame(bytes: ByteArray) {
        writes.withLock {
            check(!ended.get() && !first) { "Terminal lane not ready" }
            try {
                withTimeout(5000) { wire.write(bytes) }
                check(!ended.get()) { "Terminal input outcome is unknown" }
            } catch (failure: Throwable) { close(); throw failure }
        }
    }

    override fun close() {
        if (!ended.compareAndSet(false, true)) return
        state.value = true
        cleanup.launch { try { runCatching { wire.retire() } } finally { wire.close() } }
    }
    companion object { private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
