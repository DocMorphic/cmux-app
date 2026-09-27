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
    data class Header(val group: NativeGroup) : WorkspaceListEntry
    data class Workspace(val workspace: NativeWorkspace) : WorkspaceListEntry
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
