package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ordered page input, bounded while RPC is slow. Unknown delivery is never retried. */
internal class BrowserInputQueue(scope: CoroutineScope, private val deliver: suspend (BrowserInput) -> Unit) : AutoCloseable {
    private val pending = ArrayDeque<BrowserInput>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val failure = MutableStateFlow<String?>(null)
    val error = failure.asStateFlow()
    private var closed = false
    private var inFlight = false
    private var pendingBytes = 0
    private fun bytes(input: BrowserInput) = input.parameters("").toString().toByteArray(Charsets.UTF_8).size
    private fun fail(message: String) { pending.clear(); pendingBytes = 0; failure.value = message }
    private val worker = scope.launch {
        for (signal in wake) while (true) {
            val next = synchronized(this@BrowserInputQueue) {
                if (closed || failure.value != null) null else pending.removeFirstOrNull()?.also { inFlight = true }
            } ?: break
            try {
                deliver(next)
                synchronized(this@BrowserInputQueue) { inFlight = false; pendingBytes = (pendingBytes - bytes(next)).coerceAtLeast(0) }
            } catch (error: Exception) {
                synchronized(this@BrowserInputQueue) {
                    inFlight = false
                    fail("Browser input paused. Delivery was not confirmed. Check the page before resuming.")
                }
                if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                break
            }
        }
    }
    @Synchronized fun offer(vararg inputs: BrowserInput): Boolean = offer(inputs.toList())
    @Synchronized fun offer(inputs: List<BrowserInput>): Boolean {
        if (closed || failure.value != null) return false
        // Check the entire paste before enqueueing any part of it.
        if (inputs.size > 512 || inputs.sumOf { bytes(it).toLong() } + pendingBytes > 64 * 1024L) {
            fail("Browser input paused because the connection could not keep up. Check the page before resuming."); return false
        }
        for (input in inputs) {
            val previous = pending.lastOrNull()
            val merged = if (previous is BrowserInput.Scroll && input is BrowserInput.Scroll) previous.merge(input) else null
            if (merged != null) { pending.removeLast(); pendingBytes -= bytes(checkNotNull(previous)); pending.addLast(merged); pendingBytes += bytes(merged) }
            else { pending.addLast(input); pendingBytes += bytes(input) }
        }
        wake.trySend(Unit)
        return true
    }
    @Synchronized fun resume(): Boolean {
        if (closed || inFlight) return false
        failure.value = null; return true
    }
    @Synchronized fun pause() { if (!closed) fail("Browser input paused. Check the page before resuming.") }
    @Synchronized override fun close() { closed = true; pending.clear(); pendingBytes = 0; worker.cancel(); wake.close() }
}
