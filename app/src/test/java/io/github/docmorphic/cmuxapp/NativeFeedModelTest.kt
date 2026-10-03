package io.github.docmorphic.cmuxapp

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class NativeFeedModelTest {
    private val zone = ZoneId.of("America/New_York")
    private fun workspace(id: String = "w", surface: String = "s") = NativeWorkspace(id, id,
        listOf(NativeTerminal(surface, surface)), null, false, null, null, false, emptyList(), null, null, null)
    private val noon = Instant.parse("2026-09-27T16:00:00Z").epochSecond.toDouble()
    private fun entry(id: String, time: Double? = noon, mac: String = "a", workspace: String = "w", read: Boolean = false) =
        NativeFeedEntry(NativeFeedSource(NativeCredentialStore.PairedMac(mac, mac, "Mac $mac")),
            NativeNotification(id, workspace, "s", "Ready", id, read, createdAt = time))
    private fun build(entries: List<NativeFeedEntry>, previous: NativeFeedProjection = NativeFeedProjection(),
        unread: Boolean = false, limit: Int = 300) = NativeFeedProjection.build(entries, unread,
            entries.map { it.id }.toSet(), zone, limit, previous)

    @Test fun aggregationKeepsIdenticalRemoteIdsOnDifferentMacsAndBoundsGlobalHistory() {
        fun source(mac: String) = NativeFeedSource(NativeCredentialStore.PairedMac(mac, mac, mac),
            (0 until 1500).map { entry(it.toString(), noon - it, mac).notification }, listOf(workspace()))
        val first = aggregateNativeFeed(listOf(source("b"), source("a")))
        assertEquals(2000, first.size)
        assertEquals(2000, first.map { it.id }.toSet().size)
        assertEquals(listOf("a", "b"), first.take(2).map { it.source.mac.deviceId })
        assertEquals(first, aggregateNativeFeed(listOf(source("a"), source("b"))))
    }

    @Test fun computerScopeAndDestinationVisibilityPrecedeTheGlobalCap() {
        val a = NativeFeedSource(NativeCredentialStore.PairedMac("a", "a", "A"),
            (0 until 2000).map { entry("a$it", noon - it).notification }, listOf(workspace()))
        val b = NativeFeedSource(NativeCredentialStore.PairedMac("b", "b", "B"),
            (0 until 5).map { entry("b$it", noon - 3000 - it).notification }, listOf(workspace()))
        assertEquals(2000, aggregateNativeFeed(listOf(a, b)).size)
        assertEquals(5, aggregateNativeFeed(listOf(a, b), b.mac.origin).size)
        val removed = a.copy(workspaces = emptyList())
        assertEquals(5, aggregateNativeFeed(listOf(removed, b)).size)
        assertEquals(2000, removed.items.size)
        assertTrue(aggregateNativeFeed(listOf(a, b), "forgotten-origin").isEmpty())
    }

    @Test fun liveDestinationFilteringUsesProvenanceAndRejectsAmbiguousOwners() {
        val source = NativeFeedSource(NativeCredentialStore.PairedMac("a", "a", "A"), listOf(
            entry("moved", workspace = "removed").notification.copy(retargetsToLiveSurfaceOwner = true),
            entry("historical", workspace = "removed").notification,
            entry("workspace-fallback").notification.copy(surfaceId = "removed")), listOf(workspace()))
        assertEquals(listOf("moved", "workspace-fallback"), aggregateNativeFeed(listOf(source)).map { it.notification.id })
        val ambiguous = source.copy(workspaces = listOf(workspace(), workspace("another")))
        assertEquals(listOf("workspace-fallback"), aggregateNativeFeed(listOf(ambiguous)).map { it.notification.id })
    }

    @Test fun computerScopeKeepsSiblingInstallationsSeparateAndRetainsOfflineRows() {
        val mac = NativeCredentialStore.PairedMac("a", "same-device", "Mac", "release")
        val a = NativeFeedSource(mac, listOf(entry("same").notification), listOf(workspace()), NativeFeedAvailability.OFFLINE)
        val b = a.copy(mac = mac.copy(code = "b", instanceTag = "nightly"))
        assertEquals(2, aggregateNativeFeed(listOf(a, b)).size)
        assertEquals(listOf(a), aggregateNativeFeed(listOf(a, b), a.mac.origin).map { it.source })
        assertEquals(listOf(b), aggregateNativeFeed(listOf(a, b), b.mac.origin).map { it.source })
    }

    @Test fun consecutivePaneHistoryUsesLatestTimestampAndTwoHourBoundary() {
        val entries = listOf(entry("a"), entry("b", noon - 3600), entry("c", noon - 7200), entry("d", noon - 7201),
            entry("e", noon - 7300, workspace = "other"), entry("f", noon - 7400))
        assertEquals(listOf(3, 1, 1, 1), build(entries).days.single().groups.map { it.entries.size })
        assertEquals("c", build(entries).days.single().groups.first().entries.last().notification.id)
    }

    @Test fun macsSurfacesAndInterveningNotificationsPreventGrouping() {
        val entries = listOf(entry("a"), entry("b", noon - 1, mac = "b"), entry("c", noon - 2),
            entry("d", noon - 3).let { it.copy(notification = it.notification.copy(surfaceId = "other")) })
        assertEquals(4, build(entries).days.single().groups.size)
    }

    @Test fun dayBucketsRespectLocalMidnightAndDaylightSaving() {
        val entries = listOf(entry("a", Instant.parse("2026-11-01T06:30:00Z").epochSecond.toDouble()),
            entry("b", Instant.parse("2026-11-01T05:30:00Z").epochSecond.toDouble()),
            entry("c", Instant.parse("2026-11-01T03:59:00Z").epochSecond.toDouble()), entry("unknown", null))
        val projection = build(entries)
        assertEquals(listOf("2026-11-01", "2026-10-31", null), projection.days.map { it.date?.toString() })
        assertEquals(2, projection.days.first().groups.single().entries.size)
        assertEquals(1, projection.days.last().groups.single().entries.size)
    }

    @Test fun expansionAndAnchorSurviveNewArrivalsAndRetention() {
        val original = listOf(entry("a"), entry("b", noon - 60), entry("c", noon - 120))
        val collapsed = build(original)
        val anchor = collapsed.days.single().groups.single().id
        val expanded = collapsed.toggle(anchor)
        val updated = build(listOf(entry("new", noon + 60)) + original, expanded)
        assertEquals(anchor, updated.days.single().groups.single().id)
        assertTrue(anchor in updated.expanded)
        val trimmed = build(original.take(2), updated)
        assertEquals(1, trimmed.expanded.size)
        assertEquals(trimmed.days.single().groups.single().id, trimmed.expanded.single())
        assertTrue(build(listOf(entry("unrelated")), trimmed).expanded.isEmpty())
    }

    @Test fun filteringPrecedesGroupingAndRowWindowKeepsHistoryExpandable() {
        val entries = listOf(entry("a"), entry("read", noon - 1, workspace = "other", read = true), entry("b", noon - 2))
        assertEquals(3, build(entries).days.single().groups.size)
        assertEquals(2, build(entries, unread = true).days.single().groups.single().entries.size)
        val bounded = build(entries, limit = 1)
        assertTrue(bounded.hasMore)
        assertEquals(1, bounded.days.single().groups.single().entries.size)
        assertFalse(build(entries, bounded, limit = 300).hasMore)
    }
}
