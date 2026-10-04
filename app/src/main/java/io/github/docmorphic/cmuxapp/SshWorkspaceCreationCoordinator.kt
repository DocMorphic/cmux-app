package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class SshWorkspaceCreationRequest(val id: String, val host: SshHostRecord, val kind: SshWorkspaceKind)
internal sealed interface SshWorkspaceCreationState {
    val request: SshWorkspaceCreationRequest?
    data object Idle : SshWorkspaceCreationState { override val request = null }
    data class Running(override val request: SshWorkspaceCreationRequest) : SshWorkspaceCreationState
    data class Ready(override val request: SshWorkspaceCreationRequest, val target: SshWorkspaceTarget) : SshWorkspaceCreationState
    data class Failed(override val request: SshWorkspaceCreationRequest, val message: String) : SshWorkspaceCreationState
}

/** One explicit operation owned by the login, not by an Activity or its saved state. */
internal class SshWorkspaceCreationCoordinator(lifetime: CoroutineScope, private val admitted: () -> Boolean,
    private val create: suspend (SshHostRecord, SshWorkspaceKind) -> SshWorkspaceTarget) : AutoCloseable {
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(lifetime.coroutineContext + job)
    private val mutable = MutableStateFlow<SshWorkspaceCreationState>(SshWorkspaceCreationState.Idle)
    val state = mutable.asStateFlow()
    fun begin(host: SshHostRecord, kind: SshWorkspaceKind): String? {
        if (!job.isActive || !admitted() || mutable.value is SshWorkspaceCreationState.Running) return null
        val request = SshWorkspaceCreationRequest(UUID.randomUUID().toString(), host, kind)
        mutable.value = SshWorkspaceCreationState.Running(request)
        scope.launch {
            try {
                check(admitted()) { "SSH account changed" }
                val target = create(host, kind)
                ensureActive()
                if (admitted()) mutable.value = SshWorkspaceCreationState.Ready(request, target)
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (admitted()) mutable.value = SshWorkspaceCreationState.Failed(request,
                    failure.message ?: "Creation was not confirmed. Check the computer before trying again.")
            }
        }
        return request.id
    }
    fun clearCompleted(id: String) {
        if (mutable.value.request?.id == id && mutable.value !is SshWorkspaceCreationState.Running)
            mutable.value = SshWorkspaceCreationState.Idle
    }
    override fun close() { job.cancel(); mutable.value = SshWorkspaceCreationState.Idle }
}
