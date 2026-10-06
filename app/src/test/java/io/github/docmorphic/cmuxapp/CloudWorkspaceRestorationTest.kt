package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudWorkspaceRestorationTest {
    private val owner = NativeTeamScope("login", "user", "team", 1)
    private val machine = CloudMachine("vm_a", "fixture", "running", "Cloud", null, null)
    private fun catalog(terminals: List<String> = listOf("first", "last")) = CloudWorkspaceCatalog(
        listOf(CloudWorkspaceSummary("ws_a")), terminals.map { CloudTerminalSummary(it, workspaceId = "ws_a") })
    private fun snapshot(terminals: List<String> = listOf("first", "last")) =
        CloudWorkspaceSnapshot(machine, catalog(terminals), NativeFeedAvailability.CONNECTED, true)
    private val machines = CloudMachinesState(phase = CloudCatalogPhase.LOADED, catalog = CloudMachineCatalog(listOf(machine), null, null))
    private val checkpoint = CloudScreenCheckpoint("login", "user", "team", "vm_a", CloudAddress("vm_a", "ws_a").identifier, CloudAddress("vm_a", "last").identifier)
    @Test fun savedPhoneTabWinsOverFirstTerminalAndMemoryIsScopedAcrossHostsAccountsAndTeams() {
        val row = snapshot().rows.single(); val key = cloudWorkspaceTabKey(owner, row)
        val memory = NativeWorkspaceLastTabs(); memory.set(key, NativeWorkspaceTab(NativeWorkspaceTabKind.TERMINAL, checkpoint.terminal!!))
        val reopened = NativeWorkspaceLastTabs(memory.json())
        assertEquals(checkpoint.terminal, cloudWorkspaceTerminal(row, true, reopened.get(key))!!.id)
        assertNull(reopened.get(cloudWorkspaceTabKey(owner.copy(userId = "other"), row)))
        assertNull(reopened.get(cloudWorkspaceTabKey(owner.copy(teamId = "other"), row)))
        val other = projectCloudWorkspaces(machine.copy(id = "vm_b"), catalog()).single()
        assertNull(reopened.get(cloudWorkspaceTabKey(owner, other)))
        assertEquals(key, cloudWorkspaceTabKey(owner.copy(generation = 2), row))
    }
    @Test fun retainedInventoryCannotEraseSelectionButAuthoritativeAbsenceFallsBackWithinSameWorkspace() {
        val remembered = NativeWorkspaceTab(NativeWorkspaceTabKind.TERMINAL, checkpoint.terminal!!)
        val missing = snapshot(listOf("first")).rows.single()
        assertNull(cloudWorkspaceTerminal(missing, false, remembered))
        assertEquals(CloudAddress(machine.id, "first").identifier, cloudWorkspaceTerminal(missing, true, remembered)!!.id)
        assertNull(cloudWorkspaceTerminal(snapshot(emptyList()).rows.single(), true, remembered))
    }
    @Test fun activityCheckpointWaitsForForegroundMachineAndAuthoritativeCatalogThenRestoresExactTerminal() {
        val fresh = mapOf(machine.id to snapshot())
        assertEquals(CloudRestoreDecision.Wait, checkpoint.resolve(owner, false, emptySet(), machines, fresh))
        assertEquals(CloudRestoreDecision.Wait, checkpoint.resolve(owner, true, emptySet(), machines.copy(phase = CloudCatalogPhase.LOADING,
            catalog = CloudMachineCatalog(emptyList(), null, null)), emptyMap()))
        for (retained in listOf(snapshot().copy(authoritative = false), snapshot().copy(availability = NativeFeedAvailability.OFFLINE)))
            assertEquals(CloudRestoreDecision.Wait, checkpoint.resolve(owner, true, emptySet(), machines, mapOf(machine.id to retained)))
        val restored = checkpoint.resolve(owner.copy(generation = 2), true, emptySet(), machines, fresh) as CloudRestoreDecision.Open
        assertEquals(checkpoint.workspace, restored.row.key); assertEquals(checkpoint.terminal, restored.terminal!!.id)
    }
    @Test fun switchedAccountHiddenOrDeletedMachineAndDeletedWorkspaceDiscardInsteadOfCrossRestoring() {
        val fresh = mapOf(machine.id to snapshot())
        for (other in listOf(owner.copy(login = "new"), owner.copy(userId = "other"), owner.copy(teamId = "other")))
            assertEquals(CloudRestoreDecision.Discard, checkpoint.resolve(other, true, emptySet(), machines, fresh))
        assertEquals(CloudRestoreDecision.Discard, checkpoint.resolve(owner, true, setOf(machine.id), machines, fresh))
        assertEquals(CloudRestoreDecision.Discard, checkpoint.resolve(owner, true, emptySet(), machines.copy(catalog = CloudMachineCatalog(emptyList(), null, null)), fresh))
        val noWorkspace = snapshot().copy(catalog = CloudWorkspaceCatalog(emptyList(), emptyList()))
        assertEquals(CloudRestoreDecision.Discard, checkpoint.resolve(owner, true, emptySet(), machines, mapOf(machine.id to noWorkspace)))
        val paused = machines.copy(catalog = CloudMachineCatalog(listOf(machine.copy(status = "paused")), null, null))
        assertEquals(CloudRestoreDecision.Wait, checkpoint.resolve(owner, true, emptySet(), paused, mapOf(machine.id to noWorkspace)))
    }
    @Test fun deletedTerminalFallsBackWithoutChangingWorkspaceAndWrongMachineSnapshotCannotOpen() {
        val next = checkpoint.resolve(owner, true, emptySet(), machines, mapOf(machine.id to snapshot(listOf("first")))) as CloudRestoreDecision.Open
        assertEquals(checkpoint.workspace, next.row.key); assertEquals(CloudAddress(machine.id, "first").identifier, next.terminal!!.id)
        assertEquals(CloudRestoreDecision.Wait, checkpoint.resolve(owner, true, emptySet(), machines,
            mapOf(machine.id to snapshot().copy(machine = machine.copy(id = "wrong")))))
    }
    @Test fun checkpointRoundTripHasOnlyBoundedIdsAndRejectsCrossMachineOrMalformedState() {
        assertEquals(checkpoint, CloudScreenCheckpoint.decode(checkpoint.encode()))
        val nullable = checkpoint.copy(team = null, terminal = null)
        assertEquals(nullable, CloudScreenCheckpoint.decode(nullable.encode()))
        assertEquals(setOf("version", "login", "user", "team", "machine", "workspace", "terminal"), JSONObject(checkpoint.encode()).keys().asSequence().toSet())
        assertNull(CloudScreenCheckpoint.decode("x".repeat(32769)))
        assertNull(CloudScreenCheckpoint.decode(checkpoint.copy(terminal = CloudAddress("another", "last").identifier).encode()))
        assertNull(CloudScreenCheckpoint.decode(checkpoint.copy(workspace = CloudAddress("vm_a").identifier).encode()))
        assertNull(CloudScreenCheckpoint.decode(JSONObject(checkpoint.encode()).put("version", 2).toString()))
        assertNull(CloudScreenCheckpoint.decode(JSONObject(checkpoint.encode()).put("login", true).toString()))
    }
}
