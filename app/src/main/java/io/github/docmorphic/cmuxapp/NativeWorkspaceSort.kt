/* Based on cmux MobileWorkspaceAggregation/RecencyOrder/SortStore, 0fc35d6.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

internal enum class NativeWorkspaceSortMode(val raw: String, val title: String) {
    AUTOMATIC("automatic", "Last Opened"), PRIORITY("computerPriority", "Custom Order"), ACTIVITY("recentActivity", "Recent Activity")
}
internal data class NativeWorkspaceSortState(val rawMode: String = "automatic", val priority: List<String> = emptyList(),
    val opened: Map<String, Long> = emptyMap()) {
    val mode get() = NativeWorkspaceSortMode.entries.firstOrNull { it.raw == rawMode } ?: NativeWorkspaceSortMode.AUTOMATIC
}
/** A device-local preference, never a host mutation. Unknown modes survive unrelated writes. */
internal class NativeWorkspaceSortStore(read: () -> String?, private val write: (String) -> Unit) {
    private val mutable = MutableStateFlow(runCatching {
        val json = JSONObject(read() ?: "{}")
        val priority = json.optJSONArray("priority") ?: JSONArray()
        val opened = json.optJSONObject("opened") ?: JSONObject()
        NativeWorkspaceSortState(json.optString("mode", "automatic"),
            (0 until priority.length()).map { priority.getString(it) }.distinct(),
            opened.keys().asSequence().mapNotNull { id -> (opened.opt(id) as? Number)?.toLong()?.takeIf { it > 0 }?.let { id to it } }.toMap())
    }.getOrDefault(NativeWorkspaceSortState()))
    val state = mutable.asStateFlow()
    @Synchronized fun setMode(mode: NativeWorkspaceSortMode) = save(mutable.value.copy(rawMode = mode.raw))
    @Synchronized fun recordOpened(id: String, at: Long) {
        if (id.isNotEmpty() && at > (mutable.value.opened[id] ?: 0)) save(mutable.value.copy(opened = mutable.value.opened + (id to at)))
    }
    @Synchronized fun setPriority(ids: List<String>) {
        val visible = ids.filter(String::isNotEmpty).distinct()
        // Keep absent computers so a temporary disconnect does not erase their slot.
        val old = mutable.value.priority
        val iterator = visible.iterator()
        val result = old.map { if (it in visible && iterator.hasNext()) iterator.next() else it }.toMutableList()
        while (iterator.hasNext()) result += iterator.next()
        save(mutable.value.copy(priority = result.distinct()))
    }
    private fun save(next: NativeWorkspaceSortState) {
        if (next == mutable.value) return
        write(JSONObject().put("mode", next.rawMode).put("priority", JSONArray(next.priority))
            .put("opened", JSONObject(next.opened)).toString())
        mutable.value = next
    }
}
internal data class NativeSortComputer(val id: String, val name: String, val foreground: Boolean = false, val buildLabel: String? = null)
internal fun orderWorkspaceComputers(computers: List<NativeSortComputer>, state: NativeWorkspaceSortState,
    locale: Locale): List<NativeSortComputer> {
    val priority = if (state.mode == NativeWorkspaceSortMode.PRIORITY) state.priority.distinct().withIndex().associate { it.value to it.index } else emptyMap()
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    return computers.distinctBy { it.id }.sortedWith(compareBy<NativeSortComputer> { priority[it.id] ?: Int.MAX_VALUE }
        .thenByDescending { it.foreground }.thenByDescending { state.opened[it.id] ?: Long.MIN_VALUE }
        .thenComparator { a, b -> collator.compare(a.name, b.name) }.thenBy { it.id })
}
internal sealed interface NativeWorkspaceDisplayRow {
    val key: String
    data class Mac(val entry: WorkspaceListEntry) : NativeWorkspaceDisplayRow { override val key get() = entry.key }
    data class Ssh(val row: SshFeedRow) : NativeWorkspaceDisplayRow { override val key get() = row.key }
    data class Cloud(val row: CloudWorkspaceRow) : NativeWorkspaceDisplayRow { override val key get() = row.key }
}
private data class WorkspaceDisplayBlock(val rows: List<NativeWorkspaceDisplayRow>, val pinned: Boolean, val time: Double?)
private fun List<NativeWorkspace>.activity() = mapNotNull { it.lastActivityAt?.takeIf(Double::isFinite) }.maxOrNull()
private fun WorkspaceListEntry.withSource(source: NativeFeedSource): WorkspaceListEntry = when (this) {
    is WorkspaceListEntry.Header -> copy(source = source)
    is WorkspaceListEntry.Workspace -> copy(source = source)
    is WorkspaceListEntry.Footer -> copy(source = source)
}

/** Immutable display projection. Every action retains the original source, including its full sidebar order. */
internal fun sortedWorkspaceRows(sources: List<NativeFeedSource>, ssh: List<SshFeedRow>, computers: List<NativeSortComputer>,
    state: NativeWorkspaceSortState, allComputers: Boolean, matches: Set<String>, filtering: Boolean, unread: Boolean,
    collapsed: Map<String, Boolean>, locale: Locale, cloud: List<CloudWorkspaceRow> = emptyList()): List<NativeWorkspaceDisplayRow> {
    val ranks = orderWorkspaceComputers(computers, state, locale).withIndex().associate { it.value.id to it.index }
    val blocks = mutableListOf<Pair<Int, List<WorkspaceDisplayBlock>>>()
    val recent = allComputers && state.mode == NativeWorkspaceSortMode.ACTIVITY
    for (source in sources) {
        val sourceBlocks = mutableListOf<WorkspaceDisplayBlock>()
        if (!recent || filtering || unread) {
            val rows = workspaceEntries(listOf(source), matches, filtering, unread, collapsed)
            if (recent) rows.filterIsInstance<WorkspaceListEntry.Workspace>().forEach {
                sourceBlocks += WorkspaceDisplayBlock(listOf(NativeWorkspaceDisplayRow.Mac(it)), it.workspace.isPinned,
                    it.workspace.lastActivityAt?.takeIf(Double::isFinite))
            } else sourceBlocks += WorkspaceDisplayBlock(rows.map(NativeWorkspaceDisplayRow::Mac), false, null)
        } else {
            val groups = effectiveWorkspaceGroups(source.workspaces, source.groups)
            val groupMap = groups.associateBy { it.id }
            val anchors = buildMap { groups.forEach { g -> g.liveAnchorWorkspaceId?.let { if (it !in this) put(it, g.id) } } }
            val members = linkedMapOf<String, MutableList<NativeWorkspace>>()
            val first = mutableListOf<Pair<String?, NativeWorkspace>>()
            for (workspace in source.workspaces.distinctBy { it.id }) {
                val group = workspace.groupId?.takeIf { it in groupMap } ?: anchors[workspace.id]
                if (group == null) first += null to workspace else {
                    if (group !in members) first += group to workspace
                    members.getOrPut(group) { mutableListOf() } += workspace.copy(groupId = group)
                }
            }
            fun groupBlock(group: NativeGroup, rows: List<NativeWorkspace>) {
                val temporary = source.copy(workspaces = rows, groups = listOf(group))
                val entries = workspaceHierarchy(temporary, collapsed).map { NativeWorkspaceDisplayRow.Mac(it.withSource(source)) }
                sourceBlocks += WorkspaceDisplayBlock(entries, group.isPinned || rows.any { it.isPinned }, rows.activity())
            }
            for ((group, workspace) in first) if (group == null) sourceBlocks += WorkspaceDisplayBlock(
                listOf(NativeWorkspaceDisplayRow.Mac(WorkspaceListEntry.Workspace(source, workspace))), workspace.isPinned,
                workspace.lastActivityAt?.takeIf(Double::isFinite))
            else groupBlock(groupMap.getValue(group), members.getValue(group))
            groups.filter { it.isEmpty && it.id !in members }.forEach { groupBlock(it, emptyList()) }
        }
        blocks += (ranks[workspaceMacFilterId(source.mac.deviceId, source.mac.instanceTag)] ?: Int.MAX_VALUE) to sourceBlocks
    }
    ssh.groupBy { it.host.id }.forEach { (id, rows) ->
        blocks += (ranks[workspaceSshFilterId(id)] ?: Int.MAX_VALUE) to rows.map {
            WorkspaceDisplayBlock(listOf(NativeWorkspaceDisplayRow.Ssh(it)), it.workspace.isPinned, it.workspace.lastActivityAt)
        }
    }
    cloud.groupBy { it.machine.id }.forEach { (id, rows) ->
        blocks += (ranks[CloudAddress(id).identifier] ?: Int.MAX_VALUE) to rows.map {
            WorkspaceDisplayBlock(listOf(NativeWorkspaceDisplayRow.Cloud(it)), false, null)
        }
    }
    val ordered = (if (allComputers) blocks.sortedBy { it.first } else blocks).flatMap { it.second }
    val result = (if (recent) ordered.sortedWith(compareByDescending<WorkspaceDisplayBlock> { it.pinned }
        .thenByDescending { it.time ?: Double.NEGATIVE_INFINITY }) else ordered).flatMap { it.rows }
    // iOS flat lists retain pinned-first behavior across computer boundaries.
    return if (!recent && (filtering || unread || sources.none { it.groups.isNotEmpty() })) result.sortedByDescending {
        when (it) { is NativeWorkspaceDisplayRow.Mac -> (it.entry as? WorkspaceListEntry.Workspace)?.workspace?.isPinned == true
            is NativeWorkspaceDisplayRow.Ssh -> it.row.workspace.isPinned
            is NativeWorkspaceDisplayRow.Cloud -> false }
    } else result
}
