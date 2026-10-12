package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Mutations stay on one admitted client; a lost response is never replayed here. */
internal class NativeForegroundWorkspaceAccess(private val scope: CoroutineScope,
    private val mac: NativeCredentialStore.PairedMac, private val client: MobileRpcClient,
    private val snapshots: NativeWorkspaceSnapshots, private val capabilities: () -> Set<String>,
    private val permits: () -> Boolean) {
    private val mutex = Mutex()
    private fun current() = !client.isClosed && permits()
    private fun source(): NativeFeedSource {
        check(current()) { "Workspace connection changed" }
        val snapshot = snapshots.latest(mac) ?: error("Refresh this Mac's workspaces before changing them.")
        return NativeFeedSource(mac, workspaces = snapshot.workspaces, groups = parseGroups(snapshot.value),
            capabilities = capabilities(), macMutationTicket = client.macMutationTicket(), hasWorkspaceSnapshot = true)
    }
    private suspend fun <T> use(canSend: () -> Boolean, operation: suspend (NativeFeedSource) -> T): T =
        withContext(scope.coroutineContext.minusKey(Job)) {
            mutex.withLock {
                check(current() && canSend()) { "Workspace action is no longer available" }
                val result = try {
                    val source = source()
                    val result = snapshots.mutate(mac) { operation(source) }
                    ensureActive()
                    check(current() && canSend()) { "Workspace connection changed while saving" }
                    result
                } catch (failure: Exception) {
                    if (nativeSetupRequiresReauth(failure) && !client.isClosed) client.retireForOwnerFailure(failure)
                    throw failure
                } finally {
                    // Reconcile even after a no-op or uncertain outcome, without repeating the write.
                    if (currentCoroutineContext().isActive && current()) {
                        try {
                            val fresh = snapshots.read(mac, client)
                            check(current()) { "Workspace connection changed while refreshing" }
                            fresh.accept()
                        } catch (failure: Exception) {
                            currentCoroutineContext().ensureActive()
                            if (nativeSetupRequiresReauth(failure) && !client.isClosed) client.retireForOwnerFailure(failure)
                        }
                    }
                }
                ensureActive()
                check(current() && canSend()) { "Workspace connection changed while refreshing" }
                result
            }
        }

    suspend fun workspaceAction(workspaceId: String, action: String, title: String? = null,
        canSend: () -> Boolean = { true }): JSONObject = use(canSend) { source ->
        val workspace = source.workspaces.singleOrNull { it.id == workspaceId }
            ?: error("This workspace is no longer available on ${mac.name}.")
        val capability = when (action) {
            "rename", "pin", "unpin" -> "workspace.actions.v1"
            "mark_read", "mark_unread" -> "workspace.read_state.v1"
            "close" -> "workspace.close.v1"
            else -> null
        }
        check(capability == null || capability in source.capabilities) { "This Mac does not support this workspace action." }
        when {
            action == "terminal.create" -> client.createTerminal(workspace.id)
            action == "browser.create" -> client.createBrowser(workspace.id)
            action == "close" -> client.closeWorkspace(workspace.id, workspace.windowId)
            action.startsWith("move:") -> {
                check("workspace.move.v1" in source.capabilities) { "This Mac does not support moving workspaces." }
                val group = action.removePrefix("move:").takeIf(String::isNotBlank)
                check(group == null || source.groups.any { it.id == group }) { "This group is no longer available." }
                client.moveWorkspace(workspace.id, workspace.windowId, group)
            }
            else -> client.workspaceAction(workspace.id, workspace.windowId, action, title)
        }
    }

    suspend fun createWorkspace(groupId: String? = null, canSend: () -> Boolean = { true }): JSONObject = use(canSend) { source ->
        val params = JSONObject()
        if (groupId != null) {
            check(source.canCreateInGroup()) { "Pair this Mac again or update cmux to create workspaces in groups." }
            check(source.groups.any { it.id == groupId }) { "This group is no longer available." }
            params.put("group_id", groupId)
        }
        client.request("workspace.create", params).also(::createdPlainWorkspace)
    }

    suspend fun createGroup(canSend: () -> Boolean = { true }): JSONObject = use(canSend) { source ->
        check(source.canCreateGroup()) { "Pair this Mac again or update cmux to create groups." }
        client.createGroup("")
    }

    suspend fun groupAction(groupId: String, action: String, title: String? = null,
        canSend: () -> Boolean = { true }): JSONObject = use(canSend) { source ->
        check(source.canEditGroups()) { "Pair this Mac again or update cmux to change groups." }
        val group = source.groups.singleOrNull { it.id == groupId } ?: error("This group is no longer available.")
        check(action != "ungroup" || !group.isPinned) { "Unpin this group before ungrouping it." }
        client.groupAction(groupId, action, title)
    }

    suspend fun createTask(params: JSONObject, canSend: () -> Boolean): JSONObject = use(canSend) { source ->
        check("workspace.task_create.v1" in source.capabilities) { "Update cmux to create tasks." }
        val group = params.opt("group_id") as? String
        if (group != null) {
            check(source.canCreateInGroup() && source.groups.count { it.id == group } == 1) { "This group is no longer available." }
        }
        client.request("workspace.create", params, timeoutMillis = 30_000)
    }
}
