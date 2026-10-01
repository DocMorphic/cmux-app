package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

internal data class SshCmuxTree(val generation: String?, val registry: String?, val revision: Long?,
    val workspaces: List<SshCmuxWorkspace>) {
    val tabs get() = workspaces.flatMap { it.tabs }
}
internal data class SshCmuxWorkspace(val id: Int, val key: String?, val resource: String?, val name: String,
    val active: Boolean, val screens: List<SshCmuxScreen>) {
    val tabs get() = screens.flatMap { it.panes }.flatMap { it.tabs }
}
internal data class SshCmuxScreen(val id: Int, val resource: String?, val name: String?, val active: Boolean,
    val activePane: Int?, val panes: List<SshCmuxPane>, val layout: SshCmuxLayout?)
internal data class SshCmuxPane(val id: Int, val resource: String?, val name: String?, val activeTab: Int?,
    val dead: Boolean, val tabs: List<SshCmuxTab>)
internal data class SshCmuxTab(val surface: Int, val pane: Int, val screen: Int, val resource: String?, val kind: String,
    val content: String?, val terminal: String?, val terminalId: String?, val name: String?, val title: String,
    val columns: Int?, val rows: Int?, val dead: Boolean, val url: String?, val browserStatus: String?,
    val browserError: String?, val framesStalled: Boolean) {
    val isTerminal get() = kind == "pty"
    val isBrowser get() = kind == "browser"
}
internal sealed interface SshCmuxLayout {
    data class Leaf(val pane: Int) : SshCmuxLayout
    data class Split(val id: Int?, val right: Boolean, val ratio: Double, val a: SshCmuxLayout, val b: SshCmuxLayout) : SshCmuxLayout
    data class Stack(val panes: List<Int>, val expanded: Int) : SshCmuxLayout
    /** A future layout can still expose its ordered panes without guessing its geometry. */
    data class Unknown(val type: String) : SshCmuxLayout
}

/** Durable selection, scoped by the caller to its account/host and here to the
 * remote session/registry. No title/index matching and no numeric-ID fallback
 * across owner generations. A terminal may have more than one tab view. */
internal data class SshCmuxSelection(val session: String, val registry: String?, val generation: String?,
    val workspace: Int, val workspaceKey: String?, val workspaceResource: String?, val surface: Int,
    val tabResource: String?, val terminalResource: String?, val terminalId: String?) {
    fun resolve(session: String, tree: SshCmuxTree): Pair<SshCmuxWorkspace, SshCmuxTab>? {
        if (this.session != session || registry != null && registry != tree.registry) return null
        val sameOwner = generation != null && generation == tree.generation
        val workspace = tree.workspaces.singleOrNull {
            when {
                workspaceKey != null -> it.key == workspaceKey && (workspaceResource == null || it.resource == workspaceResource)
                workspaceResource != null -> it.resource == workspaceResource
                else -> sameOwner && it.id == this.workspace
            }
        } ?: return null
        val tab = workspace.tabs.filter { it.isTerminal && !it.dead }.singleOrNull {
            (tabResource == null || it.resource == tabResource) && when {
                terminalResource != null -> it.terminal == terminalResource
                terminalId != null -> it.terminalId == terminalId
                else -> sameOwner && it.surface == surface
            }
        } ?: return null
        return workspace to tab
    }
    companion object {
        fun capture(session: String, tree: SshCmuxTree, workspace: SshCmuxWorkspace, tab: SshCmuxTab): SshCmuxSelection {
            require(workspace in tree.workspaces && tab in workspace.tabs && tab.isTerminal && !tab.dead)
            return SshCmuxSelection(session, tree.registry, tree.generation, workspace.id, workspace.key, workspace.resource,
                tab.surface, tab.resource, tab.terminal, tab.terminalId)
        }
    }
}

/** Strict known fields, additive unknown fields/kinds, bounded nesting/counts.
 * Keep the full ordered screen/pane/tab hierarchy; browser and unknown tabs
 * must never be treated as PTYs by an index or default-kind shortcut. */
internal object SshCmuxInventory {
    fun parse(value: JSONObject): SshCmuxTree = Parser().parse(value)
    private class Parser {
        private var remaining = 10000
        private val workspaceIDs = mutableSetOf<Int>()
        private val workspaceKeys = mutableSetOf<String>()
        private val workspaceResources = mutableSetOf<String>()
        private val screens = mutableSetOf<Int>()
        private val panes = mutableSetOf<Int>()
        private val surfaces = mutableSetOf<Int>()
        private val tabResources = mutableSetOf<String>()
        private fun count() { require(--remaining >= 0) { "cmux-tui inventory exceeded its item limit" } }
        private fun objects(value: JSONObject, key: String, required: Boolean = false): List<JSONObject> {
            val raw = value.opt(key)
            if (raw == null || raw === JSONObject.NULL) { require(!required) { "Missing cmux-tui $key" }; return emptyList() }
            require(raw is JSONArray && raw.length() <= remaining) { "Invalid cmux-tui $key" }
            return (0 until raw.length()).map { raw.getJSONObject(it) }
        }
        private fun number(value: JSONObject, key: String, max: Long = Int.MAX_VALUE.toLong(), required: Boolean = true): Long? {
            val raw = value.opt(key)
            if (raw == null || raw === JSONObject.NULL) { require(!required) { "Missing cmux-tui $key" }; return null }
            require(raw is Int || raw is Long) { "Invalid cmux-tui $key" }
            return (raw as Number).toLong().also { require(it in 0..max) { "Invalid cmux-tui $key" } }
        }
        private fun id(value: JSONObject, key: String = "id") = number(value, key)!!.toInt()
        private fun unique(value: JSONObject, set: MutableSet<Int>): Int = id(value).also { require(set.add(it)) { "Duplicate cmux-tui ID" } }
        private fun string(value: JSONObject, key: String, required: Boolean = false): String? {
            val raw = value.opt(key)
            if (raw == null || raw === JSONObject.NULL) { require(!required) { "Missing cmux-tui $key" }; return null }
            require(raw is String) { "Invalid cmux-tui $key" }; return raw
        }
        private fun identity(value: JSONObject, key: String, seen: MutableSet<String>? = null): String? = string(value, key)?.also {
            require(it.isNotEmpty() && it.none { c -> c.isISOControl() }) { "Invalid cmux-tui identity" }
            if (seen != null) require(seen.add(it)) { "Duplicate cmux-tui identity" }
        }
        private fun flag(value: JSONObject, key: String, fallback: Boolean = false): Boolean {
            val raw = value.opt(key)
            if (raw == null || raw === JSONObject.NULL) return fallback
            require(raw is Boolean) { "Invalid cmux-tui $key" }; return raw
        }
        fun parse(value: JSONObject): SshCmuxTree {
            val workspaces = objects(value, "workspaces", required = true).map { workspace ->
                count()
                SshCmuxWorkspace(unique(workspace, workspaceIDs), identity(workspace, "key", workspaceKeys),
                    identity(workspace, "resource_id", workspaceResources), string(workspace, "name", true)!!, flag(workspace, "active"),
                    objects(workspace, "screens").map(::screen))
            }
            return SshCmuxTree(identity(value, "generation"), identity(value, "registry_id"),
                number(value, "workspace_revision", Long.MAX_VALUE, false), workspaces)
        }
        private fun screen(value: JSONObject): SshCmuxScreen {
            count(); val id = unique(value, screens)
            val rows = objects(value, "panes").map { pane(it, id) }
            val active = number(value, "active_pane", required = false)?.toInt()
            require(active == null || rows.any { it.id == active }) { "Unknown active cmux-tui pane" }
            val rawLayout = value.opt("layout")
            val layout = if (rawLayout == null || rawLayout === JSONObject.NULL) null else {
                require(rawLayout is JSONObject); layout(rawLayout, rows.map { it.id }.toSet(), mutableSetOf(), 0)
            }
            return SshCmuxScreen(id, identity(value, "resource_id"), string(value, "name"), flag(value, "active"), active, rows, layout)
        }
        private fun pane(value: JSONObject, screen: Int): SshCmuxPane {
            count(); val id = unique(value, panes)
            return SshCmuxPane(id, identity(value, "resource_id"), string(value, "name"),
                number(value, "active_tab", required = false)?.toInt(), flag(value, "dead"), objects(value, "tabs").map { tab(it, id, screen) })
        }
        private fun tab(value: JSONObject, pane: Int, screen: Int): SshCmuxTab {
            count(); val surface = id(value, "surface"); require(surfaces.add(surface)) { "Duplicate cmux-tui surface" }
            val size = value.opt("size")
            val grid = if (size == null || size === JSONObject.NULL) null else {
                require(size is JSONObject)
                val columns = number(size, "cols", 65535)!!.toInt(); val rows = number(size, "rows", 65535)!!.toInt()
                require(columns > 0 && rows > 0); columns to rows
            }
            return SshCmuxTab(surface, pane, screen, identity(value, "tab_resource_id", tabResources), string(value, "kind", true)!!,
                identity(value, "content_resource_id"), identity(value, "terminal_resource_id"), identity(value, "terminal_id"),
                string(value, "name"), string(value, "title") ?: "", grid?.first, grid?.second, flag(value, "dead"), string(value, "url"),
                string(value, "browser_status"), string(value, "browser_error"), flag(value, "browser_frames_stalled"))
        }
        private fun layout(value: JSONObject, allowed: Set<Int>, used: MutableSet<Int>, depth: Int): SshCmuxLayout {
            count(); require(depth < 64) { "cmux-tui layout is too deep" }
            fun pane(id: Int): Int { require(id in allowed && used.add(id)) { "Invalid cmux-tui layout pane" }; return id }
            return when (val type = string(value, "type", true)!!) {
                "leaf" -> SshCmuxLayout.Leaf(pane(id(value, "pane")))
                "split" -> {
                    val direction = string(value, "dir", true); require(direction == "right" || direction == "down")
                    val rawRatio = value.get("ratio"); require(rawRatio is Number)
                    val ratio = rawRatio.toDouble(); require(ratio.isFinite() && ratio > 0 && ratio < 1)
                    SshCmuxLayout.Split(number(value, "split", required = false)?.toInt(), direction == "right", ratio,
                        layout(value.getJSONObject("a"), allowed, used, depth + 1), layout(value.getJSONObject("b"), allowed, used, depth + 1))
                }
                "stack" -> {
                    val ids = value.getJSONArray("panes"); require(ids.length() in 1..remaining)
                    val rows = (0 until ids.length()).map { count(); pane(id(JSONObject().put("id", ids.get(it)))) }
                    val expanded = id(value, "expanded"); require(expanded in rows)
                    SshCmuxLayout.Stack(rows, expanded)
                }
                else -> SshCmuxLayout.Unknown(type)
            }
        }
    }
}

internal suspend fun SshCmuxControl.listWorkspaces(): SshCmuxTree {
    val info = checkNotNull(server) { "cmux-tui handshake is required" }
    val tree = SshCmuxInventory.parse(request("list-workspaces"))
    check(info.generation == null || tree.generation == info.generation) { "cmux-tui owner changed during inventory" }
    return tree
}
