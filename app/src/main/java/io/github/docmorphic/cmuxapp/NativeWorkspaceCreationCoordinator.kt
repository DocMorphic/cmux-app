package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

internal data class NativeCreationRequest(val id: String, val login: String,
    val mac: NativeCredentialStore.PairedMac, val workspaceId: String? = null, val groupId: String? = null,
    val team: NativeTeamScope? = null)
internal data class NativeCreatedWorkspace(val workspace: NativeWorkspace, val terminalId: String?)
internal sealed interface NativeCreationState {
    val request: NativeCreationRequest?
    data object Idle : NativeCreationState { override val request = null }
    data class Running(override val request: NativeCreationRequest) : NativeCreationState
    data class Ready(override val request: NativeCreationRequest, val destination: NativeCreatedWorkspace?) : NativeCreationState
    data class Failed(override val request: NativeCreationRequest, val message: String) : NativeCreationState
}

/** The session owns a single send. Activity destruction only removes its UI waiter. */
internal class NativeWorkspaceCreationCoordinator(private val scope: CoroutineScope,
    private val admitted: (NativeCreationRequest) -> Boolean,
    private val hold: (NativeCredentialStore.PairedMac) -> AutoCloseable,
    private val create: suspend (NativeCreationRequest) -> JSONObject,
    private val latest: (NativeCredentialStore.PairedMac, NativeWorkspace) -> NativeWorkspace) {
    private val mutable = MutableStateFlow<NativeCreationState>(NativeCreationState.Idle)
    val state = mutable.asStateFlow()
    private var task: Job? = null

    fun begin(login: String, mac: NativeCredentialStore.PairedMac, workspaceId: String? = null,
        groupId: String? = null, team: NativeTeamScope? = null): String? {
        require(workspaceId == null || groupId == null)
        if (!scope.isActive || mutable.value is NativeCreationState.Running) return null
        val request = NativeCreationRequest(UUID.randomUUID().toString(), login, mac, workspaceId, groupId, team)
        if (!admitted(request)) return null
        mutable.value = NativeCreationState.Running(request)
        task = scope.launch {
            var lease: AutoCloseable? = null
            fun current() = mutable.value.request == request && admitted(request)
            try {
                check(current()) { "Workspace account changed" }
                lease = hold(mac)
                val response = create(request)
                ensureActive()
                if (!current()) return@launch
                val destination = if (workspaceId == null) {
                    createdPlainWorkspace(response)?.let { returned ->
                        val workspace = latest(mac, returned)
                        NativeCreatedWorkspace(workspace,
                            workspace.terminals.singleOrNull { it.id == response.optString("created_terminal_id") }?.id
                                ?: workspace.preferredTerminal?.id)
                    }
                } else {
                    val id = response.opt("created_terminal_id") as? String
                    check(!id.isNullOrBlank()) { "Mac did not return the created terminal." }
                    val workspace = parseAuthoritativeWorkspaces(response).singleOrNull { it.id == workspaceId }
                        ?.let { latest(mac, it) }
                    check(workspace != null && workspace.terminals.any { it.id == id }) {
                        "Mac did not return the created terminal workspace."
                    }
                    NativeCreatedWorkspace(workspace, id)
                }
                mutable.value = NativeCreationState.Ready(request, destination)
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (current()) {
                    if (failure !is CancellationException) recordWorkspaceActionFailure(failure)
                    mutable.value = NativeCreationState.Failed(request,
                        "Creation was not confirmed. Check this Mac's workspaces before trying again.")
                }
            } finally {
                lease?.close()
                if (mutable.value is NativeCreationState.Running && mutable.value.request == request)
                    mutable.value = NativeCreationState.Idle
            }
        }
        return request.id
    }

    fun clearCompleted(id: String) {
        if (mutable.value.request?.id == id && mutable.value !is NativeCreationState.Running)
            mutable.value = NativeCreationState.Idle
    }
    fun clear() { task?.cancel(); task = null; mutable.value = NativeCreationState.Idle }
}
