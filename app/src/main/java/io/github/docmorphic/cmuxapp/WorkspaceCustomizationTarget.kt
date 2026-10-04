package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.saveable.listSaver

/** Saved editor identity includes the login incarnation, never credentials or pending RPCs. */
internal data class WorkspaceCustomizationTarget(val login: String, val origin: String, val key: NativeWorkspaceTabKey) {
    val workspaceId get() = key.workspaceId
    fun matches(login: String?, team: NativeTeamScope?, mac: NativeCredentialStore.PairedMac) =
        this.login == login && origin == mac.origin && key == workspaceTabKey(login, team, mac, workspaceId)

    fun saved() = listOf("2", login, origin, key.accountId, key.teamId.orEmpty(), key.computerId, key.workspaceId)

    companion object {
        fun capture(login: String?, team: NativeTeamScope?, mac: NativeCredentialStore.PairedMac, workspaceId: String) =
            workspaceTabKey(login, team, mac, workspaceId)?.let { WorkspaceCustomizationTarget(checkNotNull(login), mac.origin, it) }

        fun restore(fields: List<String>): WorkspaceCustomizationTarget? {
            // Legacy origin/workspace-only state cannot establish ownership.
            if (fields.size != 7 || fields[0] != "2" || fields.any { it.length > 16_384 } ||
                listOf(1, 2, 3, 5, 6).any { fields[it].isBlank() }) return null
            return WorkspaceCustomizationTarget(fields[1], fields[2], NativeWorkspaceTabKey(fields[3],
                fields[4].ifEmpty { null }, fields[5], fields[6]))
        }
    }
}

internal val workspaceCustomizationTargetSaver = listSaver<WorkspaceCustomizationTarget?, String>(
    save = { it?.saved().orEmpty() }, restore = WorkspaceCustomizationTarget::restore)
