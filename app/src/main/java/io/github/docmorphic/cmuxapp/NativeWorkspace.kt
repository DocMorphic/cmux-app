package io.github.docmorphic.cmuxapp

import org.json.JSONObject

internal data class NativeWorkspace(
    val id: String, val title: String, val terminals: List<NativeTerminal>,
    val directory: String?, val hasUnread: Boolean, val lastActivityAt: Double?,
    val windowId: String?, val isPinned: Boolean, val browsers: List<NativeBrowser>,
    val groupId: String?, val preview: String?, val color: String?, val description: String? = null,
    val unreadCount: Long? = null
) {
    val unreadState get() = NativeWorkspaceUnread(hasUnread, unreadCount ?: if (hasUnread) null else 0L)
}
internal data class NativeGroup(
    val id: String, val name: String, val isCollapsed: Boolean, val isPinned: Boolean,
    val anchorWorkspaceId: String? = null, val isEmpty: Boolean = anchorWorkspaceId == null,
    val iconSymbol: String? = null
) {
    val liveAnchorWorkspaceId get() = anchorWorkspaceId.takeUnless { isEmpty }
}
internal sealed interface WorkspaceListEntry {
    val source: NativeFeedSource
    val key: String
    data class Header(override val source: NativeFeedSource, val group: NativeGroup,
        val unread: NativeWorkspaceUnread = NativeWorkspaceUnread.Read) : WorkspaceListEntry {
        val hasUnread get() = unread.isUnread
        override val key = source.mac.origin + ":group:" + group.id
    }
    data class Workspace(override val source: NativeFeedSource, val workspace: NativeWorkspace, val indented: Boolean = false) : WorkspaceListEntry {
        override val key = source.mac.origin + ":workspace:" + workspace.id
    }
    data class Footer(override val source: NativeFeedSource, val group: NativeGroup) : WorkspaceListEntry {
        override val key = source.mac.origin + ":footer:" + group.id
    }
}
internal data class NativeWorkspaceRoute(
    val origin: String, val workspaceId: String, val terminalId: String? = null,
    val browserId: String? = null, val changes: Boolean = false,
    val id: String = java.util.UUID.randomUUID().toString()
)
internal fun workspaceSearchId(source: NativeFeedSource, workspace: NativeWorkspace) =
    source.mac.origin + ":workspace:" + workspace.id

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
                workspace.optString("description").takeIf { it.isNotBlank() && it != "null" },
                workspace.optString("unread_count").toLongOrNull()?.takeIf { it >= 0 }
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
            val anchor = item.optString("anchor_workspace_id").takeIf { it.isNotBlank() && it != "null" }
            if (id.isNotBlank()) add(NativeGroup(id, item.optString("name", "Group"),
                item.optBoolean("is_collapsed"), item.optBoolean("is_pinned"),
                anchor, item.optBoolean("is_empty") || anchor == null,
                item.optString("icon_symbol").takeIf { it.isNotBlank() && it != "null" }))
        }
    }
}
