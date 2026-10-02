package io.github.docmorphic.cmuxapp

/** Policy edits preserve inactive options and disconnected priority keys, as in the iOS size sheet. */
internal class TerminalSizingPresentation(val state: TerminalSizeState, val selfId: String?) {
    val self get() = state.participants.firstOrNull { it.id == selfId }
    val others get() = state.participants.filter { it.id != selfId }
    val rows: List<TerminalSizeParticipant> get() {
        val rows = listOfNotNull(self) + others
        if (state.policy.mode != TerminalSizeMode.PRIORITY) return rows
        val rank = state.policy.priority.withIndex().groupBy({ it.value }, { it.index }).mapValues { it.value.first() }
        return rows.sortedBy { rank[it.priorityKey] ?: Int.MAX_VALUE }
    }
    fun title(row: TerminalSizeParticipant): String = if (row.id == selfId) "This phone" else
        row.deviceName?.takeIf(String::isNotBlank) ?: row.displayName.takeIf(String::isNotBlank) ?: "Connected device"
    val ownerLabel: String get() = state.owners.mapNotNull { id -> state.participants.find { it.id == id } }
        .joinToString(", ", transform = ::title).ifBlank { state.policy.mode.title }
    fun select(mode: TerminalSizeMode) = state.policy.copy(mode = mode,
        fixed = state.policy.fixed ?: state.grid.takeIf { mode == TerminalSizeMode.FIXED },
        priority = if (mode == TerminalSizeMode.PRIORITY && state.policy.priority.isEmpty())
            (listOfNotNull(self) + others).map { it.priorityKey }.distinct() else state.policy.priority)
    fun fixed(columns: String, rows: String): TerminalSizePolicy? {
        val cols = columns.toIntOrNull() ?: return null
        val lines = rows.toIntOrNull() ?: return null
        return state.policy.copy(mode = TerminalSizeMode.FIXED,
            fixed = SharedTerminalGrid(cols.coerceIn(20, 300), lines.coerceIn(5, 120)))
    }
    /** Destination is the insertion point in the original list, before removing the source. */
    fun move(id: String, destination: Int): TerminalSizePolicy {
        val rows = rows.toMutableList()
        val source = rows.indexOfFirst { it.id == id }
        require(source >= 0 && destination in 0..rows.size)
        val row = rows.removeAt(source)
        rows.add((if (destination > source) destination - 1 else destination).coerceIn(0, rows.size), row)
        val ranked = rows.map { it.priorityKey }.distinct()
        return state.policy.copy(mode = TerminalSizeMode.PRIORITY,
            priority = ranked + state.policy.priority.filter { it !in ranked }.distinct())
    }
}

internal sealed interface TerminalSizingAction {
    data class Policy(val policy: TerminalSizePolicy) : TerminalSizingAction
    data class Counts(val counts: Boolean?) : TerminalSizingAction
    /** Captured IDs only: newly attached participants are never included in an older confirmation. */
    data class Disconnect(val ids: List<String>) : TerminalSizingAction
}
