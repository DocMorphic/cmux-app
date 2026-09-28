package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** One mounted terminal on one RPC lease. Only unsent input may fall back to RPC. */
internal class TerminalInputLaneOwner(parent: CoroutineScope,
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
                        candidate.closed.first { it }
                    } finally {
                        synchronized(lock) { if (lane === candidate) { lane = null; readiness.value = false } }
                    }
                }
                if (!supported) return@launch
            } catch (failure: Exception) { currentCoroutineContext().ensureActive() }
            if (attempt < 2) delay(250L * (attempt + 1))
        }
    }

    suspend fun send(text: String): Boolean {
        // Large operations retain the existing bounded RPC path, before any native write.
        if (text.toByteArray(Charsets.UTF_8).size !in 1..TerminalLaneProtocol.MAX_INPUT) return false
        val active = synchronized(lock) { check(!closed); lane?.takeUnless { it.closed.value } } ?: return false
        try { active.send(text); return true }
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
