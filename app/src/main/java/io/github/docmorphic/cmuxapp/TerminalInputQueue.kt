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
    data class Entry(val text: String, val paste: Boolean = false, internal val action: Action? = null) {
        val bytes = if (action != null) 1 else text.toByteArray(Charsets.UTF_8).size
    }
    class Action internal constructor(val run: suspend () -> Unit, private val release: () -> Unit) : AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }
    data class Status(val pendingBytes: Int = 0, val error: String? = null, val closed: Boolean = false)
    private val state = MutableStateFlow(Status())
    val status = state.asStateFlow()
    private val entries = ArrayDeque<Entry>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var inFlight = false
    private var pendingActions = 0
    private val worker = scope.launch {
        for (signal in wake) {
            while (true) {
                val entry = synchronized(this@TerminalInputQueue) {
                    if (state.value.error != null || state.value.closed) null
                    else entries.removeFirstOrNull()?.also { inFlight = true }
                } ?: break
                try {
                    if (entry.action != null) entry.action.run() else deliver(entry)
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
                } finally {
                    entry.action?.close()
                    if (entry.action != null) synchronized(this@TerminalInputQueue) { pendingActions-- }
                }
            }
        }
    }

    init {
        worker.invokeOnCompletion {
            synchronized(this) {
                discardQueued()
                state.value = state.value.copy(pendingBytes = 0, closed = true)
                wake.close()
            }
        }
    }

    @Synchronized fun offer(text: String, paste: Boolean = false): Boolean {
        if (text.isEmpty()) return true
        return enqueue(Entry(text, paste))
    }

    /** Reserve ordering before asynchronous image decoding. Owns release even when rejected. */
    @Synchronized fun offerAction(release: () -> Unit, run: suspend () -> Unit): Boolean {
        val action = Action(run, release)
        if (pendingActions >= MAX_PENDING_ACTIONS) { action.close(); return false }
        pendingActions++
        if (enqueue(Entry("", action = action))) return true
        pendingActions--
        action.close()
        return false
    }

    private fun enqueue(entry: Entry): Boolean {
        if (state.value.closed || state.value.error != null) return false
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

    /** Reserve a mouse/scroll operation behind text and any asynchronous paste preparation. */
    suspend fun <T> performOrdered(operation: suspend () -> T): T {
        val answer = kotlinx.coroutines.CompletableDeferred<T>()
        val accepted = offerAction(release = {
            if (!answer.isCompleted) answer.completeExceptionally(java.io.IOException("Queued input was cancelled"))
        }) {
            try { answer.complete(operation()) }
            catch (failure: Throwable) { answer.completeExceptionally(failure); throw failure }
        }
        check(accepted) { "Typing paused. Check the terminal before resuming." }
        return answer.await()
    }

    suspend fun awaitIdle() {
        val result = status.first { it.pendingBytes == 0 || it.error != null || it.closed }
        check(!result.closed && result.error == null) { result.error ?: "Terminal connection changed" }
    }

    private fun fail(message: String) {
        discardQueued()
        state.value = state.value.copy(pendingBytes = 0, error = message)
    }

    @Synchronized override fun close() {
        discardQueued()
        state.value = state.value.copy(pendingBytes = 0, closed = true)
        worker.cancel()
        wake.close()
    }

    private fun discardQueued() {
        entries.forEach { entry -> entry.action?.let { it.close(); pendingActions-- } }
        entries.clear()
    }

    companion object { const val MAX_PENDING_BYTES = 64 * 1024; const val MAX_PENDING_ACTIONS = 4 }
}
