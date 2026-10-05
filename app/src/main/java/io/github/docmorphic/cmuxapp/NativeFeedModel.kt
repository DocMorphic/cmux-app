package io.github.docmorphic.cmuxapp

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

internal enum class NativeFeedAvailability { CONNECTING, CONNECTED, OFFLINE }
internal data class NativeFeedSource(
    val mac: NativeCredentialStore.PairedMac,
    val items: List<NativeNotification> = emptyList(),
    val workspaces: List<NativeWorkspace> = emptyList(),
    val availability: NativeFeedAvailability = NativeFeedAvailability.CONNECTING,
    val revision: Long = -1,
    val error: String? = null,
    val groups: List<NativeGroup> = emptyList(),
    val capabilities: Set<String> = emptySet(),
    val hasWorkspaceSnapshot: Boolean = false,
    val keepAwake: Boolean? = null,
    val changes: Map<String, WorkspaceChangesChip> = emptyMap(),
    val macMutationTicket: NativeMacMutationTicket? = null,
    // Identity of the last authorized panel snapshot; never authorizes network requests.
    val panelCacheToken: Any? = null
)
internal data class NativeFeedEntry(val source: NativeFeedSource, val notification: NativeNotification,
    val displayComputer: String? = null) {
    val id = source.mac.origin + ":" + notification.id
    val computer get() = displayComputer ?: source.mac.name.ifBlank { "cmux" }
    fun presentation(locale: Locale) = notification.presentation(source.workspaces, computer, locale)
    fun searchFields() = notification.searchFields(source.workspaces, computer)
}

/** Scope and live destinations precede the cap; retained source snapshots remain untouched. */
internal fun aggregateNativeFeed(sources: Collection<NativeFeedSource>, selectedOrigin: String? = null,
    computerName: (NativeCredentialStore.PairedMac) -> String? = { null }): List<NativeFeedEntry> =
    sources.filter { selectedOrigin == null || it.mac.origin == selectedOrigin }
        .flatMap { source -> source.items.filter { it.destination(source.workspaces) != null }
            .map { NativeFeedEntry(source, it, computerName(source.mac)) } }
        .sortedWith(compareByDescending<NativeFeedEntry> { it.notification.createdAt ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.source.mac.deviceId }.thenBy { it.source.mac.instanceTag.orEmpty() }
            .thenBy { it.notification.id }.thenBy { it.source.mac.origin })
        .distinctBy { it.id }.take(2_000)

internal data class NativeFeedGroup(val id: String, val entries: List<NativeFeedEntry>)
internal data class NativeFeedDay(val date: LocalDate?, val groups: List<NativeFeedGroup>)
internal data class NativeFeedProjection(
    val days: List<NativeFeedDay> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val hasMore: Boolean = false
) {
    fun toggle(id: String) = copy(expanded = if (id in expanded) expanded - id else expanded + id)

    companion object {
        /** iOS groups consecutive updates on one pane within two hours, separately per local day. */
        fun build(entries: List<NativeFeedEntry>, unreadOnly: Boolean, matches: Set<String>,
            zone: ZoneId, rowWindow: Int, previous: NativeFeedProjection): NativeFeedProjection {
            val filtered = entries.filter { (!unreadOnly || !it.notification.isRead) && it.id in matches }
            val previousGroups = previous.days.flatMap { it.groups }
            val anchors = previousGroups.map { it.id }.toSet()
            val expandedMembers = previousGroups.filter { it.id in previous.expanded }
                .flatMap { it.entries.map(NativeFeedEntry::id) }.toSet()
            val days = filtered.take(rowWindow.coerceAtLeast(0)).groupBy { entry ->
                entry.notification.createdAt?.let { seconds -> runCatching {
                    Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(zone).toLocalDate()
                }.getOrNull() }
            }.map { (day, items) ->
                val groups = mutableListOf<MutableList<NativeFeedEntry>>()
                items.forEach { entry ->
                    val latest = groups.lastOrNull()?.firstOrNull()
                    val firstTime = latest?.notification?.createdAt
                    val time = entry.notification.createdAt
                    if (latest != null && firstTime != null && time != null &&
                        latest.source.mac.origin == entry.source.mac.origin &&
                        latest.notification.workspaceId == entry.notification.workspaceId &&
                        latest.notification.surfaceId == entry.notification.surfaceId &&
                        firstTime - time in 0.0..7200.0) groups.last().add(entry)
                    else groups.add(mutableListOf(entry))
                }
                NativeFeedDay(day, groups.map { group ->
                    val anchor = group.firstOrNull { it.id in previous.expanded }
                        ?: group.firstOrNull { it.id in anchors } ?: group.last()
                    NativeFeedGroup(anchor.id, group.toList())
                })
            }
            val expanded = days.flatMap { it.groups }.filter { group ->
                group.entries.any { it.id in expandedMembers }
            }.map { it.id }.toSet()
            return NativeFeedProjection(days, expanded, filtered.size > rowWindow)
        }
    }
}
