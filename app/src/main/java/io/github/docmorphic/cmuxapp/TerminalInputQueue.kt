package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One ordered, bounded lane for keys and clipboard paste. Failed input is never replayed. */
class TerminalInputQueue(scope: CoroutineScope, private val deliver: suspend (Entry) -> Unit) : AutoCloseable {
    data class Entry(val text: String, val paste: Boolean = false) {
        val bytes = text.toByteArray(Charsets.UTF_8).size
    }
    data class Status(val pendingBytes: Int = 0, val error: String? = null, val closed: Boolean = false)
    private val state = MutableStateFlow(Status())
    val status = state.asStateFlow()
    private val entries = ArrayDeque<Entry>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var inFlight = false
    private val worker = scope.launch {
        for (signal in wake) {
            while (true) {
                val entry = synchronized(this@TerminalInputQueue) {
                    if (state.value.error != null || state.value.closed) null
                    else entries.removeFirstOrNull()?.also { inFlight = true }
                } ?: break
                try {
                    deliver(entry)
                    synchronized(this@TerminalInputQueue) {
                        inFlight = false
                        state.value = state.value.copy(pendingBytes = (state.value.pendingBytes - entry.bytes).coerceAtLeast(0))
                    }
                } catch (failure: Exception) {
                    synchronized(this@TerminalInputQueue) {
                        inFlight = false
                        fail("Typing paused. Delivery was not confirmed. Check the terminal before resuming.")
                    }
                    if (failure is CancellationException && !currentCoroutineContext().isActive) throw failure
                    break
                }
            }
        }
    }

    @Synchronized fun offer(text: String, paste: Boolean = false): Boolean {
        if (text.isEmpty()) return true
        if (state.value.closed || state.value.error != null) return false
        val entry = Entry(text, paste)
        if (entry.bytes > MAX_PENDING_BYTES - state.value.pendingBytes) {
            fail("Typing paused because the connection could not keep up. Check the terminal before resuming.")
            return false
        }
        entries.addLast(entry)
        state.value = state.value.copy(pendingBytes = state.value.pendingBytes + entry.bytes)
        wake.trySend(Unit)
        return true
    }

    /** Resuming discards uncertain input; the user decides what to type again. */
    @Synchronized fun resume(): Boolean {
        if (inFlight || state.value.closed) return false
        state.value = Status()
        return true
    }

    suspend fun awaitIdle() {
        val result = status.first { it.pendingBytes == 0 || it.error != null || it.closed }
        check(!result.closed && result.error == null) { result.error ?: "Terminal connection changed" }
    }

    private fun fail(message: String) {
        entries.clear()
        state.value = state.value.copy(pendingBytes = 0, error = message)
    }

    @Synchronized override fun close() {
        entries.clear()
        state.value = state.value.copy(pendingBytes = 0, closed = true)
        worker.cancel()
        wake.close()
    }

    companion object { const val MAX_PENDING_BYTES = 64 * 1024 }
}
