package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*

/** Foreground access binds requests to one client and its exact currently displayed descriptor. */
internal class NativeForegroundArtifactAccess(private val scope: CoroutineScope, private val client: MobileRpcClient,
    private val live: () -> Boolean, private val cacheAllowed: () -> Boolean) {
    private fun cached(descriptor: () -> Boolean): Boolean = cacheAllowed() && descriptor() &&
        client.disconnected.replayCache.lastOrNull()?.let(NativePanelCachePolicy::retains) != false
    private fun admitted(descriptor: () -> Boolean) = !client.isClosed && live() && cached(descriptor)
    private suspend fun <T> use(descriptor: () -> Boolean, action: suspend () -> T): T =
        withContext(scope.coroutineContext.minusKey(Job)) {
            check(admitted(descriptor)) { "Artifact connection or descriptor changed" }
            val result = try { withContext(Dispatchers.IO) { action() } }
            catch (failure: Exception) {
                if (nativeSetupRequiresReauth(failure) && !client.isClosed) client.retireForOwnerFailure(failure)
                throw failure
            }
            ensureActive(); check(admitted(descriptor)) { "Artifact changed while loading" }
            result
        }

    fun terminal(terminal: ArtifactAuthorization.Terminal, capabilities: ArtifactCapabilities,
        descriptor: () -> Boolean): TerminalArtifactAccess? {
        if (!capabilities.terminal || !admitted(descriptor)) return null
        val rpc = ArtifactRpc(capabilities,
            if (client.supportsArtifactLanes) ({ resource, consume -> use(descriptor) { client.useArtifactLane(resource, consume) } }) else null,
            { method, params ->
                require((method.startsWith("mobile.terminal.artifact.") &&
                    params.optString("workspace_id") == terminal.workspaceId && params.optString("surface_id") == terminal.surfaceId) ||
                    (method.startsWith("mobile.chat.artifact.") && (capabilities.gallery || capabilities.sessionFolders) &&
                        params.optString("session_id").isNotBlank())) {
                    "Artifact request names another terminal"
                }
                use(descriptor) { client.request(method, params) }
            })
        return TerminalArtifactAccess(rpc, { admitted(descriptor) }, { cached(descriptor) })
    }

    fun panel(target: NativePanelTarget, descriptor: () -> Boolean): PanelArtifactAccess? {
        if (!admitted(descriptor)) return null
        val rpc = ArtifactRpc(ArtifactCapabilities(false, false, false, false, panel = true),
            if (client.supportsArtifactLanes) ({ resource, consume -> use(descriptor) { client.useArtifactLane(resource, consume) } }) else null,
            { method, params ->
                require(method in setOf("mobile.panel.artifact.stat", "mobile.panel.artifact.fetch", "mobile.panel.artifact.thumbnail") &&
                    params.optString("workspace_id") == target.workspace && params.optString("surface_id") == target.surface &&
                    params.optString("path") == target.path) { "Artifact request isn't for this displayed panel" }
                use(descriptor) { client.request(method, params) }
            })
        return PanelArtifactAccess(rpc, { admitted(descriptor) }, { cached(descriptor) })
    }
}
