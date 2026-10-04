/* Derived from cmux MobileWorkspaceGroupMoveMenu, revision
 * 0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

internal data class NativeWorkspaceGroupMoveMenu(
    val entries: List<Entry> = emptyList(), val canRemoveFromGroup: Boolean = false
) {
    data class Entry(val group: NativeGroup, val isCurrent: Boolean, val isEnabled: Boolean)
    val isEmpty get() = entries.isEmpty() && !canRemoveFromGroup

    companion object {
        /** One complete owning-Mac snapshot, never a filtered or aggregated list. */
        fun forWorkspace(source: NativeFeedSource, movedId: String, pending: Int = 0): NativeWorkspaceGroupMoveMenu {
            if (!source.canReorderWorkspaces() || pending >= 3) return NativeWorkspaceGroupMoveMenu()
            val moved = source.workspaces.firstOrNull { it.id == movedId } ?: return NativeWorkspaceGroupMoveMenu()
            if (source.groups.any { it.liveAnchorWorkspaceId == movedId }) return NativeWorkspaceGroupMoveMenu()
            val policy = NativeWorkspaceMovePolicy(source.workspaces, source.groups)
            return NativeWorkspaceGroupMoveMenu(source.groups.map { group ->
                val current = moved.groupId == group.id
                Entry(group, current, !current && policy.normalized(NativeWorkspaceMove(group.id, null), movedId) != null)
            }, source.groups.any { it.id == moved.groupId } &&
                policy.normalized(NativeWorkspaceMove(null, null), movedId) != null)
        }
    }
}
