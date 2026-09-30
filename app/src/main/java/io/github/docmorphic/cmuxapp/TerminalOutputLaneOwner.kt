package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI-dispatcher owner. Replay barriers stop reads until a new authoritative baseline exists. */
internal class TerminalOutputLaneOwner(
    private val scope: CoroutineScope,
    private val cursor: () -> ULong?,
    private val useLane: suspend (ULong, suspend (TerminalOutputLane) -> Unit) -> Boolean,
    private val consume: (TerminalLaneProtocol.Output) -> TerminalStreamMirror.Result,
    private val resync: () -> Unit,
    private val onAcknowledgement: ((TerminalInputAcknowledgement) -> Unit)? = null
) : AutoCloseable {
    private var generation = 0L
    private var closed = false
    private var worker: Job? = null
    private var lane: TerminalOutputLane? = null
    private val readiness = MutableStateFlow(false)
    val ready = readiness.asStateFlow()

    fun resume() {
        if (closed || worker?.isActive == true || cursor() == null) return
        val run = ++generation
        worker = scope.launch(start = CoroutineStart.LAZY) {
            repeat(3) { attempt ->
                try {
                    val start = cursor() ?: return@launch
                    val supported = useLane(start) { candidate ->
                        try {
                            currentCoroutineContext().ensureActive()
                            if (generation != run || closed) return@useLane
                            lane = candidate
                            while (true) {
                                val frame = candidate.receive() ?: break
                                currentCoroutineContext().ensureActive()
                                if (generation != run || closed) return@useLane
                                if (frame.inputAcknowledgement != null) {
                                    checkNotNull(onAcknowledgement) { "Unexpected input acknowledgement" }(frame.inputAcknowledgement)
                                    continue
                                }
                                if (consume(frame) == TerminalStreamMirror.Result.REPLAY) {
                                    pause(); resync(); return@useLane
                                }
                                readiness.value = true
                            }
                        } finally {
                            if (lane === candidate) { lane = null; readiness.value = false }
                        }
                    }
                    if (!supported) return@launch
                } catch (failure: Exception) { currentCoroutineContext().ensureActive() }
                if (generation != run || closed) return@launch
                if (attempt < 2) delay(250L * (attempt + 1))
            }
            // Keep event/RPC fallback after bounded attempts; a later authoritative
            // replay can restart this owner with a fresh cursor.
        }
        worker?.start()
    }

    fun pause() {
        generation++; readiness.value = false
        lane?.close(); lane = null
        worker?.cancel(); worker = null
    }

    suspend fun send(text: String): Boolean = send(text, null)

    suspend fun sendIdentified(text: String, delivery: TerminalInputDelivery): Boolean = send(text, delivery)

    private suspend fun send(text: String, delivery: TerminalInputDelivery?): Boolean {
        check(!closed)
        val active = lane?.takeIf { readiness.value && !it.closed.value } ?: return false
        if (text.toByteArray(Charsets.UTF_8).size !in 1..TerminalLaneProtocol.MAX_INPUT) return false
        if (delivery != null && (!active.supportsIdentifiedInput || onAcknowledgement == null)) return false
        try {
            if (delivery == null) active.send(text) else active.sendIdentified(text, delivery)
            return true
        }
        catch (failure: Throwable) { pause(); throw failure }
    }

    override fun close() { if (!closed) { closed = true; pause() } }
}
