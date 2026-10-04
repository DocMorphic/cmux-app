package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutedSidebarNewGroupTest {
    private fun source(id: String) = NativeFeedSource(NativeCredentialStore.PairedMac("secret-$id", id, "Mac $id"),
        availability = NativeFeedAvailability.CONNECTED,
        capabilities = setOf(WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY, "workspace.group_create.v1"))
    private fun input() = listOf(source("A"), source("B")).let { sources -> NativeSidebarInput(sources, emptyList(),
        sources.map { NativeSortComputer(workspaceMacFilterId(it.mac.deviceId, null)!!, it.mac.name) },
        NativeWorkspaceSortState(), creation = NativeSidebarCreation(foregroundMac = sources.first().mac)) }
    @Test fun globalTargetsForegroundMacWhileScopedMenuTargetsSelectedMacAndNeverSsh() = runTest {
        var value = input(); val writes = mutableListOf<String>()
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {},
            createWorkspaceGroup = { mac, current -> assertTrue(current()); writes += mac.deviceId })
        val all = host.read(RoutedSidebarQuery(workspaceQuery = "hidden", workspaceUnread = true))!!
        host.mutate(RoutedSidebarMutation(all.createGroup!!, RoutedSidebarMutationKind.CREATE_GROUP)) { true }
        val b = all.computers.single { it.name == "Mac B" }.key
        val scoped = host.read(RoutedSidebarQuery(computer = b))!!
        host.mutate(RoutedSidebarMutation(scoped.createGroup!!, RoutedSidebarMutationKind.CREATE_GROUP)) { true }
        assertEquals(listOf("A", "B"), writes)
        assertNotEquals(all.createGroup, scoped.createGroup)
        assertNull(host.read(RoutedSidebarQuery(notifications = true))!!.createGroup)
        assertNull(host.read(RoutedSidebarQuery(computer = "missing"))!!.createGroup)
        value = value.copy(computers = value.computers + NativeSortComputer("ssh:fixture", "SSH fixture"))
        val ssh = host.read(RoutedSidebarQuery())!!.computers.single { it.name == "SSH fixture" }
        assertNull(host.read(RoutedSidebarQuery(computer = ssh.key))!!.createGroup)
    }
    @Test fun alreadyIssuedGlobalActionCannotFollowForegroundReplacementAndCapabilityWithdrawalStopsQueuedWrite() = runTest {
        var value: NativeSidebarInput? = input(); var current: (() -> Boolean)? = null
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {},
            createWorkspaceGroup = { _, guard -> current = guard })
        val original = value!!; val key = host.read(RoutedSidebarQuery())!!.createGroup!!
        val command = RoutedSidebarMutation(key, RoutedSidebarMutationKind.CREATE_GROUP)
        host.mutate(command) { true }; assertTrue(current!!())
        for (replacement in listOf(
            original.copy(creation = original.creation!!.copy(foregroundMac = original.sources[1].mac)),
            original.copy(creation = original.creation!!.copy(busy = true)),
            original.copy(sources = original.sources.map { it.copy(capabilities = emptySet()) }),
            original.copy(sources = original.sources.map { it.copy(availability = NativeFeedAvailability.OFFLINE) }),
            original.copy(sources = original.sources.map { it.copy(mac = it.mac.copy(code = "replaced")) }))) {
            value = replacement; assertFalse(current!!()); assertTrue(runCatching { host.mutate(command) { true } }.isFailure)
        }
        value = null; assertFalse(current!!())
        value = original; assertTrue(runCatching { host.mutate(command) { false } }.isFailure)
    }
    @Test fun newGroupIsExplicitIssuedMutationWithoutTitleOrNavigationAuthority() {
        val value = input()
        val host = NativeRoutedSidebarHost("owner", "salt", { value }, { RoutedSidebarLease({}) {} }, {}, createWorkspaceGroup = { _, _ -> })
        val snapshot = host.read(RoutedSidebarQuery())!!; val key = snapshot.createGroup!!
        val exchange = RoutedSidebarExchange(); val page = exchange.begin(snapshot)
        val wire = RoutedSidebarWire.page(page)
        assertEquals(page, RoutedSidebarWire.page(wire)); assertFalse(wire.contains("secret-"))
        val command = RoutedSidebarMutation(key, RoutedSidebarMutationKind.CREATE_GROUP)
        assertEquals(command, RoutedSidebarWire.mutation(RoutedSidebarWire.mutation(command)))
        assertTrue(exchange.permitsMutation(command)); assertNull(host.resolve(key))
        assertThrows(IllegalStateException::class.java) { exchange.prepare(key) { true } }
        assertThrows(IllegalArgumentException::class.java) { command.copy(title = "Custom name").validate() }
        exchange.begin(snapshot.copy(createGroup = null)); assertFalse(exchange.permitsMutation(command))
        assertFalse(exchange.permitsMutation(command.copy(key = "unissued")))
    }
}
