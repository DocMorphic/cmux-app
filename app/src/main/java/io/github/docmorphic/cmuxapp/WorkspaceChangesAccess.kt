package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal sealed interface WorkspaceChangesRead {
    data object Files : WorkspaceChangesRead
    data class Diff(val path: String, val budget: Int) : WorkspaceChangesRead
    data class Stat(val path: String, val revision: ChangesRevision) : WorkspaceChangesRead
    data class Fetch(val path: String, val revision: ChangesRevision, val offset: Long, val length: Int) : WorkspaceChangesRead
}

/** A captured, read-only workspace. Every response is rechecked before it can reach the viewer. */
internal class WorkspaceChangesAccess(val workspaceId: String, val title: String, val current: () -> Boolean,
    private val fetch: suspend (WorkspaceChangesRead) -> JSONObject) {
    suspend fun read(request: WorkspaceChangesRead): JSONObject {
        check(current()) { "Changes workspace is no longer available" }
        val value = fetch(request)
        currentCoroutineContext().ensureActive()
        check(current()) { "Changes workspace changed" }
        return value
    }
    val content = ChangesContentTransfer(
        { path, revision -> read(WorkspaceChangesRead.Stat(path, revision)) },
        { path, revision, offset, length -> read(WorkspaceChangesRead.Fetch(path, revision, offset, length)) })
}

internal suspend fun MobileRpcClient.readChanges(workspace: String, request: WorkspaceChangesRead): JSONObject = when (request) {
    WorkspaceChangesRead.Files -> changedFiles(workspace)
    is WorkspaceChangesRead.Diff -> fileDiff(workspace, request.path, request.budget.takeIf { it != DiffContinuation.DEFAULT_BUDGET })
    is WorkspaceChangesRead.Stat -> this.request("mobile.workspace.changes.file_stat", JSONObject().put("workspace_id", workspace)
        .put("path", request.path).put("revision", request.revision.wire))
    is WorkspaceChangesRead.Fetch -> this.request("mobile.workspace.changes.file_fetch", JSONObject().put("workspace_id", workspace)
        .put("path", request.path).put("revision", request.revision.wire).put("offset", request.offset).put("length", request.length))
}
