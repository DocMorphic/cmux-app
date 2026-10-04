package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutedSidebarDropTest {
    private fun source(device: String = "A") = NativeFeedSource(
        NativeCredentialStore.PairedMac("secret-$device", device, "Mac $device", stableOrigin = device),
        availability = NativeFeedAvailability.CONNECTED, capabilities = setOf("workspace.move.v1"),
        workspaces = parseWorkspaces(JSONObject("""{"workspaces":[
            {"id":"first","title":"First $device","window_id":"window"},
            {"id":"second","title":"Second $device","window_id":"window"},
            {"id":"third","title":"Third $device","window_id":"window"}]}""")))
    private fun input(vararg sources: NativeFeedSource) = NativeSidebarInput(sources.toList(), emptyList(), sources.map {
        NativeSortComputer(workspaceMacFilterId(it.mac.deviceId, null)!!, it.mac.name)
    }, NativeWorkspaceSortState())
    private fun host(input: () -> NativeSidebarInput?, move: suspend (NativeFeedSource, String, NativeWorkspaceMove, () -> Boolean) -> Unit) =
        NativeRoutedSidebarHost("owner", "salt", input, { RoutedSidebarLease({}) {} }, {}, moveWorkspace = move)
    private fun command(snapshot: RoutedSidebarSnapshot, key: String = snapshot.rows.first().key,
        place: RoutedSidebarDropPlacement = RoutedSidebarDropPlacement.AFTER, target: String? = snapshot.rows.last().key) =
        RoutedSidebarDrop(checkNotNull(snapshot.dragRevision), key, place, target)

    @Test fun flatDropAndAccessibleStepResolveExactScopedMac() = runTest {
        var value = input(source(), source("B")); val calls = mutableListOf<Triple<String, String, NativeWorkspaceMove>>()
        val host = host({ value }) { source, key, intent, gate -> assertTrue(gate()); calls += Triple(source.mac.deviceId, key, intent) }
        val all = host.read(RoutedSidebarQuery())!!; assertNull(all.dragRevision)
        val query = RoutedSidebarQuery(computer = all.computers.single { it.name == "Mac B" }.key)
        val snapshot = host.read(query)!!
        assertTrue(snapshot.rows.first().drag!!.down); assertFalse(snapshot.rows.first().drag!!.up)
        host.drop(command(snapshot), query) { true }
        assertEquals(Triple("B", "first", NativeWorkspaceMove(null, null)), calls.last())
        host.drop(command(snapshot, place = RoutedSidebarDropPlacement.DOWN, target = null), query) { true }
        assertEquals(NativeWorkspaceMove(null, "third"), calls.last().third)
        value = input(source(), source("B").copy(mac = source("B").mac.copy(code = "repaired")))
        assertTrue(runCatching { host.drop(command(snapshot), query) { true } }.isFailure)
        assertEquals(2, calls.size)
    }
    @Test fun collapsedGroupReceivesWorkspaceAndAnchorMovesWholeGroup() = runTest {
        val base = source()
        val grouped = base.copy(groups = listOf(NativeGroup("g", "Group", true, false, "second")),
            workspaces = base.workspaces.map { if (it.id == "first") it else it.copy(groupId = "g") })
        val value = input(grouped); val moves = mutableListOf<Pair<String, NativeWorkspaceMove>>()
        val host = host({ value }) { _, key, move, _ -> moves += key to move }
        val query = RoutedSidebarQuery(); val snapshot = host.read(query)!!
        assertEquals(listOf("workspace", "group"), snapshot.rows.map { it.kind })
        host.drop(command(snapshot, place = RoutedSidebarDropPlacement.INTO), query) { true }
        assertEquals("first" to NativeWorkspaceMove("g", null), moves.last())
        host.drop(command(snapshot, snapshot.rows.last().key, RoutedSidebarDropPlacement.BEFORE, snapshot.rows.first().key), query) { true }
        assertEquals("second", moves.last().first); assertTrue(moves.last().second.movesGroup)
        assertTrue(runCatching { host.drop(command(snapshot, snapshot.rows.last().key, RoutedSidebarDropPlacement.INTO, snapshot.rows.last().key), query) { true } }.isFailure)
    }
    @Test fun gatesFilteringRecencyWindowsPinsOfflineAndFullQueue() {
        val base = source(); var value = input(base)
        val host = host({ value }) { _, _, _, _ -> fail() }
        for (query in listOf(RoutedSidebarQuery(notifications = true), RoutedSidebarQuery(workspaceQuery = "First"),
            RoutedSidebarQuery(workspaceUnread = true), RoutedSidebarQuery(computer = "missing")))
            assertNull(host.read(query)!!.dragRevision)
        value = input(base, source("B"))
        assertNull(host.read(RoutedSidebarQuery(machines = setOf(host.read(RoutedSidebarQuery())!!.computers.first().key)))!!.dragRevision)
        value = input(base)
        val changes = listOf(base.copy(capabilities = emptySet()), base.copy(availability = NativeFeedAvailability.OFFLINE),
            base.copy(workspaces = base.workspaces.map { it.copy(windowId = null) }),
            base.copy(workspaces = base.workspaces.map { it.copy(windowId = it.id) }),
            base.copy(workspaces = base.workspaces.map { it.copy(isPinned = it.id == "first") }))
        for (changed in changes) { value = input(changed); assertNull(host.read(RoutedSidebarQuery())!!.dragRevision) }
        value = input(base).copy(pendingMoves = mapOf(base.mac.origin to 3)); assertNull(host.read(RoutedSidebarQuery())!!.dragRevision)
        value = input(base).copy(pendingMoves = mapOf(base.mac.origin to 2)); assertNotNull(host.read(RoutedSidebarQuery())!!.dragRevision)
        value = input(base).copy(sort = NativeWorkspaceSortState(rawMode = NativeWorkspaceSortMode.ACTIVITY.raw))
        val all = host.read(RoutedSidebarQuery())!!; assertNull(all.dragRevision)
        assertNotNull(host.read(RoutedSidebarQuery(computer = all.computers.first().key))!!.dragRevision)
    }
    @Test fun revisionTracksOrderButNotPreviewAndQueuedGuardAllowsOptimisticPrediction() = runTest {
        val base = source(); var value: NativeSidebarInput? = input(base); var allowed = true; var guard: (() -> Boolean)? = null
        val host = host({ value }) { _, _, _, gate -> guard = gate }
        val query = RoutedSidebarQuery(); val snapshot = host.read(query)!!
        value = input(base.copy(workspaces = base.workspaces.map { it.copy(preview = "new content") }))
        assertEquals(snapshot.dragRevision, host.read(query)!!.dragRevision)
        host.drop(command(snapshot), query) { allowed }; assertTrue(guard!!.invoke())
        value = input(base.copy(workspaces = base.workspaces.reversed())).copy(pendingMoves = mapOf(base.mac.origin to 3))
        assertTrue(guard!!.invoke()) // The shared queue owns base-order validation and its three slots.
        assertTrue(runCatching { host.drop(command(snapshot), query) { true } }.isFailure)
        allowed = false; assertFalse(guard!!.invoke()); allowed = true
        value = input(base.copy(availability = NativeFeedAvailability.OFFLINE)); assertFalse(guard!!.invoke())
        value = null; assertFalse(guard!!.invoke())
    }
    @Test fun wireAndPagingAdmitOnlyIssuedRowsFromCurrentDragRevision() {
        val rows = (0..101).map { RoutedSidebarRow("row-$it", "workspace", "Row", drag = RoutedSidebarDragRow(up = it > 0, down = it < 101)) }
        val exchange = RoutedSidebarExchange(); val page = exchange.begin(RoutedSidebarSnapshot(emptyList(), rows, dragRevision = "drag"))
        assertEquals(page, RoutedSidebarWire.page(RoutedSidebarWire.page(page)))
        val drop = RoutedSidebarDrop("drag", "row-0", RoutedSidebarDropPlacement.AFTER, "row-101")
        assertEquals(drop, RoutedSidebarDropWire.decode(RoutedSidebarDropWire.encode(drop)))
        assertFalse(exchange.permitsDrop(drop)); exchange.page(page.revision, page.next!!); assertTrue(exchange.permitsDrop(drop))
        assertFalse(exchange.permitsDrop(drop.copy(placement = RoutedSidebarDropPlacement.INTO)))
        assertFalse(exchange.permitsDrop(drop.copy(placement = RoutedSidebarDropPlacement.UP, target = null)))
        exchange.begin(RoutedSidebarSnapshot(emptyList(), rows, dragRevision = "changed")); assertFalse(exchange.permitsDrop(drop))
        exchange.clear(); assertFalse(exchange.permitsDrop(drop))
        for (invalid in listOf(drop.copy(revision = ""), drop.copy(target = null), drop.copy(placement = RoutedSidebarDropPlacement.DOWN)))
            assertTrue(runCatching { RoutedSidebarDropWire.encode(invalid) }.isFailure)
        val legacy = JSONObject(RoutedSidebarWire.page(page)).apply { remove("drag_revision"); getJSONArray("rows").getJSONObject(0).remove("drag") }
        val decoded = RoutedSidebarWire.page(legacy.toString()); assertNull(decoded.snapshot.dragRevision); assertNull(decoded.snapshot.rows.first().drag)
    }
    @Test fun opaqueProjectionDoesNotExposeCredentialsWindowOrNativeIds() {
        val value = input(source()); val host = host({ value }) { _, _, _, _ -> }
        val page = RoutedSidebarExchange().begin(host.read(RoutedSidebarQuery())!!)
        val wire = RoutedSidebarWire.page(page) + RoutedSidebarDropWire.encode(command(page.snapshot))
        assertFalse(wire.contains("secret-A")); assertFalse(wire.contains("window")); assertFalse(wire.contains("\"first\""))
    }
}
