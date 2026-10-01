package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class SshLoginState<R>(val login: String? = null, val resource: R? = null, val failed: Boolean = false)

/** Owns resources by login incarnation, never by access token or account/team label. */
internal class SshLoginOwner<R : AutoCloseable>(
    lifetime: CoroutineScope,
    revisions: StateFlow<Long>,
    private val currentLogin: () -> String?,
    private val load: suspend (CoroutineScope, () -> Boolean) -> R,
) : AutoCloseable {
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(lifetime.coroutineContext + job)
    private val retry = MutableStateFlow(0L)
    private val mutable = MutableStateFlow(SshLoginState<R>())
    val state = mutable.asStateFlow()
    init {
        scope.launch {
            combine(revisions, retry) { _, attempt -> currentLogin() to attempt }
                .distinctUntilChanged().collectLatest { (login, _) ->
                    mutable.value = SshLoginState(login)
                    if (login == null) return@collectLatest
                    coroutineScope {
                        val owner = this
                        val admitted = { owner.isActive && currentLogin() == login }
                        var resource: R? = null
                        try {
                            resource = load(owner, admitted)
                            ensureActive()
                            if (!admitted()) return@coroutineScope
                            mutable.value = SshLoginState(login, resource)
                            awaitCancellation()
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            if (admitted()) mutable.value = SshLoginState(login, failed = true)
                        } finally {
                            resource?.close()
                            if (resource != null && mutable.value.resource === resource) mutable.value = SshLoginState(login)
                        }
                    }
                }
        }
    }
    fun retry() { retry.value++ }
    override fun close() {
        // Revoke admission synchronously before the collector's finally executes.
        job.cancel()
        mutable.value.resource?.close()
        mutable.value = SshLoginState()
    }
}
