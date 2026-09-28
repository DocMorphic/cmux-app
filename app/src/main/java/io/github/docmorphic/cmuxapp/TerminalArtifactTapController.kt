package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*

/** Main-thread owner of one terminal's pending path classification and tap generation. */
internal class TerminalArtifactTapController(private val scope: CoroutineScope, private val deadlineMillis: Long = 2_000) : AutoCloseable {
    private var generation = 0L
    private var pending: Job? = null
    private var classification: Job? = null
    private var closed = false

    fun invalidate() { generation++; pending?.cancel(); classification?.cancel(); pending = null; classification = null }

    fun tap(path: String, foldersEnabled: Boolean, stat: suspend (String) -> ArtifactKind,
        stillMatches: () -> Boolean, open: () -> Unit, focus: (sendClick: Boolean) -> Unit): Job? {
        if (closed) return null
        invalidate()
        if (foldersEnabled) { if (stillMatches()) open(); return null }
        val captured = generation
        // A sibling job lets the two-second deadline win even if a transport ignores cancellation.
        val decision = scope.async {
            try { stat(path) != ArtifactKind.DIRECTORY }
            catch (error: CancellationException) { currentCoroutineContext().ensureActive(); false }
            catch (error: Exception) { error is MobileRpcException && error.code?.trim()?.lowercase() == "forbidden" }
        }
        classification = decision
        pending = scope.launch {
            val shouldOpen = try { withTimeoutOrNull(deadlineMillis) { decision.await() } ?: false }
                finally { decision.cancel() }
            ensureActive()
            if (closed || captured != generation) return@launch
            val samePath = stillMatches()
            if (shouldOpen) { if (samePath) open() } else focus(samePath)
        }
        return pending
    }

    override fun close() { closed = true; invalidate() }
}
