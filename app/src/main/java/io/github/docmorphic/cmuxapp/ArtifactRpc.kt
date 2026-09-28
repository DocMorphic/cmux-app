package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.UUID

internal data class ArtifactCapabilities(val terminal: Boolean, val gallery: Boolean, val sessionFolders: Boolean, val terminalFolders: Boolean) {
    companion object {
        fun read(capabilities: Set<String>) = ArtifactCapabilities("terminal.artifact.v1" in capabilities,
            "chat.artifact.gallery.v1" in capabilities, "chat.artifact.folders.v1" in capabilities,
            "terminal.artifact.list.v1" in capabilities)
    }
}
internal sealed interface ArtifactAuthorization {
    data class Terminal(val workspaceId: String, val surfaceId: String) : ArtifactAuthorization {
        init { require(workspaceId.isNotBlank() && surfaceId.isNotBlank()) }
    }
    data class Session(val sessionId: String) : ArtifactAuthorization {
        init { require(sessionId.isNotBlank()) }
    }
}

/** Bound to one connection. Scope is captured at selection; it never falls back to a different authorization. */
internal class ArtifactRpc(
    val capabilities: ArtifactCapabilities,
    private val request: suspend (String, JSONObject) -> JSONObject,
) {
    constructor(client: MobileRpcClient, capabilities: Set<String>) : this(ArtifactCapabilities.read(capabilities),
        { method, params -> client.request(method, params) })

    private suspend fun call(method: String, params: JSONObject): JSONObject {
        currentCoroutineContext().ensureActive()
        return request(method, params).also { currentCoroutineContext().ensureActive() }
    }
    suspend fun scan(terminal: ArtifactAuthorization.Terminal, visibleOnly: Boolean = true,
        countOnly: Boolean = false, includeMissing: Boolean = true): TerminalArtifactScan {
        check(capabilities.terminal) { "Update cmux on your Mac to view terminal files." }
        val params = params(terminal).put("include_missing", includeMissing).put("trace_id", UUID.randomUUID().toString())
        if (visibleOnly) params.put("visible_only", true)
        if (countOnly) params.put("count_only", true)
        if (capabilities.terminalFolders) params.put("include_directories", true)
        return TerminalArtifactScan.read(call("mobile.terminal.artifact.scan", params), capabilities.terminalFolders)
    }
    suspend fun gallery(session: ArtifactAuthorization.Session, cursor: String? = null, query: String? = null): ArtifactGalleryPage {
        check(capabilities.gallery) { "Update cmux on your Mac to view session files." }
        val params = params(session).put("page_size", 60)
        if (cursor != null) params.put("cursor", cursor)
        query?.trim()?.takeIf { it.isNotEmpty() }?.let { params.put("query", it) }
        if (capabilities.sessionFolders) params.put("include_directories", true)
        return ArtifactGalleryPage.read(call("mobile.chat.artifact.gallery", params), session.sessionId, capabilities.sessionFolders)
    }
    suspend fun list(scope: ArtifactAuthorization, path: String): ArtifactDirectoryListing {
        check(when (scope) {
            is ArtifactAuthorization.Terminal -> capabilities.terminalFolders
            is ArtifactAuthorization.Session -> capabilities.sessionFolders
        }) { "Update cmux on your Mac to browse folders." }
        val params = pathParams(scope, path)
        if (scope is ArtifactAuthorization.Terminal) params.put("trace_id", UUID.randomUUID().toString())
        return ArtifactDirectoryListing.read(call(method(scope, "list"), params), path)
    }
    suspend fun stat(scope: ArtifactAuthorization, path: String): JSONObject = call(method(scope, "stat"), pathParams(scope, path))
    suspend fun fetch(scope: ArtifactAuthorization, path: String, offset: Long, length: Int): JSONObject {
        require(offset >= 0 && length in 1..ChangesContentTransfer.CHUNK_BYTES)
        return call(method(scope, "fetch"), pathParams(scope, path).put("offset", offset).put("length", length))
    }
    suspend fun thumbnail(scope: ArtifactAuthorization, path: String, maxDimension: Int): JSONObject {
        require(maxDimension > 0)
        return call(method(scope, "thumbnail"), pathParams(scope, path).put("max_dimension", maxDimension))
    }
    private fun method(scope: ArtifactAuthorization, operation: String) =
        "mobile.${if (scope is ArtifactAuthorization.Terminal) "terminal" else "chat"}.artifact.$operation"
    private fun pathParams(scope: ArtifactAuthorization, path: String): JSONObject {
        require(validArtifactPath(path)) { "Invalid Mac file path." }
        return params(scope).put("path", path)
    }
    private fun params(scope: ArtifactAuthorization): JSONObject = when (scope) {
        is ArtifactAuthorization.Terminal -> JSONObject().put("workspace_id", scope.workspaceId).put("surface_id", scope.surfaceId)
        is ArtifactAuthorization.Session -> JSONObject().put("session_id", scope.sessionId)
    }
}
