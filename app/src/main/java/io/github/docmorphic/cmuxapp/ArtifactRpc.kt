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
    private val nativeLane: (suspend (String, suspend (ArtifactLane) -> Unit) -> Boolean)?,
    private val request: suspend (String, JSONObject) -> JSONObject,
) {
    constructor(capabilities: ArtifactCapabilities, request: suspend (String, JSONObject) -> JSONObject) : this(capabilities, null, request)
    constructor(client: MobileRpcClient, capabilities: Set<String>) : this(ArtifactCapabilities.read(capabilities),
        if (client.supportsArtifactLanes) client::useArtifactLane else null,
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
    /** False means no lane data was accepted and the same authorized RPC can start at zero. */
    suspend fun streamNative(scope: ArtifactAuthorization, path: String, size: Long,
                             consume: suspend (ByteArray, Long) -> Unit): Boolean {
        val open = nativeLane ?: return false
        val params = pathParams(scope, path).put("transport", "iroh_artifact_v1")
        var entered = false
        try {
            val descriptor = call(method(scope, "fetch"), params)
            val resource = descriptor.get("resource_id") as? String ?: error("Invalid artifact capability")
            require(resource.isNotBlank() && resource.length <= 8192 && '\u0000' !in resource)
            val total = descriptor.get("total_size").toString().toLongOrNull()
            require(total != null && total >= 0 && total == size) { "File changed before transfer" }
            // Validate the date dialect; the Mac enforces expiration using its own clock.
            java.time.Instant.parse(descriptor.getString("expires_at"))
            return open(resource) { lane ->
                entered = true
                ArtifactLaneTransfer.stream(lane, total, consume)
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (!entered || failure is ArtifactLaneTransfer.BeforeData) return false
            throw failure
        }
    }
    suspend fun thumbnail(scope: ArtifactAuthorization, path: String, maxDimension: Int): JSONObject {
        require(maxDimension > 0)
        return call(method(scope, "thumbnail"), pathParams(scope, path).put("max_dimension", maxDimension))
    }
    private fun method(scope: ArtifactAuthorization, operation: String) =
        "mobile.${if (scope is ArtifactAuthorization.Terminal) "terminal" else "chat"}.artifact.$operation"
    private fun pathParams(scope: ArtifactAuthorization, path: String): JSONObject {
        require(if (scope is ArtifactAuthorization.Terminal) validTerminalArtifactPath(path) else validArtifactPath(path)) { "Invalid Mac file path." }
        return params(scope).put("path", path)
    }
    private fun params(scope: ArtifactAuthorization): JSONObject = when (scope) {
        is ArtifactAuthorization.Terminal -> JSONObject().put("workspace_id", scope.workspaceId).put("surface_id", scope.surfaceId)
        is ArtifactAuthorization.Session -> JSONObject().put("session_id", scope.sessionId)
    }
}
