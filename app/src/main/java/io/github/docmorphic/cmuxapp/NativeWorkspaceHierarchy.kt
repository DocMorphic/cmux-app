/* Derived from cmux MobileWorkspaceListItem.swift, 4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

internal fun effectiveWorkspaceGroups(workspaces: List<NativeWorkspace>, groups: List<NativeGroup>): List<NativeGroup> =
    groups.distinctBy { it.id }.map { group ->
        val first = workspaces.firstOrNull { it.groupId == group.id }
        if (group.isEmpty && first != null) group.copy(anchorWorkspaceId = first.id, isEmpty = false) else group
    }

internal fun workspaceEntries(sources: List<NativeFeedSource>, matches: Set<String>,
    filtering: Boolean, unreadOnly: Boolean, collapsedGroups: Map<String, Boolean>): List<WorkspaceListEntry> =
    sources.flatMap { source ->
        if (filtering || unreadOnly) source.workspaces.filter {
            (!unreadOnly || it.hasUnread) && workspaceSearchId(source, it) in matches
        }.map { WorkspaceListEntry.Workspace(source, it) }
        else workspaceHierarchy(source, collapsedGroups)
    }

/** Preserve spatial order, anchor-only headers, per-Mac collapse, and explicit inside/outside group drop slots. */
internal fun workspaceHierarchy(source: NativeFeedSource, collapsedGroups: Map<String, Boolean> = emptyMap()): List<WorkspaceListEntry> {
    val workspaces = source.workspaces.distinctBy { it.id }
    val groups = effectiveWorkspaceGroups(workspaces, source.groups).map { group ->
        group.copy(isCollapsed = collapsedGroups[WorkspaceListEntry.Header(source, group).key] ?: group.isCollapsed)
    }
    val groupsById = groups.associateBy { it.id }
    val members = workspaces.groupBy { it.groupId }
    val emittedHeaders = mutableSetOf<String>()
    val emittedFooters = mutableSetOf<String>()
    var previousGroup: NativeGroup? = null
    var memberCount = 0
    val items = mutableListOf<WorkspaceListEntry>()
    fun closeRun() {
        previousGroup?.let { group ->
            if (memberCount > 0 && !group.isCollapsed && emittedFooters.add(group.id))
                items += WorkspaceListEntry.Footer(source, group)
        }
        memberCount = 0
    }
    for (workspace in workspaces) {
        val group = groupsById[workspace.groupId]
        if (group?.id != previousGroup?.id) {
            closeRun(); previousGroup = group
            if (group != null && emittedHeaders.add(group.id)) {
                val unread = members[group.id].orEmpty()
                    .filter { group.isCollapsed || it.id == group.liveAnchorWorkspaceId }
                    .fold(NativeWorkspaceUnread.Read) { aggregate, row -> aggregate.merging(row.unreadState) }
                items += WorkspaceListEntry.Header(source, group, unread)
            }
        }
        if (group != null && group.liveAnchorWorkspaceId == workspace.id) continue
        if (group == null || !group.isCollapsed) {
            items += WorkspaceListEntry.Workspace(source, workspace, group != null)
            if (group != null) memberCount++
        }
    }
    closeRun()
    // Empty headers occupy the same pin tier and relative group slot as the host.
    val liveGroups = workspaces.mapNotNull { it.groupId?.takeIf(groupsById::containsKey) }.toSet()
    val emptyGroups = groups.filter { it.isEmpty && it.id !in liveGroups }
    val before = mutableMapOf<String, MutableList<NativeGroup>>()
    val trailingPinned = mutableListOf<NativeGroup>()
    val trailingUnpinned = mutableListOf<NativeGroup>()
    for (empty in emptyGroups) {
        val next = groups.drop(groups.indexOf(empty) + 1).firstOrNull { it.id in liveGroups && it.isPinned == empty.isPinned }
        when {
            next != null -> before.getOrPut(next.id) { mutableListOf() }.add(empty)
            empty.isPinned -> trailingPinned += empty
            else -> trailingUnpinned += empty
        }
    }
    val result = items.flatMap { item ->
        if (item is WorkspaceListEntry.Header) before[item.group.id].orEmpty().map { WorkspaceListEntry.Header(source, it) } + item
        else listOf(item)
    }.toMutableList()
    val firstUnpinned = result.indexOfFirst { item -> !when (item) {
        is WorkspaceListEntry.Header -> item.group.isPinned
        is WorkspaceListEntry.Footer -> item.group.isPinned
        is WorkspaceListEntry.Workspace -> groupsById[item.workspace.groupId]?.isPinned ?: item.workspace.isPinned
    } }.let { if (it < 0) result.size else it }
    result.addAll(firstUnpinned, trailingPinned.map { WorkspaceListEntry.Header(source, it) })
    result += trailingUnpinned.map { WorkspaceListEntry.Header(source, it) }
    return result
}
