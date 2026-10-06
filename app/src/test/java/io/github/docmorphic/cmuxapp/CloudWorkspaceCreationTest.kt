package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CloudWorkspaceCreationTest {
    private val machine = CloudMachine("vm_a", "fixture", "running", "Fixture", null, null)
    private fun makeInventory(workspace: String = "ws_old", terminal: String = "term_old") =
        CloudWorkspaceCatalog(listOf(CloudWorkspaceSummary(workspace)), listOf(CloudTerminalSummary(terminal, workspaceId = workspace)))

    @Test fun creationFailureBelongsToItsMachineAndHidesNativeDiagnostic() = runTest {
        val other = machine.copy(id = "vm_b")
        val catalog = CloudWorkspaceController(this, { true }) { makeInventory() }
        val creator = CloudWorkspaceCreation(this, catalog) { _, _ -> throw IOException("private-native-diagnostic") }
        try {
            catalog.setMachines(listOf(machine, other)); catalog.setAvailable(true); runCurrent()
            assertTrue(creator.request(machine.id) {}); runCurrent()
            val failure = checkNotNull(creator.state.value.failureFor(machine.id))
            assertTrue(failure.contains("Refresh before trying again"))
            assertFalse(failure.contains("private-native-diagnostic"))
            assertNull(creator.state.value.failureFor(other.id))
            creator.clearFailure(other.id)
            assertEquals(failure, creator.state.value.failureFor(machine.id))
            creator.clearFailure(machine.id)
            assertNull(creator.state.value.failure)
        } finally { creator.close(); catalog.close() }
    }
    @Test fun acknowledgedWorkspaceIsSelectedBeforeItsStarterCatalogAndDuplicateTapCannotCreateTwice() = runTest {
        var reads = 0; var calls = 0
        val after = CompletableDeferred<CloudWorkspaceCatalog>()
        val catalog = CloudWorkspaceController(this, { true }) { if (++reads == 1) makeInventory() else after.await() }
        val creator = CloudWorkspaceCreation(this, catalog) { id, workspace -> assertEquals(machine.id, id); assertNull(workspace); calls++; "ws_new" }
        val targets = mutableListOf<CloudCreatedTarget>()
        try {
            catalog.setMachines(listOf(machine)); catalog.setAvailable(true); runCurrent()
            assertTrue(creator.request(machine.id, onCreated = targets::add))
            assertFalse(creator.request(machine.id, onCreated = targets::add)); runCurrent()
            assertEquals(1, calls); assertEquals(listOf(CloudCreatedTarget(machine.id, "ws_new")), targets)
            assertTrue(creator.state.value.pending)
            val optimistic = catalog.state.value.getValue(machine.id)
            assertFalse(optimistic.authoritative); assertTrue(optimistic.rows.any { it.remoteId == "ws_new" })
            after.complete(makeInventory("ws_new", "term_starter")); runCurrent()
            assertFalse(creator.state.value.pending); assertNull(creator.state.value.failure)
            assertEquals("term_starter", catalog.state.value.getValue(machine.id).catalog.terminals.single().id)
        } finally { creator.close(); catalog.close() }
    }
    @Test fun newTerminalUsesItsExplicitWorkspaceAndSelectsOnlyAfterCatalogConfirmation() = runTest {
        var inventory = makeInventory(); val targets = mutableListOf<CloudCreatedTarget>()
        val catalog = CloudWorkspaceController(this, { true }) { inventory }
        val creator = CloudWorkspaceCreation(this, catalog) { _, workspace ->
            assertEquals("ws_old", workspace)
            inventory = inventory.copy(terminals = inventory.terminals + CloudTerminalSummary("term_new", workspaceId = workspace))
            "term_new"
        }
        try {
            catalog.setMachines(listOf(machine)); catalog.setAvailable(true); runCurrent()
            assertFalse(creator.request(machine.id, "ws_missing", targets::add))
            assertTrue(creator.request(machine.id, "ws_old", targets::add)); runCurrent()
            assertEquals(listOf(CloudCreatedTarget(machine.id, "ws_old", "term_new")), targets)
            assertNull(creator.state.value.failure)
        } finally { creator.close(); catalog.close() }
    }
    @Test fun unassignedRowCreatesRealWorkspaceAndFindsItsStarterInsteadOfSendingSyntheticId() = runTest {
        var inventory = CloudWorkspaceCatalog(emptyList(), listOf(CloudTerminalSummary("orphan")))
        val catalog = CloudWorkspaceController(this, { true }) { inventory }
        val creator = CloudWorkspaceCreation(this, catalog) { _, workspace ->
            assertNull(workspace); inventory = makeInventory("ws_real", "starter"); "ws_real"
        }
        val targets = mutableListOf<CloudCreatedTarget>()
        try {
            catalog.setMachines(listOf(machine)); catalog.setAvailable(true); runCurrent()
            assertTrue(creator.request(machine.id, "unassigned", targets::add)); runCurrent()
            assertEquals(listOf(CloudCreatedTarget(machine.id, "ws_real", "starter")), targets)
        } finally { creator.close(); catalog.close() }
    }
    @Test fun lostAcknowledgmentRefreshesInventoryButNeverAutomaticallyRepeatsCreate() = runTest {
        var calls = 0; var reads = 0
        val catalog = CloudWorkspaceController(this, { true }) { reads++; makeInventory() }
        val creator = CloudWorkspaceCreation(this, catalog) { _, _ -> calls++; throw IOException("connection lost") }
        try {
            catalog.setMachines(listOf(machine)); catalog.setAvailable(true); runCurrent()
            creator.request(machine.id) { fail("No confirmed target") }; runCurrent(); advanceTimeBy(120_000); runCurrent()
            assertEquals(1, calls); assertEquals(2, reads)
            assertTrue(creator.state.value.failure!!.contains("Refresh before trying again"))
            assertFalse(creator.state.value.pending)
        } finally { creator.close(); catalog.close() }
    }
    @Test fun acknowledgedTerminalWithFailedReadIsNotRetriedOrMistakenForExistingTerminal() = runTest {
        var failReads = false; var calls = 0
        val catalog = CloudWorkspaceController(this, { true }) { if (failReads) throw IOException("offline"); makeInventory() }
        val creator = CloudWorkspaceCreation(this, catalog) { _, _ -> calls++; failReads = true; "term_new" }
        try {
            catalog.setMachines(listOf(machine)); catalog.setAvailable(true); runCurrent()
            creator.request(machine.id, "ws_old") { fail("New terminal not in catalog") }; runCurrent()
            advanceTimeBy(20_000); runCurrent()
            assertEquals(1, calls); assertTrue(creator.state.value.failure!!.startsWith("Created,"))
            assertFalse(creator.canCreate(machine.id))
        } finally { creator.close(); catalog.close() }
    }
    @Test fun removedAndReaddedMachineCannotReceiveAnOldMutationCompletion() = runTest {
        val result = CompletableDeferred<String>()
        val catalog = CloudWorkspaceController(this, { true }) { makeInventory() }
        val creator = CloudWorkspaceCreation(this, catalog) { _, _ -> withContext(NonCancellable) { result.await() } }
        try {
            catalog.setMachines(listOf(machine)); catalog.setAvailable(true); runCurrent()
            creator.request(machine.id) { fail("Retired machine completion") }; runCurrent()
            catalog.setMachines(emptyList()); catalog.setMachines(listOf(machine)); runCurrent()
            result.complete("ws_late"); runCurrent()
            assertFalse(catalog.state.value.getValue(machine.id).rows.any { it.remoteId == "ws_late" })
            assertFalse(creator.state.value.pending)
        } finally { result.complete("ws_late"); creator.close(); catalog.close() }
    }
    @Test fun interruptedLinkAndMissingNativeRuntimeReportFailureWithoutRepeatingMutation() = runTest {
        for (failure in listOf(CancellationException("link closed"), UnsatisfiedLinkError("fixture"))) {
            var calls = 0
            val catalog = CloudWorkspaceController(this, { true }) { makeInventory() }
            val creator = CloudWorkspaceCreation(this, catalog) { _, _ -> calls++; throw failure }
            try {
                catalog.setMachines(listOf(machine)); catalog.setAvailable(true); runCurrent()
                creator.request(machine.id) { fail("No acknowledgment") }; runCurrent()
                assertEquals(1, calls); assertFalse(creator.state.value.pending)
                assertNotNull(creator.state.value.failure)
                creator.clearFailure(); assertNull(creator.state.value.failure)
                assertEquals(1, calls)
            } finally { creator.close(); catalog.close() }
        }
    }

    @Test fun accountRetirementDropsLateResultsAndBackgroundOrPausedMachinesRejectNewCreates() = runTest {
        var current = true; val result = CompletableDeferred<String>(); var calls = 0
        val catalog = CloudWorkspaceController(this, { current }) { makeInventory() }
        val creator = CloudWorkspaceCreation(this, catalog) { _, _ -> calls++; withContext(NonCancellable) { result.await() } }
        try {
            catalog.setMachines(listOf(machine)); runCurrent()
            assertFalse(creator.request(machine.id) {})
            catalog.setAvailable(true); runCurrent(); catalog.setMachines(listOf(machine.copy(status = "paused"))); runCurrent()
            assertFalse(creator.request(machine.id) {})
            catalog.setMachines(listOf(machine)); runCurrent()
            creator.request(machine.id) { fail("Retired account") }; runCurrent()
            current = false; creator.close(); catalog.close(); result.complete("ws_late"); runCurrent()
            assertEquals(1, calls); assertTrue(catalog.state.value.isEmpty()); assertFalse(creator.state.value.pending)
        } finally { result.complete("ws_late"); creator.close(); catalog.close() }
    }
}
