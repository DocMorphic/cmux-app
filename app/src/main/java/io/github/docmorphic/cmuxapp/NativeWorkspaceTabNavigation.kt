package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

internal data class NativeWorkspacePendingTab(val login: String, val key: NativeWorkspaceTabKey,
    val tab: NativeWorkspaceTab?, val id: String = UUID.randomUUID().toString())
internal data class NativeWorkspaceTabChoice(val pane: NativeWorkspacePane?, val localBrowser: Boolean = false)

internal fun workspaceTabDisplay(login: String?, owner: NativeTeamScope?, macs: List<NativeCredentialStore.PairedMac>,
    code: String, workspace: NativeWorkspace?, terminal: NativeTerminal?, browser: NativeBrowser?, surface: NativeSurface?,
    local: LocalBrowserDestination?): Pair<NativeWorkspaceTabKey, NativeWorkspaceTab?>? {
    if (login == null) return null
    if (local != null) {
        val mac = macs.singleOrNull { it.ownsOrigin(local.key.computerId) } ?: return null
        return workspaceTabKey(login, owner, mac, local.workspace.id)?.let { it to NativeWorkspaceTab.LocalBrowser }
    }
    if (workspace == null) return null
    val mac = macs.singleOrNull { it.code == code } ?: return null
    val pane = when {
        surface != null -> NativeWorkspacePane(surface = surface)
        browser != null -> NativeWorkspacePane(browser = browser)
        terminal != null -> NativeWorkspacePane(terminal = terminal)
        else -> null
    }
    return workspaceTabKey(login, owner, mac, workspace.id)?.let { it to pane?.tab() }
}

/** Main-thread selection bookkeeping. A displayed fallback cannot overwrite a waiting tab. */
internal class NativeWorkspaceTabNavigation(
    private val read: (String, NativeWorkspaceTabKey) -> NativeWorkspaceTab?,
    private val write: (String, NativeWorkspaceTabKey, NativeWorkspaceTab) -> Unit,
    private val remove: (String, NativeWorkspaceTabKey) -> Unit
) {
    constructor(store: NativeCredentialStore) : this(store::lastWorkspaceTab,
        { login, key, tab -> store.rememberWorkspaceTab(login, key, tab); Unit },
        { login, key -> store.forgetWorkspaceTab(login, key); Unit })
    private val mutablePending = MutableStateFlow<NativeWorkspacePendingTab?>(null)
    val pending = mutablePending.asStateFlow()
    private var interim: NativeWorkspaceTab? = null
    private var recorded: Triple<String, NativeWorkspaceTabKey, NativeWorkspaceTab>? = null
    private var discovered: Pair<NativeWorkspaceTabKey, List<NativeBrowser>>? = null
    private var observed: Pair<String, NativeWorkspaceTabKey>? = null
    fun remembered(login: String, key: NativeWorkspaceTabKey) = read(login, key)
    fun cancel() { mutablePending.value = null; interim = null }
    fun clear() { cancel(); recorded = null; discovered = null; observed = null }
    fun browsers(key: NativeWorkspaceTabKey): List<NativeBrowser>? = discovered?.takeIf { it.first == key }?.second
    fun discover(login: String, key: NativeWorkspaceTabKey, browsers: List<NativeBrowser>): Boolean {
        if (observed != (login to key)) return false
        discovered = key to browsers
        return true
    }
    fun awaitDefault(login: String, key: NativeWorkspaceTabKey) {
        if (pending.value != null) return
        interim = null
        mutablePending.value = NativeWorkspacePendingTab(login, key, null)
    }
    fun refreshInterim(ticket: NativeWorkspacePendingTab, tab: NativeWorkspaceTab?) {
        if (pending.value == ticket) interim = tab
    }
    /** Browser discovery is independent of workspace surface snapshots. Retain only the visible owner. */
    fun withDiscoveredBrowsers(key: NativeWorkspaceTabKey, workspace: NativeWorkspace): NativeWorkspace {
        val browsers = discovered?.takeIf { it.first == key && workspace.id == key.workspaceId }?.second ?: return workspace
        return workspace.copy(browsers = workspace.browsers + browsers.filter { browser -> workspace.browsers.none { it.id == browser.id } })
    }
    fun explicit(login: String, key: NativeWorkspaceTabKey, tab: NativeWorkspaceTab) {
        cancel(); record(login, key, tab)
    }
    fun forget(login: String, key: NativeWorkspaceTabKey) {
        cancel(); remove(login, key); recorded = null
    }
    private fun record(login: String, key: NativeWorkspaceTabKey, tab: NativeWorkspaceTab) {
        val current = Triple(login, key, tab)
        if (recorded != current) { write(login, key, tab); recorded = current }
    }
    /** Called after composition. Explicit picker actions call explicit(), including same-pane picks. */
    fun observe(login: String?, key: NativeWorkspaceTabKey?, tab: NativeWorkspaceTab?) {
        observed = if (login != null && key != null) login to key else null
        if (login == null || key == null) { cancel(); discovered = null; return }
        if (discovered?.first != key) discovered = null
        val waiting = pending.value
        if (waiting != null && waiting.login == login && waiting.key == key && (tab == null || tab == interim)) return
        if (waiting != null) cancel()
        if (tab != null) record(login, key, tab)
    }
    fun open(login: String, key: NativeWorkspaceTabKey, workspace: NativeWorkspace): NativeWorkspaceTabChoice {
        cancel()
        val remembered = read(login, key)
        val result = remembered?.let { restoreWorkspaceTab(workspace, it) }
        val choice = if (result?.status == NativeWorkspaceRestoreStatus.RESTORED)
            NativeWorkspaceTabChoice(result.pane, result.localBrowser) else NativeWorkspaceTabChoice(workspace.defaultPane())
        if (result?.status == NativeWorkspaceRestoreStatus.WAITING) {
            interim = choice.pane?.tab()
            mutablePending.value = NativeWorkspacePendingTab(login, key, checkNotNull(remembered))
        } else if (choice.pane == null && !choice.localBrowser) awaitDefault(login, key)
        else choice.tab()?.let { record(login, key, it) }
        return choice
    }
    fun resolve(ticket: NativeWorkspacePendingTab, workspace: NativeWorkspace,
        browsers: List<NativeBrowser>?): NativeWorkspaceTabChoice? {
        if (pending.value != ticket || workspace.id != ticket.key.workspaceId) return null
        if (browsers != null) discovered = ticket.key to browsers
        if (ticket.tab == null) {
            val pane = workspace.defaultPane(browsers.orEmpty()) ?: return null
            cancel(); record(ticket.login, ticket.key, pane.tab())
            return NativeWorkspaceTabChoice(pane)
        }
        val result = restoreWorkspaceTab(workspace, ticket.tab, browsers)
        if (result.status == NativeWorkspaceRestoreStatus.WAITING) return null
        val choice = if (result.status == NativeWorkspaceRestoreStatus.RESTORED)
            NativeWorkspaceTabChoice(result.pane, result.localBrowser) else NativeWorkspaceTabChoice(workspace.defaultPane(browsers.orEmpty()))
        cancel()
        choice.tab()?.let { record(ticket.login, ticket.key, it) }
        return choice
    }
    private fun NativeWorkspaceTabChoice.tab() = if (localBrowser) NativeWorkspaceTab.LocalBrowser else pane?.tab()
}

/** Invalid discovery must not look like confirmed absence. Ownership is checked before publication. */
internal fun parseWorkspaceBrowserPanels(value: JSONObject, workspaceId: String): List<NativeBrowser> {
    val panels = value.optJSONArray("panels") ?: error("Invalid browser inventory")
    require(panels.length() <= 4096) { "Invalid browser inventory" }
    val ids = mutableSetOf<String>()
    return List(panels.length()) { index ->
        val panel = panels.getJSONObject(index)
        val id = panel.opt("panel_id") as? String ?: error("Invalid browser inventory")
        require(id.isNotBlank() && ids.add(id) && panel.opt("workspace_id") == workspaceId) { "Invalid browser inventory" }
        for (field in listOf("page_width", "page_height")) {
            val size = (panel.opt(field) as? Number)?.toDouble()
            require(size != null && size.isFinite() && size >= 0) { "Invalid browser inventory" }
        }
        for (field in listOf("can_go_back", "can_go_forward", "is_loading"))
            require(panel.opt(field) is Boolean) { "Invalid browser inventory" }
        for (field in listOf("url", "title"))
            require(!panel.has(field) || panel.isNull(field) || panel.opt(field) is String) { "Invalid browser inventory" }
        NativeBrowser(id, panel.opt("title") as? String ?: "")
    }
}
