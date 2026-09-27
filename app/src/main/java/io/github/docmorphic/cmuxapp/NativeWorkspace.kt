package io.github.docmorphic.cmuxapp

import org.json.JSONObject

internal data class NativeWorkspace(
    val id: String, val title: String, val terminals: List<NativeTerminal>,
    val directory: String?, val hasUnread: Boolean, val lastActivityAt: Double?,
    val windowId: String?, val isPinned: Boolean, val browsers: List<NativeBrowser>,
    val groupId: String?, val preview: String?, val color: String?, val description: String? = null
)
internal data class NativeGroup(val id: String, val name: String, val isCollapsed: Boolean, val isPinned: Boolean)
internal sealed interface WorkspaceListEntry {
    val source: NativeFeedSource
    val key: String
    data class Header(override val source: NativeFeedSource, val group: NativeGroup) : WorkspaceListEntry {
        override val key = source.mac.origin + ":group:" + group.id
    }
    data class Workspace(override val source: NativeFeedSource, val workspace: NativeWorkspace) : WorkspaceListEntry {
        override val key = source.mac.origin + ":workspace:" + workspace.id
    }
}
internal data class NativeWorkspaceRoute(
    val origin: String, val workspaceId: String, val terminalId: String? = null,
    val browserId: String? = null, val changes: Boolean = false,
    val id: String = java.util.UUID.randomUUID().toString()
)
internal fun workspaceSearchId(source: NativeFeedSource, workspace: NativeWorkspace) =
    source.mac.origin + ":workspace:" + workspace.id

internal fun workspaceEntries(sources: List<NativeFeedSource>, matches: Set<String>,
    filtering: Boolean, unreadOnly: Boolean, expandedGroups: Set<String>): List<WorkspaceListEntry> = buildList {
    sources.forEach { source ->
        val matching = source.workspaces.filter { (!unreadOnly || it.hasUnread) && workspaceSearchId(source, it) in matches }
        fun addRows(rows: List<NativeWorkspace>) = rows.forEach { add(WorkspaceListEntry.Workspace(source, it)) }
        if (source.groups.isEmpty() || filtering || unreadOnly) addRows(matching)
        else {
            addRows(matching.filter { row -> source.groups.none { it.id == row.groupId } })
            source.groups.forEach { group ->
                val header = WorkspaceListEntry.Header(source, group)
                add(header)
                if (group.isCollapsed == (header.key in expandedGroups)) addRows(matching.filter { it.groupId == group.id })
            }
        }
    }
}
internal data class NativeTerminal(val id: String, val title: String)
internal data class NativeBrowser(val id: String, val title: String)

internal fun parseWorkspaces(value: JSONObject): List<NativeWorkspace> {
    val array = value.optJSONArray("workspaces") ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val workspace = array.optJSONObject(index) ?: continue
            val id = workspace.optString("id")
            if (id.isBlank()) continue
            val terminals = mutableListOf<NativeTerminal>()
            val browsers = mutableListOf<NativeBrowser>()
            val items = workspace.optJSONArray("terminals")
            if (items != null) for (terminalIndex in 0 until items.length()) {
                val terminal = items.optJSONObject(terminalIndex) ?: continue
                val terminalId = terminal.optString("id")
                if (terminalId.isNotBlank()) terminals += NativeTerminal(terminalId, terminal.optString("title"))
            }
            val surfaces = workspace.optJSONArray("surfaces")
            if (surfaces != null) for (surfaceIndex in 0 until surfaces.length()) {
                val surface = surfaces.optJSONObject(surfaceIndex) ?: continue
                if (surface.optString("kind") == "browser") {
                    val surfaceId = surface.optString("surface_id")
                    if (surfaceId.isNotBlank()) browsers += NativeBrowser(surfaceId, surface.optString("title"))
                }
            }
            add(NativeWorkspace(
                id, workspace.optString("title", "Workspace"), terminals,
                workspace.optString("current_directory").takeIf { it.isNotBlank() && it != "null" },
                workspace.optBoolean("has_unread"),
                workspace.optDouble("last_activity_at").takeIf { it > 0 },
                workspace.optString("window_id").takeIf { it.isNotBlank() && it != "null" },
                workspace.optBoolean("is_pinned"), browsers,
                workspace.optString("group_id").takeIf { it.isNotBlank() && it != "null" },
                workspace.optString("preview").takeIf { it.isNotBlank() && it != "null" },
                workspace.optString("custom_color").takeIf { it.startsWith('#') },
                workspace.optString("description").takeIf { it.isNotBlank() && it != "null" }
            ))
        }
    }
}

internal fun parseGroups(value: JSONObject): List<NativeGroup> {
    val array = value.optJSONArray("groups") ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isNotBlank()) add(NativeGroup(id, item.optString("name", "Group"),
                item.optBoolean("is_collapsed"), item.optBoolean("is_pinned")))
        }
    }
}
