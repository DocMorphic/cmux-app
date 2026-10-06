/* Creation behavior follows CloudWorkspaceBridge at cmux c2715faa.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CloudCreatedTarget(val machineId: String, val workspaceId: String, val terminalId: String? = null)
internal data class CloudWorkspaceCreationState(val pending: Boolean = false, val failure: String? = null,
    val machineId: String? = null) {
    fun failureFor(machineId: String) = failure.takeIf { this.machineId == machineId }
}

/** Owned by the account, not by the menu awaiting a result. Never retries a mutation. */
internal class CloudWorkspaceCreation(parent: CoroutineScope, private val catalog: CloudWorkspaceController,
    private val create: suspend (machineId: String, workspaceId: String?) -> String) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val mutable = MutableStateFlow(CloudWorkspaceCreationState())
    val state = mutable.asStateFlow()
    fun canCreate(machineId: String, workspaceId: String? = null) = job.isActive && !mutable.value.pending &&
        catalog.canCreate(machineId, workspaceId)
    fun request(machineId: String, workspaceId: String? = null, onCreated: (CloudCreatedTarget) -> Unit): Boolean {
        if (!canCreate(machineId, workspaceId)) return false
        val epoch = catalog.creationEpoch(machineId) ?: return false
        fun current() = job.isActive && catalog.creationEpoch(machineId) == epoch
        mutable.value = CloudWorkspaceCreationState(pending = true, machineId = machineId)
        scope.launch {
            var acknowledged = false
            try {
                if (!current()) return@launch
                // "unassigned" is a projection, never a real daemon workspace ID.
                val makesWorkspace = workspaceId == null || workspaceId == "unassigned"
                val created = create(machineId, if (makesWorkspace) null else workspaceId)
                ensureActive()
                if (!current()) return@launch
                acknowledged = true
                if (makesWorkspace) {
                    catalog.publishCreatedWorkspace(machineId, created)
                    if (workspaceId == null) onCreated(CloudCreatedTarget(machineId, created))
                }
                val refreshed = catalog.reloadAfterCreation(machineId)
                ensureActive()
                if (!current()) return@launch
                if (workspaceId != null) {
                    val terminal = if (makesWorkspace) refreshed?.terminals?.firstOrNull { it.workspaceId == created }
                        else refreshed?.terminals?.singleOrNull { it.id == created && it.workspaceId == workspaceId }
                    checkNotNull(terminal) { "Created, but the new terminal is not available yet. Refresh the workspace before trying again." }
                    onCreated(CloudCreatedTarget(machineId, checkNotNull(terminal.workspaceId), terminal.id))
                } else checkNotNull(refreshed) { "Created, but the workspace could not be refreshed yet. Refresh before trying again." }
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                if (failure !is Exception && failure !is LinkageError) throw failure
                if (current()) {
                    val message = if (acknowledged) "Created, but the new workspace or terminal is not available yet. Refresh before trying again."
                        else "Creation could not be confirmed. Refresh before trying again. ${CloudSessionFailure.classify(failure as? Exception ?: IllegalStateException("Cloud native runtime is unavailable."), CloudFailureKind.LINK).userReason}"
                    mutable.value = mutable.value.copy(failure = message)
                    catalog.refresh(machineId)
                }
            } finally {
                if (job.isActive) mutable.value = mutable.value.copy(pending = false)
            }
        }
        return true
    }
    fun clearFailure(machineId: String? = null) {
        if (job.isActive && (machineId == null || mutable.value.machineId == machineId)) mutable.value = mutable.value.copy(failure = null)
    }
    override fun close() { job.cancel(); mutable.value = CloudWorkspaceCreationState() }
}
