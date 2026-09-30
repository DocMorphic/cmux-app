package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class LocalBrowserKey(val accountId: String, val teamId: String?, val computerId: String, val workspaceId: String)
internal enum class LocalBrowserCommand { BACK, FORWARD, RELOAD, STOP }
internal data class LocalBrowserSnapshot(val address: String = "", val editing: Boolean = false,
    val url: String? = null, val title: String? = null, val loading: Boolean = false, val progress: Float = 0f,
    val canGoBack: Boolean = false, val canGoForward: Boolean = false, val error: String? = null,
    val workRevision: Long = 0, val closed: Boolean = false)
internal data class LocalBrowserWork(val url: String?, val command: LocalBrowserCommand?)

/** Local state survives workspace inventory refreshes; it never owns or borrows a Mac RPC client. */
internal class LocalBrowserSurface(val id: String, initialUrl: String? = null,
    private val resolver: LocalBrowserAddress = LocalBrowserAddress()) {
    private val mutable = MutableStateFlow(LocalBrowserSnapshot(address = initialUrl.orEmpty(), url = initialUrl))
    val state = mutable.asStateFlow()
    private var pendingUrl = initialUrl
    private var pendingCommand: LocalBrowserCommand? = null
    private var attachment = 0L
    private var attached = false
    fun editAddress(value: String) { if (!state.value.closed) mutable.value = state.value.copy(address = value) }
    fun editing(value: Boolean) { if (!state.value.closed) mutable.value = state.value.copy(editing = value) }
    fun submitAddress(): Boolean {
        if (state.value.closed) return false
        val url = resolver.resolve(state.value.address) ?: return false
        load(url); return true
    }
    fun load(url: String) {
        if (state.value.closed) return
        pendingUrl = url
        mutable.value = state.value.copy(address = url, error = null, workRevision = state.value.workRevision + 1)
    }
    fun request(command: LocalBrowserCommand) {
        if (state.value.closed) return
        pendingCommand = command
        mutable.value = state.value.copy(workRevision = state.value.workRevision + 1)
    }
    /** Latest load and latest command, each consumed once; empty reads do not emit state. */
    fun takeWork(): LocalBrowserWork {
        val result = LocalBrowserWork(pendingUrl, pendingCommand)
        pendingUrl = null; pendingCommand = null; return result
    }
    /** A fresh WebView restores the committed URL; its old history does not carry across remounts. */
    fun attach(): Long {
        check(!state.value.closed)
        attachment++; attached = true
        if (pendingUrl == null) pendingUrl = state.value.url
        mutable.value = state.value.copy(canGoBack = false, canGoForward = false, loading = false, progress = 0f)
        return attachment
    }
    fun detach(token: Long) { if (token == attachment) { attached = false; attachment++ } }
    fun remote(token: Long, value: LocalBrowserSnapshot) {
        if (current(token)) mutable.value = value.copy(workRevision = state.value.workRevision, closed = false)
    }
    private fun current(token: Long) = attached && token == attachment && !state.value.closed
    fun location(token: Long, url: String?, title: String?, back: Boolean, forward: Boolean) {
        if (!current(token)) return
        val value = state.value
        mutable.value = value.copy(url = url, title = title?.takeIf { it.isNotEmpty() } ?: value.title,
            address = if (value.editing || url == null) value.address else url, canGoBack = back, canGoForward = forward)
    }
    fun started(token: Long) { if (current(token)) mutable.value = state.value.copy(loading = true, progress = 0f, error = null) }
    fun progress(token: Long, value: Float) {
        if (current(token) && value.isFinite()) mutable.value = state.value.copy(progress = value.coerceIn(0f, 1f))
    }
    fun finished(token: Long) { if (current(token)) mutable.value = state.value.copy(loading = false, progress = 1f) }
    fun failed(token: Long, message: String) { if (current(token)) mutable.value = state.value.copy(loading = false, progress = 0f, error = message) }
    fun stopped(token: Long, stillLoading: Boolean) { if (current(token)) mutable.value = state.value.copy(loading = stillLoading) }
    fun close() {
        attached = false; attachment++; pendingUrl = null; pendingCommand = null
        mutable.value = state.value.copy(closed = true, loading = false, editing = false, canGoBack = false, canGoForward = false)
    }
}

/** Account/team/Mac/workspace scope prevents collisions in the aggregated workspace UI. */
internal class LocalBrowserStore(private val defaultUrl: String? = "https://duckduckgo.com/",
    private val makeId: () -> String = { UUID.randomUUID().toString() }) {
    private val surfaces = mutableMapOf<LocalBrowserKey, LocalBrowserSurface>()
    private val restores = mutableSetOf<LocalBrowserKey>()
    fun active(key: LocalBrowserKey): LocalBrowserSurface? = surfaces[key]
    fun open(key: LocalBrowserKey) = surfaces.getOrPut(key) { LocalBrowserSurface(makeId(), defaultUrl) }
    fun close(key: LocalBrowserKey) { restores.remove(key); surfaces.remove(key)?.close() }
    fun requestRestore(key: LocalBrowserKey) { restores += key }
    fun consumeRestore(key: LocalBrowserKey): LocalBrowserSurface? = if (restores.remove(key)) open(key) else null
    fun retainAccount(accountId: String, teamId: String?) {
        surfaces.keys.filter { it.accountId != accountId || it.teamId != teamId }.forEach(::close)
        restores.removeAll { it.accountId != accountId || it.teamId != teamId }
    }
    fun retainComputers(computerIds: Set<String>) {
        surfaces.keys.filter { it.computerId !in computerIds }.forEach(::close)
        restores.removeAll { it.computerId !in computerIds }
    }
    fun retainWorkspaces(computerId: String, workspaceIds: Set<String>) {
        surfaces.keys.filter { it.computerId == computerId && it.workspaceId !in workspaceIds }.forEach(::close)
        restores.removeAll { it.computerId == computerId && it.workspaceId !in workspaceIds }
    }
    fun clear() { surfaces.values.forEach { it.close() }; surfaces.clear(); restores.clear() }
}
