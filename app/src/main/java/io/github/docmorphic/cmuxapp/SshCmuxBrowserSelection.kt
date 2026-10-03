package io.github.docmorphic.cmuxapp

/** Browser content is distinct from terminal content, including across saved-state restoration. */
internal data class SshCmuxBrowserSelection(val session: String, val registry: String?, val generation: String?,
    val workspace: Int, val workspaceKey: String?, val workspaceResource: String?, val surface: Int,
    val tabResource: String?, val contentResource: String?) {
    val panelId: String get() = "cmux-ssh-browser:" + org.json.JSONArray(listOf(session, registry,
        workspaceKey ?: workspaceResource ?: "$generation:$workspace",
        contentResource ?: "$generation:$surface", tabResource)).toString()
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
        val tab = workspace.tabs.filter { it.isBrowser && !it.dead }.singleOrNull {
            (tabResource == null || it.resource == tabResource) && when {
                contentResource != null -> it.content == contentResource
                else -> sameOwner && it.surface == surface
            }
        } ?: return null
        return workspace to tab
    }
    companion object {
        fun capture(session: String, tree: SshCmuxTree, workspace: SshCmuxWorkspace, tab: SshCmuxTab): SshCmuxBrowserSelection {
            require(workspace in tree.workspaces && tab in workspace.tabs && tab.isBrowser && !tab.dead)
            return SshCmuxBrowserSelection(session, tree.registry, tree.generation, workspace.id, workspace.key, workspace.resource,
                tab.surface, tab.resource, tab.content)
        }
    }
}
