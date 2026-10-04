package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeSidebarHistoryTest {
    private val mac = NativeCredentialStore.PairedMac("private-pairing", "device", "Mac", stableOrigin = "fixture-origin")
    private fun source(ids: List<String> = listOf("new", "middle", "old")) = NativeFeedSource(mac,
        items = ids.mapIndexed { i, id -> NativeNotification(id, "w", null, "Agent", id, false, createdAt = 1791100800.0 - i * 60) },
        workspaces = listOf(NativeWorkspace("w", "Task", emptyList(), null, false, null, null, false, emptyList(), null, null, null)), availability = NativeFeedAvailability.CONNECTED,
        groups = listOf(NativeGroup("g", "Group", false, false)))
    private fun input(source: NativeFeedSource) = NativeSidebarInput(listOf(source), emptyList(),
        listOf(NativeSortComputer(workspaceMacFilterId(source.mac.deviceId, null)!!, "Mac")), NativeWorkspaceSortState())
    private val query = RoutedSidebarQuery(notifications = true)
    private fun NativeFeedProjection.members() = days.flatMap { it.groups }.filter { it.id in expanded }.flatMap { it.entries }.map { it.notification.id }.toSet()

    @Test fun retainedMembersStayExpandedAfterAnchorRemovalAndExplicitCollapseWins() {
        var value = input(source())
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {})
        val key = host.read(query)!!.rows.single { it.kind == "updates" }.key
        val opened = query.copy(expanded = setOf(key))
        assertEquals(3, host.read(opened)!!.rows.count { it.kind == "notification" })
        value = input(source(listOf("new", "middle")))
        val retained = host.read(opened)!!
        assertEquals(2, retained.rows.count { it.kind == "notification" })
        assertNotEquals(setOf(key), retained.expanded)
        assertEquals(retained, host.read(opened)) // Stale in-flight query cannot undo reconciliation.
        val acknowledged = opened.copy(expanded = retained.expanded)
        assertEquals(retained, host.read(acknowledged))
        assertEquals(1, host.read(acknowledged.copy(expanded = emptySet()))!!.rows.count { it.kind == "notification" })
        assertTrue(host.read(query)!!.expanded.isEmpty())
    }

    @Test fun mainBrowserMainRoundTripSurvivesHostRecreationWithExactPairings() {
        var value = input(source())
        val entries = aggregateNativeFeed(value.sources)
        val collapsed = NativeFeedProjection.build(entries, false, entries.map { it.id }.toSet(), ZoneId.systemDefault(), 100, NativeFeedProjection())
        var presentation = NativeSidebarPresentation(notifications = true,
            projection = collapsed.toggle(collapsed.days.single().groups.single().id), collapsedGroups = mapOf("${mac.origin}:group:g" to true))
        val history = NativeSidebarHistory()
        fun host() = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {},
            initial = { presentation }, adoptPresentation = { presentation = it }, history = history)
        val a = host(); val initial = RoutedSidebarWire.query(RoutedSidebarWire.query(a.initialQuery()))
        assertEquals(3, a.read(initial)!!.rows.count { it.kind == "notification" })
        assertFalse(RoutedSidebarWire.query(initial).contains("private-pairing"))
        assertEquals(listOf(false), initial.groupExpansion.values.toList())
        value = input(source(listOf("new", "middle")))
        val b = host(); val next = b.read(initial)!!
        b.adopt(initial.copy(expanded = next.expanded, groupExpansion = initial.groupExpansion.mapValues { true }))
        assertEquals(setOf("new", "middle"), presentation.projection.members())
        assertEquals(mapOf("${mac.origin}:group:g" to false), presentation.collapsedGroups)
        val reopened = host(); val again = reopened.initialQuery()
        assertEquals(2, reopened.read(again)!!.rows.count { it.kind == "notification" })
    }

    @Test fun replacementPairingAndDifferentOwnerDoNotInheritExpansionOrGroupOverrides() {
        var value = input(source()); var adopted: NativeSidebarPresentation? = null
        val history = NativeSidebarHistory()
        fun host(owner: String) = NativeRoutedSidebarHost(owner, "salt", { value }, { RoutedSidebarLease({}) {} }, {},
            adoptPresentation = { adopted = it }, history = history)
        val first = host("one")
        val groupKey = first.read(RoutedSidebarQuery())!!.rows.single { it.kind == "group" }.key
        val rows = first.read(query)!!.rows
        val opened = query.copy(expanded = setOf(rows.single { it.kind == "updates" }.key), groupExpansion = mapOf(groupKey to true))
        first.read(opened)
        assertTrue(host("two").read(opened)!!.expanded.isEmpty())
        first.read(query); first.read(opened)
        value = input(source().copy(mac = mac.copy(code = "replacement")))
        assertTrue(first.read(opened)!!.expanded.isEmpty())
        first.adopt(opened)
        assertTrue(adopted!!.projection.expanded.isEmpty())
        assertTrue(adopted!!.collapsedGroups.isEmpty())
    }

    @Test fun filteredOutMembersAndMissingScopeDoNotResurrectExpansion() {
        val value = input(source())
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {})
        val key = host.read(query)!!.rows.single { it.kind == "updates" }.key
        val opened = query.copy(expanded = setOf(key)); host.read(opened)
        assertTrue(host.read(opened.copy(notificationQuery = "absent"))!!.expanded.isEmpty())
        assertTrue(host.read(opened)!!.expanded.isEmpty())
        host.read(query); host.read(opened)
        assertTrue(host.read(opened.copy(computer = "removed"))!!.expanded.isEmpty())
        assertTrue(host.read(opened)!!.expanded.isEmpty())
    }

    @Test fun controllerAdoptsAuthoritativeExpansionAndWirePreservesIt() = runTest {
        var submitted: RoutedSidebarQuery? = null
        val value = RoutedSidebarSnapshot(emptyList(), emptyList(), expanded = setOf("retained"))
        val controller = RoutedSidebarController(backgroundScope, {}, { q, _, _ ->
            submitted = q
            val page = RoutedSidebarExchange().begin(value)
            RoutedSidebarWire.page(RoutedSidebarWire.page(page))
        }, { "ticket" })
        controller.initialize(query.copy(expanded = setOf("old"))); controller.configure(true, true); controller.visible(true); runCurrent()
        assertEquals(setOf("retained"), controller.state.value.query.expanded)
        advanceTimeBy(1500); runCurrent()
        assertEquals(setOf("retained"), submitted!!.expanded)
    }
}
