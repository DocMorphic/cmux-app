package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class CloudTunnelPhase { IDLE, STARTING, READY, FAILED, CLOSED }
internal data class CloudTunnelState(val phase: CloudTunnelPhase, val failure: CloudSessionFailure? = null, val generation: Long = 0)

/** Account-owned foreground lease with bounded startup and explicit retry.
 * create runs on the native dispatcher. Its final handle-producing call must be
 * synchronous: never return a new native handle through cancellable withContext.
 * retire fences input synchronously; close may block and always runs on a worker.
 */
internal class CloudTunnelController<T : AutoCloseable>(
    parent: CoroutineScope, private val isCurrent: () -> Boolean,
    private val create: suspend () -> T, private val retire: (T) -> Unit = {},
    private val nativeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = 30_000
) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val lock = Any()
    private val mutable = MutableStateFlow(CloudTunnelState(CloudTunnelPhase.IDLE))
    val state = mutable.asStateFlow()
    private var wanted = false
    private var closed = false
    private var generation = 0L
    private var startup: Job? = null
    private var timer: Job? = null
    private var live: T? = null

    init {
        require(timeoutMillis in 1..300_000)
        job.invokeOnCompletion { close() }
    }
    private fun admitted(attempt: Long) = !closed && wanted && job.isActive && isCurrent() && generation == attempt
    fun resource(): T? = synchronized(lock) { live?.takeIf { admitted(generation) } }
    fun setWanted(value: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        if (!job.isActive || !isCurrent()) { close(); return@synchronized }
        if (wanted == value) return@synchronized
        wanted = value
        if (value) {
            if (mutable.value.phase == CloudTunnelPhase.IDLE) startLocked()
        } else stopLocked()
    }
    fun retry() = synchronized(lock) {
        if (closed || !job.isActive || !isCurrent() || mutable.value.phase != CloudTunnelPhase.FAILED) return@synchronized
        mutable.value = CloudTunnelState(CloudTunnelPhase.IDLE, generation = generation)
        if (wanted) startLocked()
    }
    private fun startLocked() {
        val attempt = ++generation
        mutable.value = CloudTunnelState(CloudTunnelPhase.STARTING, generation = attempt)
        // Independent from the account job so native startup cannot hold account
        // cancellation open; cancellation still reaches its suspending HTTP stage.
        val worker = CoroutineScope(nativeDispatcher).launch(start = CoroutineStart.LAZY) {
            var created: T? = null
            var retained = false
            try {
                synchronized(lock) { if (!admitted(attempt)) throw CancellationException("Cloud tunnel owner changed") }
                created = create()
                synchronized(lock) {
                    if (!isActive || !admitted(attempt)) throw CancellationException("Cloud tunnel owner changed")
                    live = created; retained = true
                    timer?.cancel(); timer = null; startup = null
                    mutable.value = CloudTunnelState(CloudTunnelPhase.READY, generation = attempt)
                }
            } catch (failure: Exception) { fail(attempt, failure) }
            catch (_: LinkageError) { fail(attempt, IllegalStateException("Cloud native runtime is unavailable")) }
            finally { if (!retained) created?.close() }
        }
        startup = worker
        timer = scope.launch {
            delay(timeoutMillis)
            fail(attempt, IllegalStateException("Cloud tunnel startup timed out"))
        }
        worker.start()
    }
    private fun fail(attempt: Long, failure: Exception) = synchronized(lock) {
        if (generation != attempt || closed || mutable.value.phase != CloudTunnelPhase.STARTING) return@synchronized
        stopLocked()
        // Owner/visibility cancellation advances the generation before cancelling
        // the worker. A cancellation still belonging to this attempt is a failed
        // startup and must offer retry rather than strand a wanted lease in IDLE.
        val reason = if (failure is CancellationException)
            IllegalStateException("Cloud tunnel startup was interrupted") else failure
        mutable.value = CloudTunnelState(CloudTunnelPhase.FAILED, CloudSessionFailure.classify(reason, CloudFailureKind.TUNNEL), generation)
    }
    private fun stopLocked() {
        generation++
        startup?.cancel(); startup = null
        timer?.cancel(); timer = null
        live?.let { owned ->
            retire(owned)
            CoroutineScope(nativeDispatcher).launch { owned.close() }
        }
        live = null
        if (mutable.value.phase != CloudTunnelPhase.FAILED) mutable.value = CloudTunnelState(CloudTunnelPhase.IDLE, generation = generation)
    }
    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true; wanted = false; stopLocked()
        mutable.value = CloudTunnelState(CloudTunnelPhase.CLOSED, generation = generation)
        job.cancel()
    }
}
