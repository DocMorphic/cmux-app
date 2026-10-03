package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class NativeWorkspaceEmptyRecoveryState(val busy: Boolean = false, val message: String? = null)

/** UI-owned retry lifetime, independent of whether a LazyColumn currently mounts its empty row. */
internal class NativeWorkspaceEmptyRecovery(private val scope: CoroutineScope, private val timeoutMillis: Long = 30_000) : AutoCloseable {
    private val mutable = MutableStateFlow(NativeWorkspaceEmptyRecoveryState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var closed = false
    init { require(timeoutMillis > 0) }
    fun start(refresh: suspend () -> Unit): Boolean {
        if (closed || !scope.isActive || mutable.value.busy) return false
        val attempt = ++generation
        mutable.value = NativeWorkspaceEmptyRecoveryState(busy = true)
        job = scope.launch(start = CoroutineStart.LAZY) {
            try { withTimeout(timeoutMillis) { refresh() } }
            catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (generation == attempt) mutable.value = NativeWorkspaceEmptyRecoveryState(message = TIMEOUT)
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) {
                if (generation == attempt) mutable.value = NativeWorkspaceEmptyRecoveryState(message = FAILURE)
            } finally {
                if (generation == attempt) { mutable.value = mutable.value.copy(busy = false); job = null }
            }
        }.also { it.start() }
        return true
    }
    fun cancel() { generation++; job?.cancel(); job = null; mutable.value = NativeWorkspaceEmptyRecoveryState() }
    override fun close() { closed = true; cancel() }
    companion object {
        const val TIMEOUT = "The connection is taking longer than expected. Try again or check the setup guide."
        const val FAILURE = "Could not refresh workspaces. Check the connection and try again."
    }
}
