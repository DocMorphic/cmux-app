package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal interface TerminalLaneWire : AutoCloseable {
    suspend fun read(): ByteArray
    suspend fun write(bytes: ByteArray)
    suspend fun retire()
}

internal interface TerminalInputLane : AutoCloseable {
    val closed: StateFlow<Boolean>
    suspend fun send(text: String)
    val supportsIdentifiedInput: Boolean get() = false
    val acknowledgements: kotlinx.coroutines.flow.Flow<TerminalInputAcknowledgement> get() = kotlinx.coroutines.flow.emptyFlow()
    suspend fun sendIdentified(text: String, delivery: TerminalInputDelivery) { error("Identified input lane unavailable") }
}

/** Ready after the host's empty replay. Uncertain writes are surfaced to the delivery owner. */
internal class IrxTerminalInputLane private constructor(private val wire: TerminalLaneWire, private val surface: java.util.UUID?) : TerminalInputLane {
    private val ended = AtomicBoolean()
    private val state = MutableStateFlow(false)
    override val closed = state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Mutex()
    private val answers = kotlinx.coroutines.channels.Channel<TerminalInputAcknowledgement>(64)
    override val acknowledgements = answers.receiveAsFlow()
    override val supportsIdentifiedInput get() = surface != null

    private fun observeClosure() {
        scope.launch {
            try {
                val decoder = TerminalLaneProtocol.Decoder(acceptInputAcknowledgements = surface != null)
                while (isActive) {
                    val bytes = wire.read()
                    if (bytes.isEmpty()) throw EOFException("Terminal input lane ended")
                    decoder.feed(bytes).forEach { frame ->
                        val ack = frame.inputAcknowledgement ?: throw IOException("Unexpected input-lane output")
                        answers.send(ack)
                    }
                }
            } catch (_: Exception) { /* Optional lane failure is reported through closed. */ }
            finally { close() }
        }.invokeOnCompletion { close() }
    }

    override suspend fun send(text: String) = sendFrame(TerminalLaneProtocol.input(text))

    override suspend fun sendIdentified(text: String, delivery: TerminalInputDelivery) {
        require(surface != null && delivery.surface == surface) { "Input delivery terminal mismatch" }
        sendFrame(TerminalLaneProtocol.input(text, delivery = delivery))
    }

    private suspend fun sendFrame(frame: ByteArray) {
        writes.withLock {
            check(!ended.get()) { "Terminal input lane closed" }
            try {
                withTimeout(5000) { wire.write(frame) }
                check(!ended.get()) { "Terminal input outcome is unknown" }
            } catch (failure: Throwable) { close(); throw failure }
        }
    }

    override fun close() {
        if (!ended.compareAndSet(false, true)) return
        state.value = true
        answers.close()
        scope.cancel()
        cleanup.launch { try { runCatching { wire.retire() } } finally { wire.close() } }
    }

    companion object {
        private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        suspend fun open(wire: TerminalLaneWire, surfaceId: String? = null): IrxTerminalInputLane {
            val surface = surfaceId?.let { runCatching { java.util.UUID.fromString(it).takeIf { id -> id.toString().equals(it, true) } }.getOrNull() }
            val lane = IrxTerminalInputLane(wire, surface)
            try {
                withTimeout(5000) {
                    val decoder = TerminalLaneProtocol.Decoder()
                    while (true) {
                        val bytes = wire.read()
                        if (bytes.isEmpty()) throw EOFException("Terminal lane closed before readiness")
                        val frames = decoder.feed(bytes)
                        if (frames.isEmpty()) continue
                        val baseline = frames.singleOrNull() ?: throw IOException("Unexpected input-lane output")
                        require(!decoder.partial && baseline.replay && baseline.bytes.isEmpty()) { "Invalid input-lane baseline" }
                        break
                    }
                }
                currentCoroutineContext().ensureActive()
                lane.observeClosure()
                return lane
            } catch (failure: Throwable) { lane.close(); throw failure }
        }
    }
}
