package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class CloudLinkPhase { IDLE, CONNECTING, READY, FAILED, CLOSED }
internal data class CloudLinkState(val phase: CloudLinkPhase, val failure: CloudSessionFailure? = null)

/** Account-owned connection attempt. Native connect and approval must both succeed.
 * The machine owner must close this on retirement or when its attempt is no longer wanted.
 * Cancelling one observer's await does not orphan or steal the owner's connection.
 */
internal class CloudMachineHandshake<S : AutoCloseable>(
    parent: CoroutineScope, private val service: CloudTerminalService,
    private val machineId: String, private val fingerprint: String,
    private val isCurrent: () -> Boolean, private val connect: (CloudAttachEndpoint) -> S,
    private val nativeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val approvalAttempts: Int = 150, private val approvalTimeoutMillis: Long = 300_000,
    private val retireSession: (S) -> Unit = {}
) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val lock = Any()
    private val result = CompletableDeferred<S>()
    private val mutable = MutableStateFlow(CloudLinkState(CloudLinkPhase.IDLE))
    val state = mutable.asStateFlow()
    private var started = false
    private var stopped = false
    private var approved = false
    private var session: S? = null

    init {
        require(machineId.isNotBlank() && fingerprint.isNotBlank())
        require(approvalAttempts in 1..150 && approvalTimeoutMillis in 1..300_000)
        job.invokeOnCompletion { close() }
    }
    private fun admitted() = !stopped && job.isActive && isCurrent()
    private fun requireCurrent() = synchronized(lock) {
        if (!admitted()) throw CancellationException("Cloud connection owner changed")
    }
    fun start() {
        synchronized(lock) {
            if (started || stopped) return
            started = true
            mutable.value = CloudLinkState(CloudLinkPhase.CONNECTING)
        }
        scope.launch {
            try {
                requireCurrent()
                // No capability is advertised before its Android behavior is implemented.
                val endpoint = service.attach(machineId, fingerprint)
                requireCurrent()
                startNative(endpoint)
                if (!endpoint.trustedCarrier && endpoint.invitation != null) {
                    try {
                        withTimeout(approvalTimeoutMillis) { approveUntilGranted(endpoint.invitation.id) }
                    } catch (_: TimeoutCancellationException) {
                        error("Cloud invitation approval timed out")
                    }
                }
                synchronized(lock) {
                    if (!admitted()) throw CancellationException("Cloud connection owner changed")
                    approved = true; finishIfReady()
                }
            } catch (failure: Exception) { fail(failure) }
        }
    }
    suspend fun awaitSession(): S { start(); return result.await() }

    private fun startNative(endpoint: CloudAttachEndpoint) {
        // Deliberately not a child of the account job: a blocking C call cannot
        // cooperate with cancellation. Account close must finish promptly while
        // this bounded native call returns and disposes of any late handle.
        CoroutineScope(nativeDispatcher).launch {
            var created: S? = null
            var retained = false
            try {
                requireCurrent()
                created = connect(endpoint)
                synchronized(lock) {
                    if (!admitted()) throw CancellationException("Cloud connection owner changed")
                    session = created; retained = true; finishIfReady()
                }
            } catch (failure: Exception) { fail(failure) }
            catch (_: LinkageError) { fail(IllegalStateException("Cloud native runtime is unavailable")) }
            finally { if (!retained) created?.close() }
        }
    }
    private fun finishIfReady() {
        val ready = session ?: return
        if (!approved) return
        mutable.value = CloudLinkState(CloudLinkPhase.READY)
        result.complete(ready)
    }
    private suspend fun approveUntilGranted(invitation: String) {
        repeat(approvalAttempts) {
            requireCurrent()
            delay(2_000)
            requireCurrent()
            try {
                if (service.approve(machineId, invitation)) return
            } catch (failure: CancellationException) { throw failure }
            catch (failure: CloudApiFailure) {
                if (failure.status == 404) error("Cloud invitation expired")
                if (!CloudSessionFailure.classify(failure, CloudFailureKind.LINK).retryable) throw failure
            } catch (failure: CloudNotSignedIn) { throw failure }
            catch (_: Exception) { /* A transient approval read can be polled again. */ }
        }
        error("Cloud invitation approval timed out")
    }
    private fun fail(failure: Exception) {
        val owned = synchronized(lock) {
            if (stopped) return
            stopped = true
            mutable.value = if (failure is CancellationException) CloudLinkState(CloudLinkPhase.CLOSED)
                else CloudLinkState(CloudLinkPhase.FAILED, CloudSessionFailure.classify(failure, CloudFailureKind.LINK))
            result.completeExceptionally(failure)
            session.also { session = null }
        }
        job.cancel()
        // Never block the account/UI dispatcher in disconnect's callback drain.
        if (owned != null) {
            retireSession(owned)
            CoroutineScope(nativeDispatcher).launch { owned.close() }
        }
    }
    override fun close() { fail(CancellationException("Cloud connection closed")) }
}
