package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** A host viewport_transition is readiness, not a failed terminal connection.
 * Like the iOS replay barrier, wait for a full grid or a three-second watchdog,
 * with at most two retries. This helper is exclusively for replay, never input.
 */
internal class TerminalReplayRecovery(private val watchdogMillis: Long = 3_000) {
    private val geometryReady = Channel<Unit>(Channel.CONFLATED)

    fun onFullGrid() { geometryReady.trySend(Unit) }

    suspend fun <T> replay(request: suspend () -> T): T {
        for (attempt in 0..2) {
            currentCoroutineContext().ensureActive()
            while (geometryReady.tryReceive().isSuccess) { /* discard signals from before this request */ }
            try { return request() }
            catch (failure: MobileRpcException) {
                if (failure.code != "viewport_transition" || attempt == 2) throw failure
                withTimeoutOrNull(watchdogMillis) { geometryReady.receive() }
            }
        }
        error("Unreachable replay retry state")
    }
}
