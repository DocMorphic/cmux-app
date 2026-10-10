package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

internal data class NativeAnalyticsConsentSnapshot(val enabled: Boolean, val generation: Long)

/** An event captured before revocation stays retired even after re-enabling.
 * A future uploader must register its job before starting IO and check allows()
 * again after suspension points. Coroutine cancellation alone cannot retract an
 * already accepted request or cancel an unbound blocking HTTP operation.
 */
internal class NativeAnalyticsConsentGate(initiallyEnabled: Boolean,
    private val publish: (NativeAnalyticsConsentSnapshot) -> Unit = {}) : AutoCloseable {
    private val lock = Any()
    private var enabled = initiallyEnabled
    private var generation = 0L
    private var closed = false
    private val uploads = mutableSetOf<Job>()

    fun snapshot(): NativeAnalyticsConsentSnapshot = synchronized(lock) {
        NativeAnalyticsConsentSnapshot(enabled, generation)
    }

    /** The base snapshot precedes the provider read. An intervening change
     * makes that observation stale instead of restoring an old opt-in.
     */
    fun synchronize(observedEnabled: Boolean, basedOn: NativeAnalyticsConsentSnapshot): NativeAnalyticsConsentSnapshot {
        var cancelled = emptyList<Job>()
        return try { synchronized(lock) {
            if (closed || generation != basedOn.generation) return@synchronized basedOn
            if (enabled != observedEnabled) {
                enabled = observedEnabled; generation++
                if (!enabled) { cancelled = uploads.toList(); uploads.clear() }
                // Publish runtime and UI consent in the same critical section.
                val updated = NativeAnalyticsConsentSnapshot(enabled, generation)
                publish(updated)
            }
            NativeAnalyticsConsentSnapshot(enabled, generation)
        } } finally {
            cancelled.forEach { it.cancel(CancellationException("Analytics consent revoked")) }
        }
    }

    fun allows(snapshot: NativeAnalyticsConsentSnapshot): Boolean = synchronized(lock) {
        !closed && enabled && snapshot.enabled && generation == snapshot.generation
    }

    /** Lazy jobs may be admitted before starting; rejected jobs cannot start later. */
    fun register(job: Job, snapshot: NativeAnalyticsConsentSnapshot): Boolean {
        val admitted = synchronized(lock) {
            if (closed || !enabled || !snapshot.enabled || generation != snapshot.generation || job.isCompleted || job.isCancelled) false
            else { uploads.add(job); true }
        }
        if (!admitted) job.cancel(CancellationException("Analytics consent unavailable"))
        else job.invokeOnCompletion { synchronized(lock) { uploads.remove(job) } }
        return admitted
    }

    override fun close() {
        var cancelled = emptyList<Job>()
        try { synchronized(lock) {
            if (closed) return
            closed = true; enabled = false; generation++
            cancelled = uploads.toList()
            uploads.clear()
            publish(NativeAnalyticsConsentSnapshot(false, generation))
        } } finally {
            cancelled.forEach { it.cancel(CancellationException("Analytics consent owner closed")) }
        }
    }
}
