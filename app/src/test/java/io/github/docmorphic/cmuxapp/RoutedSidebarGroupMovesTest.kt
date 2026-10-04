package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutedSidebarGroupMovesTest {
    private fun source(device: String) = NativeFeedSource(
        NativeCredentialStore.PairedMac("secret-$device", device, device, stableOrigin = device),
        availability = NativeFeedAvailability.CONNECTED, capabilities = setOf("workspace.move.v1"),
        groups = listOf(NativeGroup("g", "Group $device", false, false, "anchor", iconSymbol = "folder")),
        workspaces = parseWorkspaces(JSONObject("""{"workspaces":[
            {"id":"w","title":"Workspace $device","window_id":"win"},
            {"id":"anchor","title":"Anchor $device","window_id":"win","group_id":"g"},
            {"id":"child","title":"Child $device","window_id":"win","group_id":"g"}]}""")))
    private fun input(sources: List<NativeFeedSource>) = NativeSidebarInput(sources, emptyList(), sources.map {
        NativeSortComputer(workspaceMacFilterId(it.mac.deviceId, null)!!, it.mac.name)
    }, NativeWorkspaceSortState())
    private fun command(key: String, page: RoutedSidebarGroupPage, destination: String? = page.choices.first().key) =
        RoutedSidebarMutation(key, RoutedSidebarMutationKind.MOVE_TO_GROUP, menuRevision = page.revision, destination = destination)

    @Test fun menusResolveExactMacAndCurrentGroupWithoutSendingPrivateIdentifiers() = runTest {
        val value = input(listOf(source("A"), source("B")))
        var moved: Triple<String, String, NativeWorkspaceMove>? = null
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {},
            moveWorkspace = { source, id, intent, gate -> assertTrue(gate()); moved = Triple(source.mac.deviceId, id, intent) })
        val snapshot = host.read(RoutedSidebarQuery())!!
        val key = snapshot.rows.single { it.title == "Workspace B" }.key
        val page = host.groupMenu(key, null, 0)
        assertEquals("Group B", page.choices.single().name)
        assertFalse(page.canRemove); assertFalse(page.choices.single().current)
        val wire = RoutedSidebarGroupWire.encode(page)
        assertEquals(page, RoutedSidebarGroupWire.decode(wire))
        assertFalse(wire.contains("secret-")); assertFalse(wire.contains("anchor")); assertFalse(wire.contains("\"g\""))
        host.mutate(command(key, page)) { true }
        assertEquals(Triple("B", "w", NativeWorkspaceMove("g", null)), moved)
        val child = snapshot.rows.single { it.title == "Child B" }.key
        val current = host.groupMenu(child, null, 0)
        assertTrue(current.canRemove); assertTrue(current.choices.single().current); assertFalse(current.choices.single().enabled)
        assertTrue(runCatching { host.mutate(command(child, current)) { true } }.isFailure)
        host.mutate(command(child, current, null)) { true }
        assertEquals("child", moved!!.second); assertNull(moved!!.third.groupId)
        assertTrue(snapshot.rows.single { it.kind == "group" && it.title == "Group B" }.mutations.isEmpty())
    }

    @Test fun staleOrderAnchorOrPairingCannotReuseMenuAndCallerIsRechecked() = runTest {
        val original = input(listOf(source("A")))
        var value: NativeSidebarInput? = original
        var allowed = true; var gate: (() -> Boolean)? = null; var calls = 0
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {},
            moveWorkspace = { _, _, _, permission -> gate = permission; calls++ })
        val key = host.read(RoutedSidebarQuery())!!.rows.single { it.title == "Workspace A" }.key
        val page = host.groupMenu(key, null, 0)
        host.mutate(command(key, page)) { allowed }
        assertTrue(gate!!.invoke()); allowed = false; assertFalse(gate!!.invoke()); allowed = true
        val source = original.sources.single()
        val changes = listOf(source.copy(workspaces = source.workspaces.reversed()),
            source.copy(groups = source.groups.map { it.copy(anchorWorkspaceId = "child") }),
            source.copy(mac = source.mac.copy(deviceId = "replacement")),
            source.copy(mac = source.mac.copy(code = "repaired")),
            source.copy(availability = NativeFeedAvailability.OFFLINE))
        for (changed in changes) {
            value = input(listOf(changed))
            assertTrue(runCatching { host.mutate(command(key, page)) { true } }.isFailure)
            assertTrue(runCatching { host.groupMenu(key, page.revision, 0) }.isFailure)
        }
        value = null; assertFalse(gate!!.invoke()); assertEquals(1, calls)
    }

    @Test fun anchorRowsAndFullQueueDoNotAdvertiseMoveAndOtherMacDestinationIsRejected() = runTest {
        var value = input(listOf(source("A"), source("B")))
        var calls = 0
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {},
            moveWorkspace = { _, _, _, _ -> calls++ })
        val snapshot = host.read(RoutedSidebarQuery())!!
        val a = snapshot.rows.single { it.title == "Workspace A" }.key
        val b = snapshot.rows.single { it.title == "Workspace B" }.key
        val pageA = host.groupMenu(a, null, 0); val pageB = host.groupMenu(b, null, 0)
        assertTrue(runCatching { host.mutate(command(a, pageA, pageB.choices.first().key)) { true } }.isFailure)
        value = value.copy(pendingMoves = mapOf(value.sources.first().mac.origin to 3))
        assertTrue(host.read(RoutedSidebarQuery())!!.rows.single { it.key == a }.mutations.isEmpty())
        assertTrue(runCatching { host.groupMenu(a, null, 0) }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun byteBoundedPagesIssueOnlyEnabledTransmittedDestinations() {
        val choices = (0..180).map { RoutedSidebarGroupChoice("g$it", "界".repeat(1024), "folder", it == 0, it != 0) }
        val exchange = RoutedSidebarGroupExchange()
        var page = RoutedSidebarGroupWire.page("rev", choices, true, 0)
        assertTrue(page.choices.size < 100)
        val first = page
        exchange.issue("workspace", page)
        assertFalse(exchange.permits(command("workspace", page, "g0")))
        assertFalse(exchange.permits(command("workspace", page, "g180")))
        assertTrue(exchange.permits(command("workspace", page, null)))
        val all = page.choices.toMutableList()
        while (page.next != null) {
            page = RoutedSidebarGroupWire.decode(RoutedSidebarGroupWire.encode(
                RoutedSidebarGroupWire.page("rev", choices, true, page.next!!)))
            exchange.issue("workspace", page); all += page.choices
        }
        assertEquals(choices, all)
        assertTrue(exchange.permits(command("workspace", first, "g180")))
        assertFalse(exchange.permits(command("other", first, "g180")))
        exchange.issue("other", first.copy(revision = "new"))
        assertFalse(exchange.permits(command("workspace", first, "g180")))
        assertTrue(runCatching { RoutedSidebarGroupWire.decode(RoutedSidebarGroupWire.encode(first.copy(next = 0))) }.isFailure)
        assertTrue(runCatching { RoutedSidebarWire.mutation(RoutedSidebarMutation("workspace", RoutedSidebarMutationKind.CLOSE, menuRevision = "rev")) }.isFailure)
        assertTrue(runCatching { RoutedSidebarWire.mutation(RoutedSidebarMutation("workspace", RoutedSidebarMutationKind.MOVE_TO_GROUP)) }.isFailure)
    }

    @Test fun controllerCollectsEveryPageAndRejectsRevisionChangeOrHiddenMenu() = runTest {
        val choices = (0..240).map { RoutedSidebarGroupChoice("g$it", "Group $it", null, false, true) }
        val requests = mutableListOf<Int>(); var change = false
        val snapshot = RoutedSidebarSnapshot(emptyList(), listOf(RoutedSidebarRow("w", "workspace", "Workspace",
            mutations = setOf(RoutedSidebarMutationKind.MOVE_TO_GROUP))))
        val controller = RoutedSidebarController(backgroundScope, {}, { _, _, _ -> RoutedSidebarExchange().begin(snapshot) }, { "ticket" },
            readGroupMenu = { _, _, offset -> requests += offset; RoutedSidebarGroupWire.page(if (change && offset > 0) "changed" else "rev", choices, false, offset) })
        controller.initialize(RoutedSidebarQuery()); controller.configure(true, true); controller.visible(true); runCurrent()
        assertEquals(choices, controller.groupMenu("w").choices)
        assertEquals(listOf(0, 100, 200), requests)
        change = true
        assertTrue(runCatching { controller.groupMenu("w") }.isFailure)
        val count = requests.size
        controller.visible(false)
        assertTrue(runCatching { controller.groupMenu("w") }.isFailure)
        assertEquals(count, requests.size)
    }
}
