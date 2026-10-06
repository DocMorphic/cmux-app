package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CloudWorkspaceControllerTest {
    private fun machine(id: String = "vm_a", status: String = "running") = CloudMachine(id, "fixture", status, id, null, null)
    private fun catalog(id: String = "ws_a") = CloudWorkspaceCatalog(listOf(CloudWorkspaceSummary(id)), listOf(CloudTerminalSummary("term_a", workspaceId = id)))
    @Test fun placeholdersDoNotDialUntilTheTunnelIsReadyAndNonRunningMachinesNeverDial() = runTest {
        val calls = mutableListOf<String>()
        val controller = CloudWorkspaceController(this, { true }) { calls += it; catalog() }
        try {
            controller.setMachines(listOf(machine(), machine("paused", "paused"))); runCurrent()
            assertTrue(calls.isEmpty()); assertEquals(2, controller.state.value.size)
            controller.setAvailable(true); runCurrent()
            assertEquals(listOf("vm_a"), calls)
            assertTrue(controller.state.value.getValue("vm_a").authoritative)
            assertEquals(NativeFeedAvailability.OFFLINE, controller.state.value.getValue("paused").availability)
            controller.setMachines(listOf(machine(), machine("paused", "paused"))); runCurrent(); assertEquals(1, calls.size)
        } finally { controller.close() }
    }
    @Test fun failureAndBackgroundRetainRowsAndRecoveryReplacesThemAuthoritatively() = runTest {
        var fail = false; var requests = 0
        val controller = CloudWorkspaceController(this, { true }) { requests++; if (fail) throw IOException("offline"); catalog("ws_$requests") }
        try {
            controller.setMachines(listOf(machine())); controller.setAvailable(true); runCurrent()
            assertEquals("ws_1", controller.state.value.getValue("vm_a").catalog.workspaces.single().id)
            fail = true; controller.refreshAll(); runCurrent()
            val failed = controller.state.value.getValue("vm_a")
            assertFalse(failed.authoritative); assertEquals("ws_1", failed.catalog.workspaces.single().id)
            assertEquals(NativeFeedAvailability.OFFLINE, failed.availability)
            controller.setAvailable(false); advanceTimeBy(90_000); runCurrent(); assertEquals(2, requests)
            assertEquals(NativeFeedAvailability.CONNECTING, controller.state.value.getValue("vm_a").availability)
            fail = false; controller.setAvailable(true); runCurrent()
            assertEquals("ws_3", controller.state.value.getValue("vm_a").catalog.workspaces.single().id)
        } finally { controller.close() }
    }
    @Test fun retryUsesFiveTenTwentyFortySixtySecondsWithoutBusyLooping() = runTest {
        val times = mutableListOf<Long>()
        val controller = CloudWorkspaceController(this, { true }) { times += testScheduler.currentTime; throw IOException("offline") }
        try {
            controller.setMachines(listOf(machine())); controller.setAvailable(true); runCurrent()
            for (wait in listOf(5000L, 10000L, 20000L, 40000L, 60000L, 60000L)) { advanceTimeBy(wait); runCurrent() }
            assertEquals(listOf(0L, 5000L, 15000L, 35000L, 75000L, 135000L, 195000L), times)
            controller.setMachines(emptyList()); advanceTimeBy(60_000); runCurrent(); assertEquals(7, times.size)
            assertTrue(controller.state.value.isEmpty())
        } finally { controller.close() }
    }
    @Test fun supersededUncancellableReadCannotOverwriteReplacementOrResurrectRemovedMachine() = runTest {
        val pending = mutableListOf<CompletableDeferred<CloudWorkspaceCatalog>>()
        val controller = CloudWorkspaceController(this, { true }) {
            val result = CompletableDeferred<CloudWorkspaceCatalog>().also(pending::add)
            withContext(NonCancellable) { result.await() }
        }
        try {
            controller.setMachines(listOf(machine())); controller.setAvailable(true); runCurrent()
            controller.refreshAll(); runCurrent()
            pending[1].complete(catalog("ws_new")); runCurrent()
            pending[0].complete(catalog("ws_stale")); runCurrent()
            assertEquals("ws_new", controller.state.value.getValue("vm_a").catalog.workspaces.single().id)
            controller.refreshAll(); runCurrent(); controller.setMachines(emptyList())
            pending[2].complete(catalog("ws_removed")); runCurrent(); assertTrue(controller.state.value.isEmpty())
        } finally { pending.forEach { it.complete(catalog()) }; controller.close() }
    }
    @Test fun pauseClearsPublishedRowsAndAccountRetirementRejectsOldSuccess() = runTest {
        var current = true; val pending = CompletableDeferred<CloudWorkspaceCatalog>(); var requests = 0
        val controller = CloudWorkspaceController(this, { current }) {
            if (++requests == 1) catalog() else withContext(NonCancellable) { pending.await() }
        }
        try {
            controller.setMachines(listOf(machine())); controller.setAvailable(true); runCurrent()
            controller.setMachines(listOf(machine(status = "paused"))); runCurrent()
            assertTrue(controller.state.value.getValue("vm_a").catalog.workspaces.isEmpty())
            controller.setMachines(listOf(machine())); runCurrent()
            current = false; controller.close(); pending.complete(catalog("ws_wrong_owner")); runCurrent()
            assertTrue(controller.state.value.isEmpty())
            controller.refreshAll(); runCurrent(); assertEquals(2, requests)
        } finally { pending.complete(catalog()); controller.close() }
    }

    @Test fun explicitRefreshBypassesFailureBackoffAndRetiresThePreviousRetryTimer() = runTest {
        var failing = true; val reads = mutableListOf<Long>()
        val controller = CloudWorkspaceController(this, { true }) {
            reads += testScheduler.currentTime
            if (failing) throw IOException("private transport diagnostic") else catalog()
        }
        try {
            controller.setMachines(listOf(machine())); controller.setAvailable(true); runCurrent()
            val failure = controller.state.value.getValue("vm_a").failure!!
            assertEquals(CloudFailureKind.LINK, failure.kind)
            assertFalse(failure.userReason.contains("private transport"))
            advanceTimeBy(1000); failing = false; controller.refreshAll(); runCurrent()
            assertEquals(listOf(0L, 1000L), reads)
            assertEquals(NativeFeedAvailability.CONNECTED, controller.state.value.getValue("vm_a").availability)
            assertNull(controller.state.value.getValue("vm_a").failure)
            advanceTimeBy(60000); runCurrent(); assertEquals(2, reads.size)
        } finally { controller.close() }
    }
}
