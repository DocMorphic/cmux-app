package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** An RPC deadline is reportable; disposing the panel must still cancel all work. */
internal suspend fun rethrowBrowserCancellation(failure: Exception) {
    if (failure is CancellationException) {
        currentCoroutineContext().ensureActive()
        if (failure !is TimeoutCancellationException) throw failure
    }
}

/** The iOS unanswered-input policy. A quiet page, or a state event, is not frame evidence. */
internal class BrowserRecoveryPolicy(val inputSilenceMillis: Long = 2_500, val restartBackoffMillis: Long = 4_000) {
    private var lastInput: Long? = null
    private var lastFrame: Long? = null
    private var lastRestart: Long? = null
    private var eventOrder = 0L
    private var inputOrder = 0L
    private var frameOrder = 0L
    init { require(inputSilenceMillis > 0 && restartBackoffMillis > 0) }
    fun noteInput(now: Long) { lastInput = now; inputOrder = ++eventOrder }
    fun noteFrame(now: Long) { lastFrame = now; frameOrder = ++eventOrder }
    fun noteRestart(now: Long) { lastRestart = now }
    fun reset() { lastInput = null; lastFrame = null; lastRestart = null; eventOrder = 0; inputOrder = 0; frameOrder = 0 }
    fun shouldRestart(now: Long): Boolean {
        val input = lastInput ?: return false
        if (now - input < inputSilenceMillis) return false
        // Android's millisecond clock can give adjacent input/frame events the
        // same timestamp. Preserve their order instead of crediting an old frame.
        if (lastFrame?.let { it >= input } == true && frameOrder > inputOrder) return false
        return lastRestart?.let { now - it >= restartBackoffMillis } ?: true
    }
}

internal interface BrowserRecoveryClock {
    fun nowMillis(): Long
    suspend fun sleep(millis: Long)
}

internal object MonotonicBrowserRecoveryClock : BrowserRecoveryClock {
    override fun nowMillis() = System.nanoTime() / 1_000_000
    override suspend fun sleep(millis: Long) { delay(millis) }
}

/** Owned by one panel's UI scope. Late frames/timers cannot re-arm a newer subscription. */
internal class BrowserStreamRecovery(
    private val scope: CoroutineScope,
    private val clock: BrowserRecoveryClock = MonotonicBrowserRecoveryClock,
    private val policy: BrowserRecoveryPolicy = BrowserRecoveryPolicy(),
    private val restart: () -> Unit
) : AutoCloseable {
    private var timer: Job? = null
    private var active = false
    private var closed = false
    private var generation = 0L
    fun started(): Long {
        check(!closed)
        stopped(); active = true
        return generation
    }
    fun stopped() {
        generation++; active = false
        timer?.cancel(); timer = null; policy.reset()
    }
    fun noteInput() {
        if (!active || closed) return
        policy.noteInput(clock.nowMillis())
        timer?.cancel()
        val epoch = generation
        timer = scope.launch {
            clock.sleep(policy.inputSilenceMillis + 50)
            if (active && !closed && epoch == generation && policy.shouldRestart(clock.nowMillis())) {
                policy.noteRestart(clock.nowMillis())
                restart()
            }
        }
    }
    fun noteDisplayedFrame(epoch: Long) {
        if (!active || closed || epoch != generation) return
        policy.noteFrame(clock.nowMillis()); timer?.cancel(); timer = null
    }
    override fun close() { stopped(); closed = true }
}
