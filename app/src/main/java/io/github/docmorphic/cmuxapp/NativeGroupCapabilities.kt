package io.github.docmorphic.cmuxapp

internal const val WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY = "workspace.mutations.account_auth.v1"

/** Non-secret UI projection; the RPC client rechecks the real context before writing. */
internal data class NativeMacMutationTicket(val expiresAtMillis: Long?) {
    fun isCurrent(nowMillis: Long) = expiresAtMillis == null || nowMillis < expiresAtMillis
}

internal fun NativeFeedSource.canMutateMacWorkspaces(nowMillis: Long = System.currentTimeMillis()) =
    WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY in capabilities || macMutationTicket?.isCurrent(nowMillis) == true

internal fun NativeFeedSource.canEditGroups() = canMutateMacWorkspaces() &&
    "workspace.group_actions.v1" in capabilities
internal fun NativeFeedSource.canCreateInGroup() = canMutateMacWorkspaces() &&
    "workspace.create_in_group.v1" in capabilities

internal fun NativeFeedSource.canCreateGroup() = canMutateMacWorkspaces() &&
    "workspace.group_create.v1" in capabilities
