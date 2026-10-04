package io.github.docmorphic.cmuxapp

/** Captured in the main process. Only the resulting selected bit crosses browser IPC. */
internal sealed interface NativeSidebarSelection {
    data class Mac(val mac: NativeCredentialStore.PairedMac, val workspaceId: String) : NativeSidebarSelection
    data class Ssh(val host: SshHostRecord, val target: SshWorkspaceTarget) : NativeSidebarSelection
}

internal fun NativeSidebarSelection?.matches(mac: NativeCredentialStore.PairedMac, workspaceId: String?): Boolean =
    this is NativeSidebarSelection.Mac && this.mac == mac && workspaceId != null && this.workspaceId == workspaceId

internal fun NativeSidebarSelection?.matches(row: SshFeedRow): Boolean {
    if (this !is NativeSidebarSelection.Ssh || !host.connectsLike(row.host)) return false
    return when (val target = target) {
        is SshWorkspaceTarget.Shell -> row.kind == SshWorkspaceKind.SHELL && target in row.targets
        is SshWorkspaceTarget.Tmux -> row.kind == SshWorkspaceKind.TMUX && row.tmuxWorkspace?.id == target.workspace
        else -> row.kind == SshWorkspaceKind.CMUX_TUI && row.cmuxSession?.let { session ->
            row.cmuxWorkspace?.let { workspace -> target.cmuxWorkspace()?.resolve(session,
                SshCmuxTree(row.generation, row.registry, null, listOf(workspace))) != null }
        } == true
    }
}
