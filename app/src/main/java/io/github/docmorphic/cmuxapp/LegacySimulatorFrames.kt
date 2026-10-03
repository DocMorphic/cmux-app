package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*

/** One active decode, one replaceable pending absolute image. Methods run on the owner dispatcher. */
internal class LegacySimulatorFrames<T>(private val scope: CoroutineScope,
    private val decode: suspend (LegacySimulatorFrame) -> T?,
    private val discard: (T) -> Unit,
    private val presented: (LegacySimulatorFrame, T) -> Unit,
    private val stalled: () -> Unit) {
    private var active: Job? = null
    private var pending: LegacySimulatorFrame? = null
    private var received: ULong? = null
    private var generation = 0L
    private var failed = 0
    private var closed = false

    fun submit(frame: LegacySimulatorFrame, allowDuplicate: Boolean) {
        if (closed) return
        val previous = received
        if (previous != null && (frame.sequence < previous || (frame.sequence == previous && !allowDuplicate))) return
        received = frame.sequence; pending = frame
        start()
    }

    private fun start() {
        if (active != null || closed) return
        val frame = pending ?: return
        pending = null
        val epoch = generation
        val published = CompletableDeferred<Unit>()
        active = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                published.await()
                // An image decoder may be non-interruptible. Always recover its
                // result so cancelled generations can dispose it without publishing.
                val image = withContext(NonCancellable) { try { decode(frame) } catch (_: Exception) { null } }
                if (closed || epoch != generation || !isActive) { if (image != null) discard(image) }
                else if (image != null) {
                    failed = 0; presented(frame, image)
                } else {
                    failed++
                    if (failed == 3) { failed = 0; stalled() }
                }
            } finally { active = null; if (!closed && scope.isActive) start() }
        }
        published.complete(Unit)
    }

    fun close() {
        if (closed) return
        closed = true; generation++; pending = null; active?.cancel()
    }
    suspend fun awaitClosed() { close(); active?.join() }
}
