package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceGroupMoveMenuTest {
    private fun workspace(id: String, group: String? = null) = NativeWorkspace(id, id, emptyList(), null,
        false, null, "window", false, emptyList(), group, null, null)
    private val source = NativeFeedSource(NativeCredentialStore.PairedMac("a", "a", "A"),
        availability = NativeFeedAvailability.CONNECTED, capabilities = setOf("workspace.move.v1", WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY),
        workspaces = listOf(workspace("root"), workspace("anchor", "first"), workspace("member", "first")),
        groups = listOf(NativeGroup("first", "First", false, false, "anchor"),
            NativeGroup("second", "Second", true, false)))
    private fun menu(id: String, owner: NativeFeedSource = source, pending: Int = 0) =
        NativeWorkspaceGroupMoveMenu.forWorkspace(owner, id, pending)

    @Test fun rootKeepsSectionOrderAndAllowsCollapsedEmptyDestination() {
        val picker = menu("root")
        assertEquals(listOf("first", "second"), picker.entries.map { it.group.id })
        assertTrue(picker.entries.all { it.isEnabled && !it.isCurrent })
        assertFalse(picker.canRemoveFromGroup)
    }
    @Test fun memberKeepsCurrentCheckedDisabledAndCanLeave() {
        val picker = menu("member")
        assertTrue(picker.entries[0].isCurrent); assertFalse(picker.entries[0].isEnabled)
        assertTrue(picker.entries[1].isEnabled); assertTrue(picker.canRemoveFromGroup)
    }
    @Test fun soleCurrentGroupStillHasPickerAndRemoval() {
        val picker = menu("member", source.copy(groups = source.groups.take(1)))
        assertEquals(1, picker.entries.size); assertTrue(picker.entries.single().isCurrent)
        assertFalse(picker.isEmpty); assertTrue(picker.canRemoveFromGroup)
    }
    @Test fun anchorsAndMissingRowsCannotMove() {
        assertTrue(menu("anchor").isEmpty); assertTrue(menu("missing").isEmpty)
    }
    @Test fun orphanMembershipCannotOfferRemoval() {
        val owner = source.copy(workspaces = source.workspaces + workspace("orphan", "unknown"))
        assertFalse(menu("orphan", owner).canRemoveFromGroup)
        assertTrue(menu("orphan", owner).entries.all { it.isEnabled })
    }
    @Test fun collidingIdsRemainScopedToTheGivenMac() {
        val other = source.copy(mac = NativeCredentialStore.PairedMac("b", "b", "B"),
            groups = listOf(NativeGroup("second", "Other Mac group", false, false)))
        assertEquals(listOf("Other Mac group"), menu("root", other).entries.map { it.group.name })
        assertEquals(listOf("First", "Second"), menu("root").entries.map { it.group.name })
    }
    @Test fun unavailableOrUnsupportedOrSaturatedOwnerCannotOfferMove() {
        assertTrue(menu("root", source.copy(availability = NativeFeedAvailability.OFFLINE)).isEmpty)
        assertTrue(menu("root", source.copy(capabilities = emptySet())).isEmpty)
        assertTrue(menu("root", pending = 3).isEmpty)
        assertFalse(menu("root", pending = 2).isEmpty)
    }
    @Test fun AmbiguousWindowInventoryCannotOfferMove() {
        assertTrue(menu("root", source.copy(workspaces = source.workspaces + workspace("foreign").copy(windowId = "other"))).isEmpty)
        assertTrue(menu("root", source.copy(workspaces = source.workspaces + workspace("missing").copy(windowId = null))).isEmpty)
    }
    @Test fun bulkEligibilityMatchesCompleteMenuAcrossMembershipAndAvailabilityChanges() {
        val sources = listOf(source, source.copy(groups = emptyList()), source.copy(groups = source.groups.take(1)),
            source.copy(workspaces = source.workspaces + workspace("orphan", "unknown")),
            source.copy(groups = source.groups.map { it.copy(isPinned = true) }),
            source.copy(groups = source.groups.map { it.copy(isEmpty = true) }),
            source.copy(availability = NativeFeedAvailability.OFFLINE), source.copy(capabilities = emptySet()),
            source.copy(workspaces = source.workspaces + workspace("foreign").copy(windowId = "other")))
        for (owner in sources) for (pending in listOf(0, 2, 3)) {
            assertEquals(owner.workspaces.filter { !menu(it.id, owner, pending).isEmpty }.map { it.id }.toSet(),
                NativeWorkspaceGroupMoveMenu.availableWorkspaceIds(owner, pending))
        }
    }
}
