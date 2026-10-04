package io.github.docmorphic.cmuxapp

/** Explicit operations only; the browser never supplies a Mac, native ID or RPC method. */
internal enum class RoutedSidebarMutationKind(val verb: String) {
    PIN("pin"), UNPIN("unpin"), RENAME("rename"), MARK_READ("mark_read"), MARK_UNREAD("mark_unread"),
    CLOSE("close"), UNGROUP("ungroup"), DELETE_GROUP("delete"), MOVE_TO_GROUP("move_to_group");
    companion object {
        fun fromVerb(value: String) = entries.singleOrNull { it.verb == value }
    }
}
internal data class RoutedSidebarMutation(val key: String, val kind: RoutedSidebarMutationKind, val title: String? = null,
    val menuRevision: String? = null, val destination: String? = null) {
    fun validate() {
        require(key.length in 1..128 && key.none(Char::isISOControl))
        if (kind == RoutedSidebarMutationKind.RENAME) {
            require(title != null && title.isNotBlank()) { "Enter a workspace or group name." }
            require(title.length <= 4096) { "Name must be 4,096 characters or fewer." }
        } else require(title == null)
        if (kind == RoutedSidebarMutationKind.MOVE_TO_GROUP) {
            require(menuRevision != null && menuRevision.length in 1..128 && menuRevision.none(Char::isISOControl))
            require(destination == null || destination.length in 1..128 && destination.none(Char::isISOControl))
        } else require(menuRevision == null && destination == null)
    }
}
internal fun NativeFeedSource.sidebarWorkspaceMutations(item: NativeWorkspace): Set<RoutedSidebarMutationKind> = buildSet {
    if (availability != NativeFeedAvailability.CONNECTED) return@buildSet
    if ("workspace.actions.v1" in capabilities) {
        add(if (item.isPinned) RoutedSidebarMutationKind.UNPIN else RoutedSidebarMutationKind.PIN)
        add(RoutedSidebarMutationKind.RENAME)
    }
    if ("workspace.read_state.v1" in capabilities)
        add(if (item.hasUnread) RoutedSidebarMutationKind.MARK_READ else RoutedSidebarMutationKind.MARK_UNREAD)
    if ("workspace.close.v1" in capabilities) add(RoutedSidebarMutationKind.CLOSE)
}
internal fun NativeFeedSource.sidebarGroupMutations(item: NativeGroup): Set<RoutedSidebarMutationKind> = buildSet {
    if (availability != NativeFeedAvailability.CONNECTED || !canEditGroups()) return@buildSet
    add(if (item.isPinned) RoutedSidebarMutationKind.UNPIN else RoutedSidebarMutationKind.PIN)
    add(RoutedSidebarMutationKind.RENAME)
    if (!item.isPinned) add(RoutedSidebarMutationKind.UNGROUP)
    add(RoutedSidebarMutationKind.DELETE_GROUP)
}
internal data class NativeSidebarMutationTarget(val mac: NativeCredentialStore.PairedMac, val id: String, val group: Boolean)
