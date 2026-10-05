package io.github.docmorphic.cmuxapp

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class NativeFeedListRowsTest {
    private val source = NativeFeedSource(NativeCredentialStore.PairedMac("fixture", "fixture", "Mac"),
        availability = NativeFeedAvailability.CONNECTED)
    private fun entry(id: String, seconds: Double, owner: NativeFeedSource = source) = NativeFeedEntry(owner,
        NativeNotification(id, "workspace", "surface", "Build", "Update $id", false, createdAt = seconds))
    private fun project(entries: List<NativeFeedEntry>, previous: NativeFeedProjection = NativeFeedProjection()) =
        NativeFeedProjection.build(entries, false, entries.map { it.id }.toSet(), ZoneId.systemDefault(), 300, previous)
    private fun rows(projection: NativeFeedProjection, sources: List<NativeFeedSource> = listOf(source)) =
        nativeFeedListRows(projection, sources, false, false, 100_000, Locale.US)

    @Test fun expandedHistoryKeepsNotificationAndGroupIdentityAcrossNewArrivals() {
        val original = listOf(entry("newest", 90.0), entry("older", 80.0))
        val collapsed = project(original)
        val group = collapsed.days.single().groups.single().id
        assertEquals(1, rows(collapsed).filterIsInstance<NativeFeedListRow.Notification>().size)
        val expanded = collapsed.toggle(group)
        val oldRows = rows(expanded).filterIsInstance<NativeFeedListRow.Notification>()
        val updated = rows(project(listOf(entry("arrived", 100.0)) + original, expanded)).filterIsInstance<NativeFeedListRow.Notification>()
        assertEquals(oldRows.map { it.key }, updated.drop(1).map { it.key })
        assertEquals(group, updated.first().disclosure?.group)
        assertEquals(3L, updated.first().disclosure?.count)
        assertTrue(updated.drop(1).all { it.context.nested && it.disclosure == null })
    }
    @Test fun identicalRemoteIdsOnDifferentMacsRemainDistinctFromChrome() {
        val other = source.copy(mac = NativeCredentialStore.PairedMac("other", "other", "Other"))
        val entries = listOf(entry("availability", 90.0), entry("availability", 80.0, other))
        val rendered = rows(project(entries).copy(hasMore = true), listOf(source, other))
        assertEquals(2, rendered.filterIsInstance<NativeFeedListRow.Notification>().size)
        assertEquals(rendered.size, rendered.map { it.key }.toSet().size)
        assertEquals(NativeFeedListRow.More, rendered.last())
    }
    @Test fun emptyRecoveryAndLoadingDoNotInventAnAvailabilityBanner() {
        val offline = source.copy(availability = NativeFeedAvailability.OFFLINE)
        val empty = rows(NativeFeedProjection(), listOf(offline)).single() as NativeFeedListRow.Empty
        assertTrue(empty.retry); assertFalse(empty.loading)
        val loading = rows(NativeFeedProjection(), listOf(offline.copy(availability = NativeFeedAvailability.CONNECTING))).single() as NativeFeedListRow.Empty
        assertTrue(loading.loading); assertFalse(loading.retry)
        val cached = offline.copy(items = listOf(entry("cached", 90.0).notification))
        assertTrue(rows(NativeFeedProjection(), listOf(cached)).first() is NativeFeedListRow.Notice)
    }
}
