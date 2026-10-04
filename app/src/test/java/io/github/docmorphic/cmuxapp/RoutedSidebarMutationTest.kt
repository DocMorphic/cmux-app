package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutedSidebarMutationTest {
    private fun source(device: String) = NativeFeedSource(
        NativeCredentialStore.PairedMac("private-$device", device, device, stableOrigin = device),
        workspaces = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"w","title":"Workspace $device","has_unread":true}]}""")),
        availability = NativeFeedAvailability.CONNECTED,
        groups = listOf(NativeGroup("g", "Group $device", false, false)),
        capabilities = setOf("workspace.actions.v1", "workspace.close.v1", "workspace.read_state.v1", "workspace.group_actions.v1", WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY))
    private fun input(sources: List<NativeFeedSource>) = NativeSidebarInput(sources, emptyList(),
        sources.map { NativeSortComputer(workspaceMacFilterId(it.mac.deviceId, null)!!, it.mac.name) }, NativeWorkspaceSortState())

    @Test fun wireAdmitsOnlyExplicitOperationsAndBoundedRenameTitles() {
        for (kind in RoutedSidebarMutationKind.entries) {
            val command = RoutedSidebarMutation("opaque", kind, "New name".takeIf { kind == RoutedSidebarMutationKind.RENAME },
                menuRevision = "menu".takeIf { kind == RoutedSidebarMutationKind.MOVE_TO_GROUP })
            assertEquals(command, RoutedSidebarWire.mutation(RoutedSidebarWire.mutation(command)))
        }
        for (bad in listOf("""{"key":"opaque","kind":"RUN_SHELL"}""", """{"key":"opaque","kind":"CLOSE","title":"injected"}""",
            """{"key":"opaque","kind":"RENAME","title":" "}"""))
            assertTrue(runCatching { RoutedSidebarWire.mutation(bad) }.isFailure)
        assertTrue(runCatching { RoutedSidebarWire.mutation(RoutedSidebarMutation("key", RoutedSidebarMutationKind.RENAME, "x".repeat(4097))) }.isFailure)
    }
    @Test fun onlyTransmittedRowsAndStillAdvertisedActionsCanMutate() {
        val rows = (0..120).map { RoutedSidebarRow("w$it", "workspace", "Workspace", canOpen = false,
            mutations = setOf(RoutedSidebarMutationKind.CLOSE)) }
        val exchange = RoutedSidebarExchange()
        val first = exchange.begin(RoutedSidebarSnapshot(emptyList(), rows))
        assertEquals(first, RoutedSidebarWire.page(RoutedSidebarWire.page(first)))
        assertTrue(exchange.permitsMutation(RoutedSidebarMutation("w0", RoutedSidebarMutationKind.CLOSE)))
        assertFalse(exchange.permitsMutation(RoutedSidebarMutation("w120", RoutedSidebarMutationKind.CLOSE)))
        assertFalse(exchange.permitsMutation(RoutedSidebarMutation("w0", RoutedSidebarMutationKind.PIN)))
        exchange.begin(RoutedSidebarSnapshot(emptyList(), listOf(rows.first().copy(mutations = emptySet()))))
        assertFalse(exchange.permitsMutation(RoutedSidebarMutation("w0", RoutedSidebarMutationKind.CLOSE)))
    }
    @Test fun collidingIdsResolveTheirExactMacAndWithdrawBeforeSending() = runTest {
        var value: NativeSidebarInput? = input(listOf(source("A"), source("B")))
        var target: NativeSidebarMutationTarget? = null; var send: (() -> Boolean)? = null; var allowed = true
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {},
            mutateWorkspace = { t, _, permission -> target = t; send = permission })
        val snapshot = host.read(RoutedSidebarQuery())!!
        val row = snapshot.rows.single { it.title == "Workspace B" }
        assertFalse(RoutedSidebarWire.page(RoutedSidebarExchange().begin(snapshot)).contains("private-"))
        val command = RoutedSidebarMutation(row.key, RoutedSidebarMutationKind.RENAME, "Renamed")
        host.mutate(command) { allowed }
        assertEquals("B", target!!.mac.deviceId); assertFalse(target!!.group); assertTrue(send!!.invoke())
        allowed = false; assertFalse(send!!.invoke()); allowed = true
        value = value!!.copy(sources = value!!.sources.map { if (it.mac.deviceId == "B") it.copy(mac = it.mac.copy(code = "replacement")) else it })
        assertFalse(send!!.invoke()); assertTrue(runCatching { host.mutate(command) { true } }.isFailure)
        value = null; assertFalse(send!!.invoke())
    }
    @Test fun groupsRequireAccountAuthorityAndPinnedGroupsCannotUngroup() = runTest {
        var value = input(listOf(source("A"))); var calls = 0
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {}, mutateWorkspace = { target, _, gate ->
            assertTrue(target.group); assertTrue(gate()); calls++
        })
        val row = host.read(RoutedSidebarQuery())!!.rows.single { it.kind == "group" }
        host.mutate(RoutedSidebarMutation(row.key, RoutedSidebarMutationKind.DELETE_GROUP)) { true }
        assertEquals(1, calls)
        value = value.copy(sources = value.sources.map { it.copy(groups = it.groups.map { g -> g.copy(isPinned = true) }) })
        assertTrue(runCatching { host.mutate(RoutedSidebarMutation(row.key, RoutedSidebarMutationKind.UNGROUP)) { true } }.isFailure)
        value = value.copy(sources = value.sources.map { it.copy(capabilities = it.capabilities - WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY) })
        assertTrue(host.read(RoutedSidebarQuery())!!.rows.single { it.kind == "group" }.mutations.isEmpty())
        assertTrue(runCatching { host.mutate(RoutedSidebarMutation(row.key, RoutedSidebarMutationKind.DELETE_GROUP)) { true } }.isFailure)
        value = value.copy(sources = value.sources.map { it.copy(availability = NativeFeedAvailability.OFFLINE) })
        assertTrue(host.read(RoutedSidebarQuery())!!.rows.all { it.mutations.isEmpty() })
        assertEquals(1, calls)
    }
    @Test fun duplicateHiddenBackgroundAndNotificationTabWritesAreRejectedAndErrorsPersist() = runTest {
        val gate = CompletableDeferred<Unit>(); var calls = 0
        val controller = RoutedSidebarController(backgroundScope, {}, { _, _, _ -> RoutedSidebarExchange().begin(RoutedSidebarSnapshot(emptyList(), emptyList())) }, { "ticket" },
            workspaceAction = { calls++; gate.await(); error("Host rejected write") })
        val command = RoutedSidebarMutation("key", RoutedSidebarMutationKind.CLOSE)
        controller.initialize(RoutedSidebarQuery()); controller.configure(true, true); controller.visible(true); runCurrent()
        val pending = async { controller.mutate(command) }; runCurrent()
        assertFalse(controller.mutate(command)); assertEquals(1, calls)
        gate.complete(Unit); runCurrent(); assertFalse(pending.await())
        advanceTimeBy(1500); runCurrent(); assertEquals("Host rejected write", controller.state.value.actionError)
        controller.tab(true); assertFalse(controller.mutate(command)); controller.tab(false)
        controller.visible(false); assertFalse(controller.mutate(command)); controller.visible(true); controller.configure(true, false)
        assertFalse(controller.mutate(command)); assertEquals(1, calls)
    }
}
