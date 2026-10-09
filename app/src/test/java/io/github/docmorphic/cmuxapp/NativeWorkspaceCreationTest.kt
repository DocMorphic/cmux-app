package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeWorkspaceCreationTest {
    private val mac = NativeCredentialStore.PairedMac("fixture", "mac-id", "Mac", "nightly", "user", "team")
    private val context = listOf<Any?>("All Computers", "old-workspace")
    private fun response() = JSONObject("""{"created_workspace_id":"new","created_terminal_id":"term","workspaces":[{"id":"old"},{"id":"new","terminals":[{"id":"term"}]}]}""")
    private class Fixture(val scope: CoroutineScope) {
        var allowed = true
        var sent = 0
        var held = 0
        var request: NativeCreationRequest? = null
        val response = CompletableDeferred<JSONObject>()
        val coordinator = NativeWorkspaceCreationCoordinator(scope, { allowed }, { held++; AutoCloseable { held-- } }, {
            sent++; request = it; response.await()
        }, { _, workspace -> workspace })
    }
    private fun NativeCreationNavigation.take(coordinator: NativeWorkspaceCreationCoordinator, navigation: List<Any?> = context,
        login: String? = "login", allowed: Boolean = true, open: (NativeCreatedWorkspace) -> Unit = {}): String? =
        reconcile(login, coordinator.state.value, navigation, { allowed }, coordinator::clearCompleted) { _, result -> open(result) }

    @Test fun recreationKeepsOneRequestAndConsumesItsExactDestinationOnce() = runTest {
        val f = Fixture(backgroundScope)
        val original = NativeCreationNavigation()
        val id = f.coordinator.begin("login", mac)!!
        original.begin(id, "login", context); runCurrent()
        assertEquals(1, f.held)
        assertNull(f.coordinator.begin("login", mac))
        val restored = NativeCreationNavigation(original.save())
        assertNull(restored.take(f.coordinator))
        f.response.complete(response()); runCurrent()
        var opened = 0
        restored.take(f.coordinator) { assertEquals("new", it.workspace.id); assertEquals("term", it.terminalId); opened++ }
        restored.take(f.coordinator) { opened++ }
        assertEquals(1, opened); assertEquals(1, f.sent); assertEquals(0, f.held)
        assertEquals(NativeCreationState.Idle, f.coordinator.state.value)
    }

    @Test fun processDeathReportsUncertainOutcomeWithoutSendingAgain() = runTest {
        val f = Fixture(backgroundScope); val nav = NativeCreationNavigation()
        nav.begin(f.coordinator.begin("login", mac)!!, "login", context); runCurrent()
        val restored = NativeCreationNavigation(nav.save()); f.coordinator.clear(); runCurrent()
        val fresh = Fixture(backgroundScope)
        assertTrue(restored.take(fresh.coordinator)!!.contains("may have completed"))
        assertNull(restored.take(fresh.coordinator)); assertEquals(0, fresh.sent); assertEquals(0, f.held)
    }

    @Test fun navigationAndOwnerChangesNeverOpenALateResultEvenAfterReturning() = runTest {
        for (change in listOf("navigation", "login", "mac")) {
            val f = Fixture(backgroundScope); val nav = NativeCreationNavigation()
            nav.begin(f.coordinator.begin("login", mac)!!, "login", context); runCurrent()
            nav.take(f.coordinator, if (change == "navigation") listOf("other") else context,
                if (change == "login") "other" else "login", change != "mac") { fail("Late route") }
            f.response.complete(response()); runCurrent()
            nav.take(f.coordinator) { fail("Returning cannot reopen old result") }
            assertEquals(1, f.sent); assertEquals(0, f.held)
        }
    }

    @Test fun accountReplacementDropsResultAndReleasesHold() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.begin("login", mac); runCurrent(); f.allowed = false
        f.response.complete(response()); runCurrent()
        assertEquals(NativeCreationState.Idle, f.coordinator.state.value)
        assertNull(f.coordinator.begin("other", mac)); assertEquals(0, f.held)
    }

    @Test fun terminalCreationRequiresExactWorkspaceAndTerminal() = runTest {
        for (result in listOf(response(), JSONObject("""{"created_terminal_id":"wrong","workspaces":[{"id":"new","terminals":[{"id":"term"}]}]}"""),
            JSONObject("""{"created_terminal_id":"term","workspaces":[{"id":"other","terminals":[{"id":"term"}]}]}"""))) {
            val f = Fixture(backgroundScope)
            f.coordinator.begin("login", mac, workspaceId = "new"); runCurrent()
            assertEquals("new", f.request?.workspaceId); assertNull(f.request?.groupId)
            f.response.complete(result); runCurrent()
            if (result.has("created_workspace_id")) {
                val ready = f.coordinator.state.value as NativeCreationState.Ready
                assertEquals("term", ready.destination?.terminalId)
            } else assertTrue(f.coordinator.state.value is NativeCreationState.Failed)
            assertEquals(1, f.sent); assertEquals(0, f.held)
        }
    }

    @Test fun groupedAndLegacyWorkspaceResultsDoNotGuessCreatedIdentity() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.begin("login", mac, groupId = "group"); runCurrent()
        assertEquals("group", f.request?.groupId); assertNull(f.request?.workspaceId)
        f.response.complete(JSONObject("""{"workspaces":[{"id":"unrelated-new"}]}""")); runCurrent()
        assertNull((f.coordinator.state.value as NativeCreationState.Ready).destination)
    }

    @Test fun failureAndConnectionCancellationAreConsumedWithoutAutomaticRetry() = runTest {
        for (failure in listOf(java.io.IOException("private peer text"), CancellationException("connection replaced"))) {
            val f = Fixture(backgroundScope); val nav = NativeCreationNavigation()
            nav.begin(f.coordinator.begin("login", mac)!!, "login", context); runCurrent()
            f.response.completeExceptionally(failure); runCurrent()
            val message = nav.take(f.coordinator)!!
            assertTrue(message.contains("not confirmed")); assertFalse(message.contains("private peer text"))
            assertNull(nav.take(f.coordinator)); assertEquals(1, f.sent); assertEquals(0, f.held)
        }
    }

    @Test fun clearingSessionCancelsWorkerAndCannotPublishIntoReplacement() = runTest {
        val f = Fixture(backgroundScope)
        f.coordinator.begin("login", mac); runCurrent(); f.coordinator.clear(); runCurrent()
        f.response.complete(response()); runCurrent()
        assertEquals(NativeCreationState.Idle, f.coordinator.state.value); assertEquals(0, f.held)
    }

    @Test fun savedWaiterContainsNoComputerCredentialsOrMutationPayload() = runTest {
        val f = Fixture(backgroundScope); val nav = NativeCreationNavigation()
        nav.begin(f.coordinator.begin("login", mac, groupId = "private-group")!!, "login", context)
        val saved = JSONObject(nav.save())
        assertEquals(setOf("version", "id", "login"), saved.keys().asSequence().toSet())
        val corrupt = NativeCreationNavigation("{bad json")
        assertNull(corrupt.take(f.coordinator)); f.coordinator.clear()
    }
}
