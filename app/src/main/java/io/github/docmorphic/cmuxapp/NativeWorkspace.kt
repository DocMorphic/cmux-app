package io.github.docmorphic.cmuxapp

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
