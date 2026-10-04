package io.github.docmorphic.cmuxapp

import org.json.JSONArray

/** Same bounded encrypted preferences as native workspaces, with an SSH route namespace.
 * Names and connection pauses are presentation; endpoint/key/jump changes are new owners. */
internal fun sshWorkspaceTabKey(login: String, host: SshHostRecord, target: SshWorkspaceTarget): NativeWorkspaceTabKey =
    NativeWorkspaceTabKey(login, null, JSONArray(listOf("ssh", host.id.toString(), host.endpoint.host,
        host.endpoint.port, host.endpoint.username, host.keyId?.toString(), host.jumpHostId?.toString())).toString(),
        sshBrowserWorkspace(target, "").id)

internal fun SshWorkspaceTarget.rememberedTab(): NativeWorkspaceTab? = runCatching {
    if (this is SshWorkspaceTarget.CmuxWorkspace) return null
    NativeWorkspaceTab(if (this is SshWorkspaceTarget.Browser) NativeWorkspaceTabKind.BROWSER_STREAM
        else NativeWorkspaceTabKind.TERMINAL, encode())
}.getOrNull()

internal fun SshFeedRow.openTarget(): SshWorkspaceTarget? = targets.firstOrNull() ?: cmuxWorkspace?.let { workspace ->
    cmuxSession?.let { session -> SshWorkspaceTarget.CmuxWorkspace(SshCmuxWorkspaceSelection(session, registry,
        generation, workspace.id, workspace.key, workspace.resource)) }
}

/** Resolve against this row's inventory, never against a title or a recycled numeric ID.
 * Return a fresh capture so later navigation uses the current generation and pane numbers.
 * Missing/unknown preferences fall back without deleting the stored choice during discovery. */
internal fun SshFeedRow.reopenTarget(remembered: NativeWorkspaceTab?): SshWorkspaceTarget? {
    val saved = remembered?.let { tab -> SshWorkspaceTarget.decode(tab.id)?.takeIf { target ->
        target.rememberedTab()?.kind == tab.kind
    } }
    val tree = cmuxWorkspace?.let { SshCmuxTree(generation, registry, null, listOf(it)) }
    val resolved = when (saved) {
        is SshWorkspaceTarget.Cmux -> if (tree != null && cmuxSession != null)
            saved.selection.resolve(cmuxSession, tree)?.let { (workspace, tab) ->
                SshWorkspaceTarget.Cmux(SshCmuxSelection.capture(cmuxSession, tree, workspace, tab))
            } else null
        is SshWorkspaceTarget.Browser -> if (tree != null && cmuxSession != null)
            saved.selection.resolve(cmuxSession, tree)?.let { (workspace, tab) ->
                SshWorkspaceTarget.Browser(SshCmuxBrowserSelection.capture(cmuxSession, tree, workspace, tab))
            } else null
        is SshWorkspaceTarget.Tmux, is SshWorkspaceTarget.Shell -> targets.singleOrNull { it == saved }
        is SshWorkspaceTarget.CmuxWorkspace, null -> null
    }
    return resolved ?: openTarget()
}
