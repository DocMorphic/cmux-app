package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
}

/** Ready only after the host's empty replay. A failed write must never fall back to RPC. */
internal class IrxTerminalInputLane private constructor(private val wire: TerminalLaneWire) : TerminalInputLane {
    private val ended = AtomicBoolean()
    private val state = MutableStateFlow(false)
    override val closed = state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Mutex()

    private fun observeClosure() {
        scope.launch {
            try {
                // Input-only output consists solely of the readiness baseline. EOF/reset
                // or unexpected further bytes invalidate this lane, not the peer session.
                wire.read()
            } catch (_: Exception) { /* Optional lane failure is reported through closed. */ }
            finally { close() }
        }.invokeOnCompletion { close() }
    }

    override suspend fun send(text: String) {
        val frame = TerminalLaneProtocol.input(text)
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
        scope.cancel()
        cleanup.launch { try { runCatching { wire.retire() } } finally { wire.close() } }
    }

    companion object {
        private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        suspend fun open(wire: TerminalLaneWire): IrxTerminalInputLane {
            val lane = IrxTerminalInputLane(wire)
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
