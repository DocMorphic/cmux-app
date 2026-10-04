package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeSshCreationNavigationTest {
    private val host = SshHostRecord(name = "Host", endpoint = SshEndpoint("fixture.test", 22, "user"))
    private val destination = SshWorkspaceTarget.Tmux("created-session", 2, 3)
    private val context = listOf<Any?>("All Computers", null)

    @Test fun savedReopenIntentRetainsLocalOrRemoteTabWithoutBecomingACreation() {
        val workspace = SshWorkspaceTarget.CmuxWorkspace(SshCmuxWorkspaceSelection("session", "registry", "generation", 1, "key", "resource"))
        for (tab in listOf(NativeWorkspaceTab.LocalBrowser, destination.rememberedTab()!!)) {
            val nav = NativeSshCreationNavigation()
            nav.open("login", host, workspace, tab)
            val restored = NativeSshCreationNavigation(nav.save())
            assertEquals(nav.route, restored.route)
            assertNull(restored.reconcile("login", SshWorkspaceCreationState.Idle, context, { true }))
            assertEquals(tab, restored.route?.rememberedTab)
        }
    }

    @Test fun rotationWaitsForOneOperationAndRestoresTheExactDestination() = runTest {
        val result = CompletableDeferred<SshWorkspaceTarget>(); var sent = 0
        val coordinator = SshWorkspaceCreationCoordinator(backgroundScope, { true }) { h, kind ->
            assertEquals(host, h); assertEquals(SshWorkspaceKind.TMUX, kind); sent++; result.await()
        }
        val original = NativeSshCreationNavigation()
        assertTrue(original.begin("login", coordinator, host, SshWorkspaceKind.TMUX, context))
        runCurrent()
        assertNull(coordinator.begin(host, SshWorkspaceKind.SHELL))
        val restored = NativeSshCreationNavigation(original.save())
        assertNull(restored.reconcile("login", coordinator.state.value, context, { true }))
        result.complete(destination); runCurrent()
        assertNull(restored.reconcile("login", coordinator.state.value, context, { true }, coordinator::clearCompleted))
        assertEquals(SshCreatedWorkspaceRoute("login", host, destination), restored.route)
        assertEquals(restored.route, NativeSshCreationNavigation(restored.save()).route)
        assertEquals(1, sent); assertEquals(SshWorkspaceCreationState.Idle, coordinator.state.value)
        coordinator.close()
    }

    @Test fun processRestorationWarnsOnceAndNeverReplaysCreation() = runTest {
        var sent = 0
        val old = SshWorkspaceCreationCoordinator(backgroundScope, { true }) { _, _ -> sent++; awaitCancellation() }
        val nav = NativeSshCreationNavigation()
        nav.begin("login", old, host, SshWorkspaceKind.SHELL, context); runCurrent()
        val restored = NativeSshCreationNavigation(nav.save()); old.close()
        val fresh = SshWorkspaceCreationCoordinator(backgroundScope, { true }) { _, _ -> sent++; destination }
        assertNull(restored.reconcile("login", null, context, { true }))
        assertTrue(restored.reconcile("login", fresh.state.value, context, { true })!!.contains("may have completed"))
        assertNull(restored.reconcile("login", fresh.state.value, context, { true }))
        assertNull(restored.route); assertEquals(1, sent); fresh.close()
    }

    @Test fun leavingDoesNotCancelRemoteCreationOrReopenACompletedWorkspace() = runTest {
        val result = CompletableDeferred<SshWorkspaceTarget>()
        val coordinator = SshWorkspaceCreationCoordinator(backgroundScope, { true }) { _, _ -> result.await() }
        val nav = NativeSshCreationNavigation()
        nav.begin("login", coordinator, host, SshWorkspaceKind.TMUX, context); runCurrent()
        nav.leave(); result.complete(destination); runCurrent()
        assertTrue(coordinator.state.value is SshWorkspaceCreationState.Ready)
        assertNull(nav.reconcile("login", coordinator.state.value, context, { true }))
        assertNull(nav.route); coordinator.close()
    }

    @Test fun accountFilterOrHostChangeCannotStealNavigationAfterCompletion() = runTest {
        for (change in listOf("account", "filter", "host")) {
            val coordinator = SshWorkspaceCreationCoordinator(backgroundScope, { true }) { _, _ -> destination }
            val nav = NativeSshCreationNavigation()
            nav.begin("login", coordinator, host, SshWorkspaceKind.TMUX, context); runCurrent()
            nav.reconcile(if (change == "account") "other" else "login", coordinator.state.value,
                if (change == "filter") listOf("Different computer") else context, { change != "host" })
            assertNull(nav.route)
            nav.reconcile("login", coordinator.state.value, context, { true })
            assertNull(nav.route); coordinator.close()
        }
    }

    @Test fun failureIsConsumedOnceAndOnlyAnExplicitRetrySendsAnotherRequest() = runTest {
        var sent = 0
        val coordinator = SshWorkspaceCreationCoordinator(backgroundScope, { true }) { _, _ -> sent++; error("Disconnected") }
        val nav = NativeSshCreationNavigation()
        repeat(2) {
            assertTrue(nav.begin("login", coordinator, host, SshWorkspaceKind.SHELL, context)); runCurrent()
            assertEquals("Disconnected", nav.reconcile("login", coordinator.state.value, context, { true }, coordinator::clearCompleted))
            assertNull(nav.reconcile("login", coordinator.state.value, context, { true }))
        }
        assertEquals(2, sent); coordinator.close()
    }

    @Test fun sessionRetirementRejectsLateSuccessAndFurtherCreation() = runTest {
        val result = CompletableDeferred<SshWorkspaceTarget>()
        val coordinator = SshWorkspaceCreationCoordinator(backgroundScope, { true }) { _, _ -> withContext(NonCancellable) { result.await() } }
        coordinator.begin(host, SshWorkspaceKind.TMUX); runCurrent(); coordinator.close()
        result.complete(destination); runCurrent()
        assertEquals(SshWorkspaceCreationState.Idle, coordinator.state.value)
        assertNull(coordinator.begin(host, SshWorkspaceKind.TMUX))
    }

    @Test fun savedDestinationIsRetiredOnAccountOrHostChange() = runTest {
        val coordinator = SshWorkspaceCreationCoordinator(backgroundScope, { true }) { _, _ -> destination }
        val nav = NativeSshCreationNavigation()
        nav.begin("login", coordinator, host, SshWorkspaceKind.TMUX, context); runCurrent()
        nav.reconcile("login", coordinator.state.value, context, { true })
        val saved = nav.save()
        for (accountChanged in listOf(true, false)) {
            val restored = NativeSshCreationNavigation(saved)
            restored.reconcile(if (accountChanged) "other" else "login", SshWorkspaceCreationState.Idle, context, { false })
            assertNull(restored.route)
        }
        coordinator.close()
    }

    @Test fun malformedSavedStateIsDiscarded() {
        for (value in listOf("not json", "{}", "{\"version\":1,\"pendingLogin\":\"login\"}", "x".repeat(32769))) {
            val nav = NativeSshCreationNavigation(value)
            assertNull(nav.route)
            assertNull(nav.reconcile("login", SshWorkspaceCreationState.Idle, context, { true }))
        }
    }
}
