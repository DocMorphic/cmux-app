package io.github.docmorphic.cmuxapp

/** A workspace can remain open after its last pane closes. Restoring it never creates a pane. */
internal data class SshCmuxWorkspaceSelection(val session: String, val registry: String?, val generation: String?,
    val workspace: Int, val key: String?, val resource: String?) {
    fun resolve(session: String, tree: SshCmuxTree): SshCmuxWorkspace? {
        if (this.session != session || registry != null && registry != tree.registry) return null
        return tree.workspaces.singleOrNull {
            when {
                key != null -> it.key == key && (resource == null || it.resource == resource)
                resource != null -> it.resource == resource
                else -> generation != null && generation == tree.generation && it.id == workspace
            }
        }
    }
    companion object {
        fun capture(session: String, tree: SshCmuxTree, workspace: SshCmuxWorkspace): SshCmuxWorkspaceSelection {
            require(workspace in tree.workspaces)
            return SshCmuxWorkspaceSelection(session, tree.registry, tree.generation, workspace.id, workspace.key, workspace.resource)
        }
    }
}

internal fun SshWorkspaceTarget.cmuxWorkspace(): SshCmuxWorkspaceSelection? = when (this) {
    is SshWorkspaceTarget.CmuxWorkspace -> selection
    is SshWorkspaceTarget.Cmux -> selection.let { SshCmuxWorkspaceSelection(it.session, it.registry, it.generation,
        it.workspace, it.workspaceKey, it.workspaceResource) }
    is SshWorkspaceTarget.Browser -> selection.let { SshCmuxWorkspaceSelection(it.session, it.registry, it.generation,
        it.workspace, it.workspaceKey, it.workspaceResource) }
    else -> null
}
