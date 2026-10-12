package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*

/** Cached presentation and live reads have separate guards on one admitted workspace. */
internal class NativeForegroundChangesAccess(private val scope: CoroutineScope, private val client: MobileRpcClient,
    private val live: () -> Boolean, private val cacheAllowed: () -> Boolean) {
    fun workspace(id: String, title: String, descriptor: () -> Boolean): WorkspaceChangesAccess? {
        fun cached() = cacheAllowed() && descriptor() &&
            client.disconnected.replayCache.lastOrNull()?.let(NativePanelCachePolicy::retains) != false
        fun admitted() = !client.isClosed && live() && cached()
        if (!admitted()) return null
        return WorkspaceChangesAccess(id, title, ::cached) { request ->
            withContext(scope.coroutineContext.minusKey(Job)) {
                check(admitted()) { "Changes computer or workspace is unavailable" }
                val result = try { withContext(Dispatchers.IO) { client.readChanges(id, request) } }
                catch (failure: Exception) {
                    if (nativeSetupRequiresReauth(failure) && !client.isClosed) client.retireForOwnerFailure(failure)
                    throw failure
                }
                ensureActive(); check(admitted()) { "Changes workspace changed while loading" }
                result
            }
        }
    }
}
