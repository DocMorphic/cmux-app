package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CloudWorkspaceNavigationTest {
    private fun rows(machine: String) = projectCloudWorkspaces(CloudMachine(machine, "fixture", "running", "Cloud $machine", null, null),
        CloudWorkspaceCatalog(listOf(CloudWorkspaceSummary("ws_same", "Project", "/home/me/repo")),
            listOf(CloudTerminalSummary("term_same", "Build", "ws_same"))))
    private fun computers(rows: List<CloudWorkspaceRow>) = rows.map { NativeSortComputer(CloudAddress(it.machine.id).identifier, it.machine.preferredName) }
    @Test fun cloudRowsUseSharedSortWithoutChangingIdentityOrInventingActivity() {
        val rows = rows("a") + rows("b")
        val priority = CloudAddress("b").identifier
        val result = sortedWorkspaceRows(emptyList(), emptyList(), computers(rows),
            NativeWorkspaceSortState("computerPriority", listOf(priority)), true, emptySet(), false, false,
            emptyMap(), Locale.US, rows)
        assertEquals(listOf("b", "a"), result.map { (it as NativeWorkspaceDisplayRow.Cloud).row.machine.id })
        assertEquals(2, result.map { it.key }.toSet().size)
        assertTrue(rows.all { it.workspace.lastActivityAt == null && !it.workspace.isPinned })
    }
    @Test fun emptyCloudMachinesOfferScopedCreationAndOldActionsRecheckConnectionAndBusyState() {
        val machine = CloudMachine("empty", "fixture", "running", "Empty Cloud", null, null)
        val snapshot = CloudWorkspaceSnapshot(machine, availability = NativeFeedAvailability.CONNECTED, authoritative = true)
        var input = NativeSidebarInput(emptyList(), emptyList(), listOf(NativeSortComputer(CloudAddress(machine.id).identifier, machine.preferredName)),
            NativeWorkspaceSortState(), creation = NativeSidebarCreation(cloud = listOf(snapshot)))
        val targets = mutableListOf<NativeSidebarTarget>()
        val host = NativeRoutedSidebarHost("account", "fixture", { input }, { RoutedSidebarLease({}, {}) }, targets::add)
        val shown = host.read(RoutedSidebarQuery())!!
        assertTrue(shown.rows.isEmpty()); assertEquals("Cloud", shown.creation.single().build)
        val target = shown.creation.single()
        assertTrue(target.enabled)
        assertEquals(listOf(target), host.read(RoutedSidebarQuery(computer = target.key))!!.creation)
        val action = host.resolve(target.options.single().key)!!
        action(); assertEquals(listOf(NativeSidebarTarget.CreateCloud(machine.id)), targets)
        for (replacement in listOf(
            NativeSidebarCreation(busy = true, cloud = listOf(snapshot)),
            NativeSidebarCreation(cloud = listOf(snapshot.copy(authoritative = false))),
            NativeSidebarCreation(cloud = listOf(snapshot.copy(machine = machine.copy(status = "paused")))),
            NativeSidebarCreation(cloud = emptyList()))) {
            input = input.copy(creation = replacement)
            assertThrows(IllegalStateException::class.java) { action() }
        }
        assertEquals(1, targets.size)
    }

    @Test fun cloudComputerScopeSurvivesSidebarRoundTripAndMissingInventoryCannotBroadenIt() {
        val rows = rows("a") + rows("b")
        val selected = CloudAddress("b").identifier
        var input = NativeSidebarInput(emptyList(), emptyList(), computers(rows), NativeWorkspaceSortState(), cloud = rows)
        val presentations = mutableListOf<NativeSidebarPresentation>()
        val host = NativeRoutedSidebarHost("account", "fixture", { input }, { RoutedSidebarLease({}, {}) }, {},
            initial = { NativeSidebarPresentation(computer = selected) }, adoptPresentation = presentations::add)
        val query = host.initialQuery()
        assertEquals(1, host.read(query)!!.rows.size)
        host.adopt(query)
        assertEquals(selected, presentations.single().computer)
        input = input.copy(cloud = rows.take(1), computers = computers(rows.take(1)))
        assertTrue(host.read(query)!!.rows.isEmpty())
        host.adopt(query)
        assertEquals(1, presentations.size)
        assertEquals(selected, presentations.single().computer)
        // Explicit All Computers is the only action that broadens the saved scope.
        host.adopt(query.copy(computer = null))
        assertNull(presentations.last().computer)
    }

    @Test fun routedSidebarSearchAndComputerScopeResolveCloudWithoutMacMutations() {
        val rows = rows("a") + rows("b")
        var input = NativeSidebarInput(emptyList(), emptyList(), computers(rows), NativeWorkspaceSortState(),
            locale = Locale.US, cloud = rows, cloudAvailability = mapOf("a" to NativeFeedAvailability.CONNECTED, "b" to NativeFeedAvailability.OFFLINE),
            cloudSelection = rows.first().key)
        val navigated = mutableListOf<NativeSidebarTarget>()
        val host = NativeRoutedSidebarHost("account", "fixture", { input }, { RoutedSidebarLease({}, {}) }, navigated::add)
        val all = host.read(RoutedSidebarQuery())!!
        assertEquals(2, all.rows.size); assertEquals(1, all.rows.count { it.selected })
        assertTrue(all.rows.all { it.mutations.isEmpty() && !it.canCustomize && it.changes == null })
        assertEquals(2, host.read(RoutedSidebarQuery(workspaceQuery = "Build"))!!.rows.size)
        val computer = all.computers.single { it.name == "Cloud b" }
        val scoped = host.read(RoutedSidebarQuery(computer = computer.key))!!
        assertEquals(1, scoped.rows.size)
        val open = host.resolve(scoped.rows.single().key)!!; open()
        assertEquals("b", (navigated.single() as NativeSidebarTarget.Cloud).row.machine.id)
        assertTrue(host.read(RoutedSidebarQuery(workspaceUnread = true))!!.rows.isEmpty())
        input = input.copy(cloud = rows.take(1), computers = computers(rows.take(1)))
        assertThrows(IllegalStateException::class.java) { open() }
        assertTrue(host.read(RoutedSidebarQuery(computer = computer.key))!!.rows.isEmpty())
    }
}
