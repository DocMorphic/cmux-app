package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

internal enum class NativeWorkspaceTabKind(val wire: String) {
    TERMINAL("terminal"), MAC_SURFACE("macSurface"), BROWSER_STREAM("browserStream"),
    SIMULATOR_STREAM("simulatorStream"), LOCAL_BROWSER("localBrowser")
}

internal data class NativeWorkspaceTab(val kind: NativeWorkspaceTabKind, val id: String) {
    init { require(id.isNotBlank() && id.length <= 4096 && (kind != NativeWorkspaceTabKind.LOCAL_BROWSER || id == "local")) }
    companion object { val LocalBrowser = NativeWorkspaceTab(NativeWorkspaceTabKind.LOCAL_BROWSER, "local") }
}

internal fun NativeWorkspacePane.tab(): NativeWorkspaceTab = when {
    surface?.simulator != null -> NativeWorkspaceTab(NativeWorkspaceTabKind.SIMULATOR_STREAM, surface.id)
    browser != null -> NativeWorkspaceTab(NativeWorkspaceTabKind.BROWSER_STREAM, browser.id)
    surface != null -> NativeWorkspaceTab(NativeWorkspaceTabKind.MAC_SURFACE, surface.id)
    else -> NativeWorkspaceTab(NativeWorkspaceTabKind.TERMINAL, checkNotNull(terminal).id)
}

/** Independent of connection routes, aggregate row IDs and account refresh generations. */
internal data class NativeWorkspaceTabKey(val accountId: String, val teamId: String?,
    val computerId: String, val workspaceId: String) {
    val encoded: String get() = JSONArray().put(accountId).put(teamId ?: JSONObject.NULL)
        .put(computerId).put(workspaceId).toString()
}

internal fun workspaceTabKey(login: String?, owner: NativeTeamScope?, mac: NativeCredentialStore.PairedMac,
    workspaceId: String): NativeWorkspaceTabKey? {
    val scope = localBrowserKey(login, owner, mac, workspaceId) ?: return null
    val buildTag = mac.instanceTag?.trim()?.takeIf { it.isNotEmpty() }
    val computer = if (mac.deviceId.isNotBlank()) JSONArray().put("device")
        .put(canonicalMacDeviceId(mac.deviceId)).put(buildTag ?: JSONObject.NULL)
    else JSONArray().put("anonymous").put(mac.origin)
    return NativeWorkspaceTabKey(scope.accountId, scope.teamId, computer.toString(), workspaceId)
}

/** Pure bounded map; the credential-store adapter commits only a changed map. */
internal class NativeWorkspaceLastTabs(initial: JSONObject? = null) {
    private data class Entry(val kind: String, val id: String, val sequence: Long)
    private val entries = linkedMapOf<String, Entry>()
    init {
        initial?.keys()?.asSequence()?.forEach { key ->
            val value = initial.optJSONObject(key) ?: return@forEach
            val kind = value.opt("kind") as? String ?: return@forEach
            val id = value.opt("tab_id") as? String ?: return@forEach
            val number = value.opt("sequence")
            val sequence = when (number) { is Long -> number; is Int -> number.toLong(); else -> return@forEach }
            if (key.length <= 16384 && kind.isNotBlank() && kind.length <= 128 && id.isNotBlank() && id.length <= 4096 && sequence >= 0)
                entries[key] = Entry(kind, id, sequence)
        }
        prune()
    }
    fun get(key: NativeWorkspaceTabKey): NativeWorkspaceTab? = entries[key.encoded]?.let { entry ->
        val kind = NativeWorkspaceTabKind.entries.firstOrNull { it.wire == entry.kind } ?: return null
        runCatching { NativeWorkspaceTab(kind, entry.id) }.getOrNull()
    }
    /** Unchanged tabs neither write nor become more recent. */
    fun set(key: NativeWorkspaceTabKey, tab: NativeWorkspaceTab): Boolean {
        val encoded = key.encoded
        require(encoded.length <= 16384)
        val previous = entries[encoded]
        if (previous?.kind == tab.kind.wire && previous.id == tab.id) return false
        var latest = entries.values.maxOfOrNull { it.sequence } ?: 0L
        if (latest == Long.MAX_VALUE) {
            oldestFirst().forEachIndexed { index, entry -> entries[entry.key] = entry.value.copy(sequence = index.toLong()) }
            latest = entries.size.toLong()
        }
        entries[encoded] = Entry(tab.kind.wire, tab.id, latest + 1)
        prune()
        return true
    }
    fun json() = JSONObject().also { result -> entries.forEach { (key, entry) ->
        result.put(key, JSONObject().put("kind", entry.kind).put("tab_id", entry.id).put("sequence", entry.sequence))
    } }
    private fun oldestFirst() = entries.entries.sortedWith(compareBy({ it.value.sequence }, { it.key }))
    private fun prune() { oldestFirst().take((entries.size - MAX_ENTRIES).coerceAtLeast(0)).forEach { entries.remove(it.key) } }
    companion object {
        const val MAX_ENTRIES = 512
        const val STORAGE_KEY = "workspace_last_tabs_v1"
    }
}

internal enum class NativeWorkspaceRestoreStatus { RESTORED, WAITING, UNAVAILABLE }
internal data class NativeWorkspaceRestoreResult(val status: NativeWorkspaceRestoreStatus,
    val pane: NativeWorkspacePane? = null, val localBrowser: Boolean = false)

/** null discovery means no successful browser inventory yet; an empty list confirms an empty inventory. */
internal fun restoreWorkspaceTab(workspace: NativeWorkspace, remembered: NativeWorkspaceTab,
    discoveredBrowsers: List<NativeBrowser>? = null, activePane: NativeWorkspacePane? = null): NativeWorkspaceRestoreResult {
    fun restored(pane: NativeWorkspacePane) = NativeWorkspaceRestoreResult(NativeWorkspaceRestoreStatus.RESTORED, pane)
    val waiting = NativeWorkspaceRestoreResult(NativeWorkspaceRestoreStatus.WAITING)
    val unavailable = NativeWorkspaceRestoreResult(NativeWorkspaceRestoreStatus.UNAVAILABLE)
    val active = activePane?.tab()
    if (active?.kind == NativeWorkspaceTabKind.SIMULATOR_STREAM || active?.kind == NativeWorkspaceTabKind.BROWSER_STREAM)
        return if (active == remembered) restored(checkNotNull(activePane)) else unavailable
    val missing = if (workspace.terminals.isEmpty() && workspace.surfaces.isEmpty()) waiting else unavailable
    return when (remembered.kind) {
        NativeWorkspaceTabKind.TERMINAL -> {
            val terminal = workspace.terminals.firstOrNull { it.id == remembered.id } ?: return missing
            if (!terminal.isReady && workspace.terminals.any { it.isReady }) waiting
            else restored(NativeWorkspacePane(terminal = terminal))
        }
        NativeWorkspaceTabKind.MAC_SURFACE -> {
            val surface = workspace.surfaces.firstOrNull { it.id == remembered.id && it.kind != "terminal" } ?: return missing
            val simulator = workspace.simulators.firstOrNull { it.panelId == surface.id }
            val browser = discoveredBrowsers?.firstOrNull { it.id == surface.id }
            restored(when {
                simulator != null -> NativeWorkspacePane(surface = simulator.surface().copy(isFocused = surface.isFocused))
                browser != null -> NativeWorkspacePane(browser = browser)
                else -> NativeWorkspacePane(surface = surface)
            })
        }
        NativeWorkspaceTabKind.BROWSER_STREAM -> {
            val browser = discoveredBrowsers?.firstOrNull { it.id == remembered.id }
            if (browser != null) restored(NativeWorkspacePane(browser = browser))
            else if (discoveredBrowsers == null || workspace.surfaces.any { it.id == remembered.id && it.kind == "browser" }) waiting
            else unavailable
        }
        NativeWorkspaceTabKind.SIMULATOR_STREAM -> workspace.simulators.firstOrNull { it.panelId == remembered.id }
            ?.let { restored(NativeWorkspacePane(surface = it.surface())) } ?: unavailable
        NativeWorkspaceTabKind.LOCAL_BROWSER -> NativeWorkspaceRestoreResult(NativeWorkspaceRestoreStatus.RESTORED, localBrowser = true)
    }
}

/** A transient restore intent is owned by exactly one open. Explicit picks disarm it. */
internal class NativeWorkspaceTabRestoration {
    private var pending: Pair<NativeWorkspaceTabKey, NativeWorkspaceTab>? = null
    fun begin(key: NativeWorkspaceTabKey, tab: NativeWorkspaceTab?) { pending = tab?.let { key to it } }
    fun cancel() { pending = null }
    fun isWaiting(key: NativeWorkspaceTabKey) = pending?.first == key
    fun resolve(key: NativeWorkspaceTabKey, workspace: NativeWorkspace, discoveredBrowsers: List<NativeBrowser>? = null,
        activePane: NativeWorkspacePane? = null): NativeWorkspaceRestoreResult? {
        val captured = pending?.takeIf { it.first == key && key.workspaceId == workspace.id } ?: return null
        val result = restoreWorkspaceTab(workspace, captured.second, discoveredBrowsers, activePane)
        if (result.status != NativeWorkspaceRestoreStatus.WAITING) pending = null
        return result
    }
}
