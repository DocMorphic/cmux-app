package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** One mounted terminal on one RPC lease. False means unsent; uncertain writes throw. */
internal class TerminalInputLaneOwner(parent: CoroutineScope,
    private val onAcknowledgement: ((TerminalInputAcknowledgement) -> Unit)? = null,
    private val useLane: suspend (suspend (TerminalInputLane) -> Unit) -> Boolean
) : AutoCloseable {
    private val lock = Any()
    private var lane: TerminalInputLane? = null
    private var closed = false
    private val readiness = MutableStateFlow(false)
    val ready = readiness.asStateFlow()
    private val worker = parent.launch {
        repeat(3) { attempt ->
            try {
                val supported = useLane { candidate ->
                    try {
                        synchronized(lock) {
                            check(!closed)
                            lane = candidate; readiness.value = !candidate.closed.value
                        }
                        coroutineScope {
                            val receive = launch {
                                candidate.acknowledgements.collect { acknowledgement ->
                                    val current = synchronized(lock) { !closed && lane === candidate }
                                    if (current) checkNotNull(onAcknowledgement) { "Unexpected input acknowledgement" }(acknowledgement)
                                }
                            }
                            try { candidate.closed.first { it } } finally { receive.cancel() }
                        }
                    } finally {
                        synchronized(lock) { if (lane === candidate) { lane = null; readiness.value = false } }
                    }
                }
                if (!supported) return@launch
            } catch (failure: Exception) { currentCoroutineContext().ensureActive() }
            if (attempt < 2) delay(250L * (attempt + 1))
        }
    }

    suspend fun send(text: String): Boolean = send(text, null)

    suspend fun sendIdentified(text: String, delivery: TerminalInputDelivery): Boolean = send(text, delivery)

    private suspend fun send(text: String, delivery: TerminalInputDelivery?): Boolean {
        // Large operations retain the existing bounded RPC path, before any native write.
        if (text.toByteArray(Charsets.UTF_8).size !in 1..TerminalLaneProtocol.MAX_INPUT) return false
        val active = synchronized(lock) { check(!closed); lane?.takeUnless { it.closed.value } } ?: return false
        if (delivery != null && (!active.supportsIdentifiedInput || onAcknowledgement == null)) return false
        try {
            if (delivery == null) active.send(text) else active.sendIdentified(text, delivery)
            return true
        }
        catch (failure: Throwable) {
            synchronized(lock) { if (lane === active) { lane = null; readiness.value = false } }
            active.close()
            throw failure
        }
    }

    override fun close() {
        val old = synchronized(lock) {
            if (closed) return
            closed = true; readiness.value = false
            lane.also { lane = null }
        }
        old?.close(); worker.cancel()
    }
}
