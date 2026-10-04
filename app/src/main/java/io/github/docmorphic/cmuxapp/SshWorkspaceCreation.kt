package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class SshWorkspaceKind(val title: String) {
    CMUX_TUI("New cmux-tui Workspace"), TMUX("New tmux Session"), SHELL("New Shell")
}

internal data class SshWorkspaceKindOption(val kind: SshWorkspaceKind, val unavailableReason: String? = null,
    val needsInstall: Boolean = false)

/** Unprobed hosts offer every kind. Probe results narrow only the kind they describe. */
internal fun sshWorkspaceKinds(tmux: SshTmuxHostState?, cmux: SshCmuxHostState?): List<SshWorkspaceKindOption> = listOf(
    SshWorkspaceKindOption(SshWorkspaceKind.CMUX_TUI,
        unavailableReason = if (cmux != null && !cmux.loading && !cmux.available && cmux.platform?.packageName == null)
            "cmux-tui is unavailable for ${cmux.platform?.os ?: "?"} / ${cmux.platform?.arch ?: "?"}." else null,
        needsInstall = cmux != null && !cmux.loading && !cmux.available && cmux.platform?.packageName != null),
    SshWorkspaceKindOption(SshWorkspaceKind.TMUX,
        unavailableReason = if (tmux != null && !tmux.loading && !tmux.available)
            tmux.error ?: "tmux is not installed on this computer." else null),
    SshWorkspaceKindOption(SshWorkspaceKind.SHELL)
)

/** A menu's saved host must still describe the same route when creation begins. */
internal suspend fun NativeSshSession.createWorkspace(expected: SshHostRecord, kind: SshWorkspaceKind): SshWorkspaceTarget =
    withContext(Dispatchers.Main.immediate) {
        fun guard() {
            check(isOpen && hosts.state.value.host(expected.id)?.connectsLike(expected) == true) {
                "SSH computer changed. Reopen the workspace menu."
            }
        }
        guard()
        val connection = connections.open(expected.id)
        guard()
        when (kind) {
            SshWorkspaceKind.SHELL -> SshWorkspaceTarget.Shell(shells.create(expected.id, connection).id)
            SshWorkspaceKind.TMUX -> tmux.adopt(expected.id, connection).createWorkspace()
            SshWorkspaceKind.CMUX_TUI -> cmux.adopt(expected.id, connection).createWorkspaceTarget()
        }.also { guard() }
    }
