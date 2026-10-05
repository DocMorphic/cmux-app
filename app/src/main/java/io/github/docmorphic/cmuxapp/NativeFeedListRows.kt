package io.github.docmorphic.cmuxapp

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class NativeFeedDisclosure(val group: String, val count: Long, val expanded: Boolean)
internal sealed interface NativeFeedListRow {
    val key: String
    data class Notice(val text: String) : NativeFeedListRow { override val key = "feed:availability" }
    data class Heading(override val key: String, val text: String) : NativeFeedListRow
    data class Notification(val entry: NativeFeedEntry, val context: NativeFeedRowContext,
        val disclosure: NativeFeedDisclosure?) : NativeFeedListRow { override val key = "feed:item:${entry.id}" }
    data object More : NativeFeedListRow { override val key = "feed:more" }
    data class Empty(val text: String, val loading: Boolean, val retry: Boolean) : NativeFeedListRow { override val key = "feed:empty" }
}

/** One keyed item per displayed notification, preserving the projection's authoritative group IDs. */
internal fun nativeFeedListRows(projection: NativeFeedProjection, sources: Collection<NativeFeedSource>,
    unreadOnly: Boolean, searching: Boolean, now: Long, locale: Locale): List<NativeFeedListRow> = buildList {
    val unavailable = sources.filter { it.availability == NativeFeedAvailability.OFFLINE }
    val loading = sources.any { it.availability == NativeFeedAvailability.CONNECTING }
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    if (unavailable.isNotEmpty() && sources.any { it.items.isNotEmpty() })
        add(NativeFeedListRow.Notice("Unavailable: ${unavailable.joinToString { it.mac.name }}. Showing the last received updates."))
    projection.days.forEach { day ->
        add(NativeFeedListRow.Heading("feed:day:${day.date}", when (day.date) {
            null -> "Earlier"
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> day.date.format(DateTimeFormatter.ofPattern("EEEE, MMM d", locale))
        }))
        day.groups.forEach { group ->
            val expanded = group.id in projection.expanded
            val parent = group.entries.firstOrNull()?.rowValue(locale)
            (if (expanded) group.entries else group.entries.take(1)).forEachIndexed { index, entry ->
                add(NativeFeedListRow.Notification(entry, entry.rowValue(locale).nestedUnder(parent.takeIf { index > 0 }, locale),
                    NativeFeedDisclosure(group.id, group.entries.size.toLong(), expanded).takeIf { index == 0 && group.entries.size > 1 }))
            }
        }
    }
    if (projection.hasMore) add(NativeFeedListRow.More)
    if (projection.days.isEmpty()) add(NativeFeedListRow.Empty(when {
        loading -> "Loading notifications…"
        searching -> "No matching notifications."
        unreadOnly -> "No unread notifications."
        unavailable.isNotEmpty() -> "Notifications are unavailable. Reconnect to a computer and try again."
        else -> "No notifications yet."
    }, loading, unavailable.isNotEmpty()))
}
