package io.github.docmorphic.cmuxapp

internal const val WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY = "workspace.mutations.account_auth.v1"

// NativeConnector uses Stack account authentication without an attach ticket.
// Legacy ticket-only hosts cannot authorize a Mac-wide group mutation here.
internal fun NativeFeedSource.canEditGroups() = WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY in capabilities &&
    "workspace.group_actions.v1" in capabilities
internal fun NativeFeedSource.canCreateInGroup() = WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY in capabilities &&
    "workspace.create_in_group.v1" in capabilities

internal fun NativeFeedSource.canCreateGroup() = WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY in capabilities &&
    "workspace.group_create.v1" in capabilities
