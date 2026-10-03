/* Derived from cmux MobileWorkspaceMovePolicy / MobileWorkspaceListMoveIntentResolver,
 * revision 4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

internal data class NativeWorkspaceMove(val groupId: String?, val beforeWorkspaceId: String?, val movesGroup: Boolean = false)
internal data class NativeWorkspaceOrderEntry(val id: String, val groupId: String?, val pinned: Boolean)

internal class NativeWorkspaceMovePolicy(val workspaces: List<NativeWorkspace>, val groups: List<NativeGroup>) {
    private val byGroup = groups.associateBy { it.id }
    private val byAnchor = groups.mapNotNull { g -> g.liveAnchorWorkspaceId?.let { it to g } }.toMap()
    private fun valid(id: String?) = id?.takeIf(byGroup::containsKey)
    private fun signature(order: List<NativeWorkspace>) = order.map { NativeWorkspaceOrderEntry(it.id, valid(it.groupId), it.isPinned) }

    fun normalized(proposed: NativeWorkspaceMove, movedId: String): NativeWorkspaceMove? {
        if (workspaces.none { it.id == movedId } ||
            (proposed.beforeWorkspaceId != null && workspaces.none { it.id == proposed.beforeWorkspaceId }) ||
            (proposed.groupId != null && proposed.groupId !in byGroup)) return null
        if (proposed.movesGroup && (movedId !in byAnchor || proposed.groupId != null)) return null
        if (!proposed.movesGroup && proposed.groupId != null && movedId in byAnchor) return null
        val predicted = applying(proposed, movedId)
        if (signature(predicted) == signature(workspaces)) return null
        if (proposed.movesGroup) {
            val ids = topLevelIds(predicted)
            val index = ids.indexOf(movedId)
            return NativeWorkspaceMove(null, if (index < 0) null else ids.getOrNull(index + 1), true)
        }
        val index = predicted.indexOfFirst { it.id == movedId }
        return NativeWorkspaceMove(valid(predicted[index].groupId), predicted.getOrNull(index + 1)?.id)
    }

    fun applying(intent: NativeWorkspaceMove, movedId: String): List<NativeWorkspace> {
        var order = normalize(workspaces)
        var movedIndex = order.indexOfFirst { it.id == movedId }
        if (movedIndex < 0) return order
        if (intent.movesGroup) {
            if (movedId !in byAnchor) return order
            val ids = topLevelIds(order).toMutableList()
            val from = ids.indexOf(movedId)
            if (from < 0) return order
            val before = order.firstOrNull { it.id == intent.beforeWorkspaceId }
            val beforeId = byGroup[valid(before?.groupId)]?.liveAnchorWorkspaceId ?: intent.beforeWorkspaceId
            val insertion = ids.indexOf(beforeId).let { if (it < 0) ids.size else it }
            val adjusted = if (insertion > from) insertion - 1 else insertion
            val pinned = ids.filter { id -> byAnchor[id]?.isPinned ?: (order.firstOrNull { it.id == id }?.isPinned == true) }.toSet()
            val clamped = adjusted.coerceIn(0, (ids.size - 1).coerceAtLeast(0)).let {
                if (movedId in pinned) minOf(it, (pinned.size - 1).coerceAtLeast(0)) else maxOf(it, pinned.size)
            }
            ids.removeAt(from); ids.add(clamped.coerceAtMost(ids.size), movedId)
            return normalize(order, ids)
        }
        val moved = order[movedIndex]
        if (valid(moved.groupId) != intent.groupId) {
            if (movedId in byAnchor) return order
            val originalIds = topLevelIds(order)
            order = order.toMutableList().also { it[movedIndex] = moved.copy(groupId = intent.groupId) }
            order = normalize(order, if (intent.groupId != null) originalIds.filter { it != movedId } else null)
        }
        movedIndex = order.indexOfFirst { it.id == movedId }
        val before = intent.beforeWorkspaceId
        val target = if (before != null) {
            val index = order.indexOfFirst { it.id == before }
            if (index < 0) return order
            if (movedIndex < index) index - 1 else index
        } else if (intent.groupId != null) {
            val last = order.indexOfLast { it.id != movedId && valid(it.groupId) == intent.groupId }
            if (last < 0) return normalize(order)
            last + 1
        } else order.size
        val clamped = clampedIndex(order[movedIndex], target, order)
        val result = order.toMutableList()
        val item = result.removeAt(movedIndex)
        result.add(clamped.coerceIn(0, result.size), item)
        return normalize(result)
    }

    private fun clampedIndex(workspace: NativeWorkspace, target: Int, order: List<NativeWorkspace>): Int {
        val bounded = target.coerceIn(0, (order.size - 1).coerceAtLeast(0))
        val group = byGroup[valid(workspace.groupId)]
        if (group != null && workspace.id != group.liveAnchorWorkspaceId) {
            val indices = order.indices.filter { valid(order[it].groupId) == group.id }
            if (indices.isNotEmpty()) {
                val first = indices.first(); val last = indices.last()
                val pinned = indices.count { order[it].id != group.liveAnchorWorkspaceId && order[it].isPinned }
                val lower = minOf(first + 1 + if (workspace.isPinned) 0 else pinned, last)
                val upper = if (workspace.isPinned) maxOf(first + pinned, lower) else last
                return bounded.coerceIn(lower, upper)
            }
        }
        fun globallyPinned(row: NativeWorkspace) = byGroup[valid(row.groupId)]?.isPinned ?: row.isPinned
        val pinnedCount = order.takeWhile(::globallyPinned).size
        return if (globallyPinned(workspace)) minOf(bounded, (pinnedCount - 1).coerceAtLeast(0))
            else minOf(maxOf(bounded, pinnedCount), (order.size - 1).coerceAtLeast(0))
    }

    private fun topLevelIds(order: List<NativeWorkspace>): List<String> {
        val emitted = mutableSetOf<String>()
        return buildList {
            order.forEach { row ->
                val group = byGroup[valid(row.groupId)]
                if (group == null) add(row.id)
                else if (emitted.add(group.id)) add(group.liveAnchorWorkspaceId ?: row.id)
            }
        }
    }

    private fun normalize(order: List<NativeWorkspace>, preferred: List<String>? = null): List<NativeWorkspace> {
        val clean = order.map { it.copy(groupId = valid(it.groupId)) }
        val ids = preferred ?: topLevelIds(clean)
        val byId = clean.associateBy { it.id }
        fun pinned(id: String) = byAnchor[id]?.isPinned ?: (byId[id]?.isPinned == true)
        val desired = ids.filter(::pinned) + ids.filterNot(::pinned)
        val emitted = mutableSetOf<String>(); val emittedGroups = mutableSetOf<String>()
        val result = mutableListOf<NativeWorkspace>()
        fun append(id: String) {
            val row = byId[id] ?: return
            val group = byGroup[row.groupId]
            if (group != null && emittedGroups.add(group.id)) {
                val members = clean.filter { it.groupId == group.id }
                val anchor = members.firstOrNull { it.id == group.liveAnchorWorkspaceId }
                val ordered = if (anchor == null) members else listOf(anchor) +
                    members.filter { it.id != anchor.id && it.isPinned } + members.filter { it.id != anchor.id && !it.isPinned }
                ordered.filter { emitted.add(it.id) }.forEach(result::add)
            } else if (group == null && emitted.add(row.id)) result += row
        }
        desired.forEach(::append)
        clean.filter { it.id !in emitted }.forEach { append(it.id) }
        return result
    }
}

/** Destination is a slot in the original rendered list, before removing the dragged row. */
internal fun workspaceDropIntent(source: NativeFeedSource, items: List<WorkspaceListEntry>, from: Int, destination: Int): Pair<String, NativeWorkspaceMove>? {
    if (from !in items.indices || items.any { it.source.mac.origin != source.mac.origin }) return null
    val movedItem = items[from]
    val movedId = when (movedItem) {
        is WorkspaceListEntry.Workspace -> movedItem.workspace.id
        is WorkspaceListEntry.Header -> movedItem.group.liveAnchorWorkspaceId
        is WorkspaceListEntry.Footer -> null
    } ?: return null
    val moved = source.workspaces.firstOrNull { it.id == movedId } ?: return null
    val dest = destination.coerceIn(0, items.size)
    if (dest == from || dest == from + 1) return null
    val remaining = items.toMutableList().also { it.removeAt(from) }
    val index = (if (from < dest) dest - 1 else dest).coerceIn(0, remaining.size)
    val previous = remaining.getOrNull(index - 1); val next = remaining.getOrNull(index)
    val workspaces = source.workspaces.filter { it.id != movedId }
    val groups = source.groups.associateBy { it.id }
    fun valid(id: String?) = id?.takeIf(groups::containsKey)
    fun first(group: String) = workspaces.firstOrNull { valid(it.groupId) == group }?.id
    fun after(group: String): String? {
        val last = workspaces.indexOfLast { valid(it.groupId) == group }
        return if (last < 0) null else workspaces.getOrNull(last + 1)?.id
    }
    fun rootBefore(item: WorkspaceListEntry?): String? = when (item) {
        is WorkspaceListEntry.Workspace -> item.workspace.id
        is WorkspaceListEntry.Header -> first(item.group.id) ?: remaining.drop(remaining.indexOf(item) + 1).firstNotNullOfOrNull {
            when (it) {
                is WorkspaceListEntry.Workspace -> it.workspace.id
                is WorkspaceListEntry.Header -> first(it.group.id)
                is WorkspaceListEntry.Footer -> null
            }
        }
        is WorkspaceListEntry.Footer -> after(item.group.id) ?: first(item.group.id) ?: groups[item.group.id]?.liveAnchorWorkspaceId
        null -> null
    }
    fun root() = NativeWorkspaceMove(null, rootBefore(next), movedItem is WorkspaceListEntry.Header)
    val proposed = if (movedItem is WorkspaceListEntry.Header) root() else when (previous) {
        is WorkspaceListEntry.Header -> if (previous.group.isCollapsed) root() else {
            val group = previous.group
            val anchor = groups[group.id]?.liveAnchorWorkspaceId
            val member = if (anchor == null) null else workspaces.firstOrNull { valid(it.groupId) == group.id && it.id != anchor }
            NativeWorkspaceMove(group.id, member?.id ?: after(group.id))
        }
        is WorkspaceListEntry.Workspace -> {
            val group = valid(previous.workspace.groupId)
            when {
                group == null -> root()
                next is WorkspaceListEntry.Workspace && valid(next.workspace.groupId) == group -> NativeWorkspaceMove(group, next.workspace.id)
                next is WorkspaceListEntry.Footer && next.group.id == group -> NativeWorkspaceMove(group, after(group))
                next is WorkspaceListEntry.Footer -> root()
                valid(moved.groupId) == group -> NativeWorkspaceMove(group, after(group))
                else -> root()
            }
        }
        else -> root()
    }
    return NativeWorkspaceMovePolicy(source.workspaces, source.groups).normalized(proposed, movedId)?.let { movedId to it }
}

/** One accessible step skips inert slots, including a dragged group's own children/footer. */
internal fun workspaceStepIntent(source: NativeFeedSource, items: List<WorkspaceListEntry>, from: Int,
    down: Boolean): Pair<String, NativeWorkspaceMove>? {
    if (from !in items.indices) return null
    val destinations = if (down) (from + 2)..items.size else (from - 1) downTo 0
    return destinations.firstNotNullOfOrNull { workspaceDropIntent(source, items, from, it) }
}
