package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class LocalBrowserDestination(val key: LocalBrowserKey, val workspace: NativeWorkspace,
    val terminalId: String?, val surface: LocalBrowserSurface)
internal data class LocalBrowserNavigationState(val local: LocalBrowserDestination? = null, val creating: LocalBrowserKey? = null)

/** A saved account-owned Mac remains scoped while account refresh is offline. */
internal fun localBrowserKey(login: String?, owner: NativeTeamScope?, mac: NativeCredentialStore.PairedMac,
    workspaceId: String): LocalBrowserKey? = login?.let {
    LocalBrowserKey(owner?.userId ?: mac.accountUserId ?: "session:$it", owner?.teamId ?: mac.accountTeamId, mac.origin, workspaceId)
}

internal fun localBrowserCreatedPanel(response: JSONObject, workspaceId: String): String? =
    (response.opt("panel_id") as? String)?.takeIf { it.isNotBlank() && response.opt("workspace_id") == workspaceId }

/** Cached, missing or reconnecting snapshots cannot prove that a workspace was closed. */
internal fun localBrowserWorkspacePresent(source: NativeFeedSource?, workspaceId: String): Boolean =
    source == null || source.availability != NativeFeedAvailability.CONNECTED || !source.hasWorkspaceSnapshot ||
        source.workspaces.any { it.id == workspaceId }

/** UI-owner confined. A create is sent once, even when its remote outcome is unknown. */
internal class LocalBrowserNavigation(private val scope: CoroutineScope,
    private val store: LocalBrowserStore = LocalBrowserStore(), private val timeoutMillis: Long = 20_000) {
    private val mutable = MutableStateFlow(LocalBrowserNavigationState())
    val state = mutable.asStateFlow()
    private var request = 0L
    private var pending: Job? = null
    private var context: Any? = null
    private var accountScope: NativeTeamScope? = null
    private var loginScope: String? = null
    private val returnTerminals = mutableMapOf<LocalBrowserKey, String?>()
    fun navigationContext(value: Any) {
        if (context != value) { context = value; cancelRequest() }
    }
    fun retain(owner: NativeTeamScope?, signedIn: Boolean, origins: Set<String>, login: String? = owner?.login) {
        if (!signedIn || (loginScope != null && loginScope != login) ||
            (owner != null && accountScope != null && owner != accountScope)) clear()
        loginScope = login
        if (owner != null) {
            accountScope = owner
            store.retainAccount(owner.userId, owner.teamId)
        }
        store.retainComputers(origins)
        returnTerminals.keys.removeAll { it.computerId !in origins }
        if (state.value.local?.surface?.state?.value?.closed == true) leave(close = true)
        if (state.value.local?.key?.computerId?.let { it !in origins } == true) leave(close = true)
        if (state.value.creating?.computerId?.let { it !in origins } == true) cancelRequest()
    }
    fun cancelRequest() {
        request++; pending?.cancel(); pending = null
        if (state.value.creating != null) mutable.value = state.value.copy(creating = null)
    }
    fun hasLocal(key: LocalBrowserKey) = store.active(key) != null
    fun observeWorkspaces(source: NativeFeedSource) {
        if (source.availability != NativeFeedAvailability.CONNECTED || !source.hasWorkspaceSnapshot) return
        val ids = source.workspaces.map { it.id }.toSet()
        fun absent(key: LocalBrowserKey) = key.computerId == source.mac.origin && key.workspaceId !in ids
        store.retainWorkspaces(source.mac.origin, ids)
        returnTerminals.keys.removeAll(::absent)
        state.value.local?.takeIf { it.key.computerId == source.mac.origin }?.let { local ->
            val workspace = source.workspaces.singleOrNull { it.id == local.key.workspaceId }
            mutable.value = state.value.copy(local = workspace?.let { local.copy(workspace = it) })
        }
        if (state.value.creating?.let(::absent) == true) cancelRequest()
    }
    fun open(key: LocalBrowserKey, workspace: NativeWorkspace, terminalId: String?, canCreate: Boolean,
        create: suspend () -> String?, stillCurrent: () -> Boolean,
        onLocal: () -> Unit, onRemote: (String) -> Unit) {
        if (state.value.creating == key) return
        cancelRequest()
        val ticket = request
        mutable.value = state.value.copy(creating = key)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val panel = if (canCreate) try {
                    withTimeoutOrNull(timeoutMillis) { create()?.takeIf { it.isNotBlank() } }
                } catch (failure: Exception) {
                    // A retired borrowed host lease can throw CancellationException while
                    // this UI request is still active. It follows the same fallback policy.
                    currentCoroutineContext().ensureActive()
                    null
                } else null
                if (ticket != request || !stillCurrent()) return@launch
                mutable.value = state.value.copy(creating = null)
                if (panel != null) {
                    store.close(key); returnTerminals.remove(key); mutable.value = state.value.copy(local = null)
                    onRemote(panel)
                } else {
                    returnTerminals[key] = terminalId ?: workspace.terminals.firstOrNull()?.id
                    mutable.value = LocalBrowserNavigationState(LocalBrowserDestination(key, workspace,
                        returnTerminals[key], store.open(key)))
                    onLocal()
                }
            } finally {
                if (ticket == request) { pending = null; mutable.value = state.value.copy(creating = null) }
            }
        }
        pending = job; job.start()
    }
    /** Back preserves the workspace tab; explicit pane selection/Close removes it. */
    fun leave(close: Boolean): LocalBrowserDestination? {
        cancelRequest()
        val previous = state.value.local
        previous?.let { if (close) { store.close(it.key); returnTerminals.remove(it.key) } else store.requestRestore(it.key) }
        mutable.value = state.value.copy(local = null)
        return previous
    }
    fun selectMacPane(key: LocalBrowserKey) { leave(close = false); store.close(key); returnTerminals.remove(key) }
    fun restore(key: LocalBrowserKey, workspace: NativeWorkspace): Boolean {
        val surface = store.consumeRestore(key) ?: return false
        cancelRequest()
        mutable.value = LocalBrowserNavigationState(LocalBrowserDestination(key, workspace,
            returnTerminals[key] ?: workspace.terminals.firstOrNull()?.id, surface))
        return true
    }
    /** Persisted local-tab memory reopens directly, without attempting a Mac creation RPC. */
    fun restoreRemembered(key: LocalBrowserKey, workspace: NativeWorkspace): Boolean {
        store.requestRestore(key)
        return restore(key, workspace)
    }
    fun restoreFromMemory(key: LocalBrowserKey, workspace: NativeWorkspace, remembered: NativeWorkspaceTab?): Boolean =
        if (remembered == NativeWorkspaceTab.LocalBrowser) restoreRemembered(key, workspace)
        else remembered == null && restore(key, workspace)
    fun clear() { cancelRequest(); store.clear(); returnTerminals.clear(); mutable.value = LocalBrowserNavigationState(); accountScope = null; loginScope = null }
    companion object {
        fun canCreate(ready: Boolean, capabilities: Set<String>) = ready &&
            "browser.stream.v1" in capabilities && "browser.stream.create.v1" in capabilities
    }
}
