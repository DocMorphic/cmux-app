package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CloudMachinesControllerTest {
    private fun machine(id: String = "vm", status: String = "running") = CloudMachine(id, "fixture", status, null, null, null)
    private fun catalog(vararg machines: CloudMachine) = CloudMachineCatalog(machines.toList(), null, null)
    private class Service : CloudMachinesService {
        var reads = 0; var closed = false
        val creates = mutableListOf<Pair<CloudMachineCreateOptions, String>>()
        val actions = mutableListOf<Pair<String, String>>()
        var list: suspend () -> CloudMachineCatalog = { CloudMachineCatalog(emptyList(), null, null) }
        var create: suspend () -> CloudMachine = { CloudMachine("created", "fixture", "provisioning", null, null, null) }
        var action: suspend () -> Unit = {}
        override suspend fun catalog(): CloudMachineCatalog { reads++; return list() }
        override suspend fun create(options: CloudMachineCreateOptions, idempotencyKey: String): CloudMachine {
            creates += options to idempotencyKey; return create()
        }
        override suspend fun pause(id: String) { actions += id to "pause"; action() }
        override suspend fun resume(id: String) { actions += id to "resume"; action() }
        override suspend fun restart(id: String) { actions += id to "restart"; action() }
        override suspend fun delete(id: String) { actions += id to "delete"; action() }
        override fun close() { closed = true }
    }
    private class Journal : CloudCreateJournal {
        var key = 0; var pending: Pair<CloudMachineCreateOptions, String>? = null
        var fail = false
        override suspend fun resolve(options: CloudMachineCreateOptions): String {
            if (fail) throw IOException("Storage full")
            if (pending?.first != options) pending = options to "key-${++key}"
            return pending!!.second
        }
        override suspend fun complete(key: String) { if (pending?.second == key) pending = null }
    }

    @Test fun failedRefreshRetainsRowsAndRetryBackoffIsBounded() = runTest {
        val service = Service().apply { list = { catalog(machine()) } }
        CloudMachinesController(this, service, Journal(), { true }, listRetryLimit = 3).use { controller ->
            controller.refresh(); runCurrent()
            service.list = { throw IOException("Offline") }
            controller.refresh(); runCurrent()
            assertEquals(listOf("vm"), controller.state.value.catalog.machines.map { it.id })
            assertEquals(CloudCatalogPhase.FAILED, controller.state.value.phase)
            advanceTimeBy(1999); runCurrent(); assertEquals(2, service.reads)
            advanceTimeBy(1); runCurrent(); assertEquals(3, service.reads)
            advanceTimeBy(5000); runCurrent(); assertEquals(4, service.reads)
            advanceTimeBy(120_000); runCurrent(); assertEquals(4, service.reads)
        }
        assertEquals(listOf(2000L, 5000L, 10000L, 20000L, 40000L, 60000L, 60000L), (1..7).map(CloudMachinesController::retryDelay))
    }
    @Test fun firstEmptyTransientFailureRetriesQuietlyButSignOutDoesNotRetry() = runTest {
        for (failure in listOf(IOException("Offline"), CloudNotSignedIn(), CloudApiFailure(403, null, "Forbidden"))) {
            val service = Service().apply { list = { throw failure } }
            CloudMachinesController(this, service, Journal(), { true }).use { controller ->
                controller.refresh(); runCurrent()
                if (failure.javaClass == IOException::class.java) {
                    assertEquals(CloudCatalogPhase.LOADING, controller.state.value.phase)
                    advanceTimeBy(2000); runCurrent(); assertEquals(2, service.reads)
                    assertEquals(CloudCatalogPhase.FAILED, controller.state.value.phase)
                } else {
                    assertEquals(CloudCatalogPhase.FAILED, controller.state.value.phase)
                    advanceTimeBy(120_000); runCurrent(); assertEquals(1, service.reads)
                }
            }
        }
    }
    @Test fun oldRefreshThatIgnoresCancellationCannotReplaceNewCatalog() = runTest {
        val gate = CompletableDeferred<Unit>(); var first = true
        val service = Service().apply { list = {
            if (first) { first = false; withContext(NonCancellable) { gate.await() }; catalog(machine("old")) }
            else catalog(machine("new"))
        } }
        CloudMachinesController(this, service, Journal(), { true }).use { controller ->
            controller.refresh(); runCurrent(); controller.refresh(); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals(listOf("new"), controller.state.value.catalog.machines.map { it.id })
        }
    }
    @Test fun provisioningPollBudgetAndForegroundPauseDoNotLoseRows() = runTest {
        val service = Service().apply { list = { catalog(machine(status = "provisioning")) } }
        CloudMachinesController(this, service, Journal(), { true }, provisioningPollLimit = 2).use { controller ->
            controller.refresh(); runCurrent(); controller.setForeground(false)
            advanceTimeBy(60_000); runCurrent(); assertEquals(1, service.reads)
            controller.setForeground(true); advanceTimeBy(5000); runCurrent()
            assertEquals(CloudCatalogPhase.FAILED, controller.state.value.phase)
            assertEquals(1, controller.state.value.catalog.machines.size)
            controller.refresh(); runCurrent()
            assertEquals(CloudCatalogPhase.LOADED, controller.state.value.phase)
        }
    }
    @Test fun observerCancellationDoesNotCancelCreateAndDuplicateAdmissionIsRefused() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = Service().apply { create = { gate.await(); machine("created") } }
        CloudMachinesController(this, service, Journal(), { true }).use { controller ->
            val operation = controller.create(CloudMachineCreateOptions())!!; runCurrent()
            val observer = launch { operation.await() }; runCurrent(); observer.cancelAndJoin()
            assertNull(controller.create(CloudMachineCreateOptions()))
            assertTrue(controller.state.value.creating)
            gate.complete(Unit); runCurrent()
            assertEquals("created", operation.await()!!.id)
            assertFalse(controller.state.value.creating); assertEquals(1, service.creates.size)
        }
    }
    @Test fun failedCreateKeepsItsKeyForExplicitRetryAndSuccessfulCreateRetiresIt() = runTest {
        val service = Service().apply { create = { throw IOException("Lost response") } }
        CloudMachinesController(this, service, Journal(), { true }).use { controller ->
            val options = CloudMachineCreateOptions(provider = " provider ")
            val first = controller.create(options)!!; runCurrent(); assertNull(first.await())
            advanceTimeBy(60_000); runCurrent(); assertEquals(1, service.creates.size)
            service.create = { machine("created") }
            controller.create(options.normalized()); runCurrent()
            assertEquals(service.creates[0].second, service.creates[1].second)
            controller.create(options); runCurrent()
            assertNotEquals(service.creates[1].second, service.creates[2].second)
        }
    }
    @Test fun failedPersistenceAndCancelledQueuedCreateNeverReachServiceOrKeepBusyState() = runTest {
        val service = Service(); val journal = Journal().apply { fail = true }
        CloudMachinesController(this, service, journal, { true }).use { controller ->
            controller.create(CloudMachineCreateOptions()); runCurrent()
            assertTrue(service.creates.isEmpty()); assertFalse(controller.state.value.creating)
            journal.fail = false
            controller.create(CloudMachineCreateOptions())!!.cancel(); runCurrent()
            assertTrue(service.creates.isEmpty()); assertFalse(controller.state.value.creating)
        }
    }
    @Test fun actionsArePerMachineAndReconcileWithoutOptimisticLifecycleChanges() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = Service().apply { list = { catalog(machine("one"), machine("two", "paused")) }; action = { gate.await() } }
        val retired = mutableListOf<Set<String>>()
        CloudMachinesController(this, service, Journal(), { true }, retired::add).use { controller ->
            controller.refresh(); runCurrent()
            val pause = controller.act("one", CloudMachineAction.PAUSE)!!
            assertNull(controller.act("one", CloudMachineAction.DELETE))
            val resume = controller.act("two", CloudMachineAction.RESUME)!!; runCurrent()
            assertEquals(setOf("one", "two"), controller.state.value.actions)
            assertEquals(CloudMachineLifecycle.RUNNING, controller.state.value.catalog.machines[0].lifecycle)
            service.list = { catalog(machine("one", "paused"), machine("two")) }
            gate.complete(Unit); runCurrent()
            assertTrue(pause.await()); assertTrue(resume.await()); assertTrue(controller.state.value.actions.isEmpty())
            assertTrue(retired.flatten().containsAll(listOf("one", "two")))
        }
    }
    @Test fun deleteClosesLinkBeforeSendingAndUnknownStatesRejectActions() = runTest {
        val events = mutableListOf<String>()
        val service = Service().apply { list = { catalog(machine(), machine("unknown", "future"), machine("gone", "destroyed")) }; action = { events += "send" } }
        CloudMachinesController(this, service, Journal(), { true }, { events += "retire" }).use { controller ->
            controller.refresh(); runCurrent()
            assertEquals(2, controller.state.value.catalog.machines.size)
            assertNull(controller.act("unknown", CloudMachineAction.DELETE))
            controller.act("vm", CloudMachineAction.DELETE); runCurrent()
            assertEquals(listOf("retire", "send"), events)
        }
    }
    @Test fun acknowledgedRestartRetiresOnlyItsCachedLinkAndReloadsAnUnchangedRunningCatalog() = runTest {
        class Session : AutoCloseable { var retired = false; var closes = 0; override fun close() { closes++ } }
        val sessions = mutableListOf<Pair<String, Session>>()
        val terminal = object : CloudTerminalService {
            override suspend fun attach(id: String, fingerprint: String, capabilities: List<String>) = CloudAttachEndpoint("fixture", id, null, true)
            override suspend fun approve(id: String, invitationId: String) = error("No approval")
        }
        val pool = CloudMachineConnections(this, terminal, "fixture", { true }, { endpoint ->
            Session().also { sessions += endpoint.session to it }
        }, StandardTestDispatcher(testScheduler), { it.retired = true })
        val rows = listOf(machine("one").copy(supportsRestart = true), machine("two"))
        val workspaces = CloudWorkspaceController(this, { true }) { id ->
            val link = checkNotNull(pool.connection(id))
            pool.withConnection(id, link) { CloudWorkspaceCatalog(emptyList(), emptyList()) }
        }
        val gate = CompletableDeferred<Unit>()
        val service = Service().apply { list = { catalog(*rows.toTypedArray()) }; action = { gate.await() } }
        val reloaded = mutableListOf<String>()
        val controller = CloudMachinesController(this, service, Journal(), { true }, pool::retire,
            onMachineRestarted = { id -> reloaded += id; workspaces.refresh(id) })
        try {
            controller.refresh(); workspaces.setMachines(rows); workspaces.setAvailable(true); runCurrent()
            val old = pool.connection("one"); val other = pool.connection("two")
            val originalRevision = workspaces.state.value.getValue("one").catalogRevision
            val action = checkNotNull(controller.act("one", CloudMachineAction.RESTART)); runCurrent()
            assertNull(controller.act("one", CloudMachineAction.PAUSE)); assertNull(controller.act("one", CloudMachineAction.RESTART))
            assertFalse(sessions.first { it.first == "one" }.second.retired); assertTrue(reloaded.isEmpty())
            gate.complete(Unit); runCurrent(); assertTrue(action.await())
            assertTrue(sessions.first { it.first == "one" }.second.retired)
            assertEquals(1, sessions.first { it.first == "one" }.second.closes)
            assertNotSame(old, pool.connection("one")); assertSame(other, pool.connection("two"))
            assertEquals(listOf("one"), reloaded)
            assertEquals(listOf("one", "two", "one"), sessions.map { it.first })
            assertTrue(workspaces.state.value.getValue("one").catalogRevision > originalRevision)
            assertEquals(rows, controller.state.value.catalog.machines)
            assertEquals(listOf("one" to "restart"), service.actions)
        } finally { gate.complete(Unit); controller.close(); workspaces.close(); pool.close(); runCurrent() }
    }

    @Test fun restartFailureKeepsTheLinkAndNeverAutomaticallyRepeatsTheMutation() = runTest {
        val service = Service().apply {
            list = { catalog(machine().copy(supportsRestart = true)) }
            action = { throw CloudApiFailure(504, "Still in progress; refresh to check again.", "Provider recovery") }
        }
        val retired = mutableListOf<Set<String>>(); val reloaded = mutableListOf<String>()
        CloudMachinesController(this, service, Journal(), { true }, retired::add, onMachineRestarted = reloaded::add).use { controller ->
            controller.refresh(); runCurrent()
            val action = checkNotNull(controller.act("vm", CloudMachineAction.RESTART)); runCurrent()
            assertFalse(action.await()); assertTrue(retired.isEmpty()); assertTrue(reloaded.isEmpty())
            assertEquals(CloudMachineAction.RESTART, controller.state.value.actionFailure?.action)
            advanceTimeBy(120_000); runCurrent(); assertEquals(1, service.actions.size)
            service.action = {}
            val retry = checkNotNull(controller.act("vm", CloudMachineAction.RESTART)); runCurrent(); assertTrue(retry.await())
            assertNull(controller.state.value.actionFailure); assertEquals(listOf(setOf("vm")), retired)
            assertEquals(listOf("vm"), reloaded); assertEquals(2, service.actions.size)
        }
    }

    @Test fun restartUsesTheCurrentCapabilityAndRetiredOwnersCannotResetSuccessorConnections() = runTest {
        val gate = CompletableDeferred<Unit>(); var current = true
        var row = machine().copy(supportsRestart = true)
        val service = Service().apply { list = { catalog(row) }; action = { withContext(NonCancellable) { gate.await() } } }
        val retired = mutableListOf<Set<String>>(); val reloaded = mutableListOf<String>()
        val controller = CloudMachinesController(this, service, Journal(), { current }, retired::add, onMachineRestarted = reloaded::add)
        try {
            controller.refresh(); runCurrent(); row = row.copy(supportsRestart = false)
            controller.refresh(); runCurrent(); assertNull(controller.act("vm", CloudMachineAction.RESTART))
            row = row.copy(supportsRestart = true, status = "paused")
            controller.refresh(); runCurrent(); assertNull(controller.act("vm", CloudMachineAction.RESTART))
            row = row.copy(status = "running"); controller.refresh(); runCurrent(); retired.clear()
            val old = checkNotNull(controller.act("vm", CloudMachineAction.RESTART)); runCurrent()
            current = false; gate.complete(Unit); runCurrent()
            assertFalse(old.await()); assertTrue(retired.isEmpty()); assertTrue(reloaded.isEmpty())
            assertNull(controller.state.value.actionFailure); assertNull(controller.act("vm", CloudMachineAction.RESTART))
        } finally { gate.complete(Unit); controller.close(); runCurrent() }
    }

    @Test fun retirementDuringLinkCleanupCannotNotifyOrReloadAReplacementOwner() = runTest {
        var current = true; val reloaded = mutableListOf<String>()
        val service = Service().apply { list = { catalog(machine().copy(supportsRestart = true)) } }
        CloudMachinesController(this, service, Journal(), { current }, { current = false }, onMachineRestarted = reloaded::add).use { controller ->
            controller.refresh(); runCurrent()
            val action = checkNotNull(controller.act("vm", CloudMachineAction.RESTART)); runCurrent()
            assertFalse(action.await()); assertTrue(reloaded.isEmpty())
        }
    }
    @Test fun retirementCancelsOperationsClearsStateAndRejectsLateUncancellableResults() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = Service().apply { create = { withContext(NonCancellable) { gate.await() }; machine("old") } }
        val controller = CloudMachinesController(this, service, Journal(), { true })
        controller.create(CloudMachineCreateOptions()); runCurrent(); controller.close()
        gate.complete(Unit); runCurrent()
        assertTrue(service.closed); assertEquals(CloudMachinesState(), controller.state.value)
        assertNull(controller.create(CloudMachineCreateOptions()))
    }
}
