package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** A mutation/event watermark is not proof that we have fetched the complete list. */
internal class NativeFeedRevision {
    var snapshot: Long = -1
        private set
    private var known: Long = -1

    @Synchronized fun observe(revision: Long): Boolean {
        if (revision <= maxOf(snapshot, known)) return false
        known = revision
        return true
    }
    @Synchronized fun required(): Long = maxOf(snapshot, known)
    @Synchronized fun needsRefresh(): Boolean = known > snapshot
    @Synchronized fun accept(revision: Long, required: Long = required()): Boolean {
        if (revision < maxOf(snapshot, required)) return false
        snapshot = revision
        known = maxOf(known, revision)
        return true
    }
    @Synchronized fun acknowledge(revision: Long): Boolean {
        if (revision < snapshot) return false
        known = maxOf(known, revision)
        return true
    }
}

/** Two immediate attempts, then one delayed batch per new demand; stale hosts cannot busy-loop. */
internal class NativeFeedRefresh(private val retryDelayMillis: Long = 1_000) {
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val generation = AtomicLong()
    fun request() { generation.incrementAndGet(); signal.trySend(Unit) }
    fun close() { signal.close() }
    suspend fun awaitRequest(timeoutMillis: Long) = withTimeoutOrNull(timeoutMillis) { signal.receive() }

    suspend fun run(fetch: suspend () -> Boolean) {
        while (currentCoroutineContext().isActive) {
            var attempts = 0
            var delayedGeneration = -1L
            do {
                signal.tryReceive()
                val fresh = fetch()
                val pending = signal.tryReceive().isSuccess || !fresh
                if (!pending) break
                if (++attempts >= 2) {
                    if (delayedGeneration == generation.get()) break
                    delay(retryDelayMillis)
                    delayedGeneration = generation.get()
                    attempts = 0
                }
            } while (currentCoroutineContext().isActive)
            awaitRequest(30_000)
        }
    }
}
