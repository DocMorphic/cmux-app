package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class LocalBrowserNavigationTest {
    private val key = LocalBrowserKey("account", "team", "mac-a", "same-workspace")
    private val owner = NativeTeamScope("login", "account", "team", 1)
    private val workspace = NativeWorkspace(key.workspaceId, "Workspace", listOf(NativeTerminal("t1", "One"), NativeTerminal("t2", "Two")),
        null, false, null, null, false, emptyList(), null, null, null)
    private fun LocalBrowserNavigation.open(key: LocalBrowserKey = this@LocalBrowserNavigationTest.key, canCreate: Boolean = true,
        create: suspend () -> String? = { null }, stillCurrent: () -> Boolean = { true },
        local: () -> Unit = {}, remote: (String) -> Unit = {}) = open(key, workspace, "t2", canCreate, create, stillCurrent, local, remote)

    @Test fun creationRequiresBothCapabilitiesAndAConnection() {
        val capabilities = setOf("browser.stream.v1", "browser.stream.create.v1")
        assertTrue(LocalBrowserNavigation.canCreate(true, capabilities))
        assertFalse(LocalBrowserNavigation.canCreate(false, capabilities))
        capabilities.forEach { assertFalse(LocalBrowserNavigation.canCreate(true, setOf(it))) }
    }
    @Test fun creationDescriptorMustHaveStringIdsForTheRequestedWorkspace() {
        val response = JSONObject().put("panel_id", "panel").put("workspace_id", "w")
        assertEquals("panel", localBrowserCreatedPanel(response, "w"))
        assertNull(localBrowserCreatedPanel(response, "other"))
        assertNull(localBrowserCreatedPanel(response.put("panel_id", 42), "w"))
        assertNull(localBrowserCreatedPanel(JSONObject().put("panel_id", "panel"), "w"))
    }
    @Test fun offlineIdentityUsesSavedAccountScopeOrTheLoginIncarnation() = runTest {
        val mac = NativeCredentialStore.PairedMac("fixture-code", "mac", "Mac", accountUserId = "account", accountTeamId = "team")
        assertEquals(localBrowserKey("login", owner, mac, "w"), localBrowserKey("login", null, mac, "w"))
        val legacy = mac.copy(accountUserId = null, accountTeamId = null)
        assertNotEquals(localBrowserKey("login-one", null, legacy, "w"), localBrowserKey("login-two", null, legacy, "w"))
        val navigation = LocalBrowserNavigation(this)
        navigation.retain(null, true, setOf(key.computerId), "one")
        navigation.open(canCreate = false); runCurrent(); val page = navigation.state.value.local!!.surface
        navigation.retain(null, true, setOf(key.computerId), "two")
        assertTrue(page.state.value.closed); assertNull(navigation.state.value.local)
    }
    @Test fun offlineAndMissingCapabilityOpenLocallyWithoutSending() = runTest {
        val navigation = LocalBrowserNavigation(this)
        var local = 0
        navigation.open(canCreate = false, create = { fail("Unexpected remote create"); null }, local = { local++ })
        runCurrent()
        assertEquals(1, local); assertEquals(key, navigation.state.value.local!!.key)
        assertEquals("t2", navigation.state.value.local!!.terminalId)
        assertNull(navigation.state.value.creating)
    }
    @Test fun rejectedMalformedTimedOutAndRetiredLeaseCreatesFallBackWithoutResending() = runTest {
        for (create in listOf<suspend () -> String?>({ error("Rejected") }, { "" }, { delay(1000); "late" }, { throw CancellationException("Retired host lease") })) {
            val navigation = LocalBrowserNavigation(this, timeoutMillis = 100)
            var attempts = 0
            navigation.open(create = { attempts++; create() })
            advanceUntilIdle()
            assertEquals(1, attempts); assertEquals(key, navigation.state.value.local!!.key)
            assertNull(navigation.state.value.creating)
        }
    }
    @Test fun remoteSuccessClosesAnExistingLocalSurface() = runTest {
        val navigation = LocalBrowserNavigation(this)
        navigation.open(canCreate = false); runCurrent()
        val local = navigation.state.value.local!!.surface
        var remote = ""
        navigation.open(create = { "panel-a" }, remote = { remote = it }); runCurrent()
        assertEquals("panel-a", remote); assertNull(navigation.state.value.local); assertTrue(local.state.value.closed)
    }
    @Test fun navigationCancellationAndSupersedingRequestFenceLateUncooperativeResults() = runTest {
        val navigation = LocalBrowserNavigation(this)
        navigation.navigationContext("terminal")
        val old = CompletableDeferred<String>(); var routed = false
        navigation.open(create = { withContext(NonCancellable) { old.await() } }, remote = { routed = true })
        runCurrent(); navigation.navigationContext("workspace-list")
        val other = key.copy(computerId = "mac-b")
        navigation.open(key = other, canCreate = false); runCurrent()
        old.complete("old-panel"); runCurrent()
        assertFalse(routed); assertEquals(other, navigation.state.value.local!!.key)
        assertNull(navigation.state.value.creating)
    }
    @Test fun repeatedClicksDoNotDuplicateThePendingMutationAndCurrentGuardRejectsCompletion() = runTest {
        val navigation = LocalBrowserNavigation(this); val gate = CompletableDeferred<String>(); var attempts = 0; var current = true
        navigation.open(create = { attempts++; gate.await() }, stillCurrent = { current }, remote = { fail("Stale route") })
        runCurrent()
        navigation.open(create = { attempts++; "duplicate" }); runCurrent()
        assertEquals(1, attempts)
        current = false; gate.complete("panel"); runCurrent()
        assertNull(navigation.state.value.local); assertNull(navigation.state.value.creating)
    }
    @Test fun backRestoresTheSameWorkspaceOnceAndExplicitSelectionRemovesIt() = runTest {
        val navigation = LocalBrowserNavigation(this)
        navigation.open(canCreate = false); runCurrent()
        val local = navigation.state.value.local!!
        navigation.leave(close = false)
        assertFalse(navigation.restore(key.copy(computerId = "mac-b"), workspace))
        assertTrue(navigation.restore(key, workspace)); assertSame(local.surface, navigation.state.value.local!!.surface)
        assertEquals("t2", navigation.state.value.local!!.terminalId)
        assertFalse(navigation.restore(key, workspace))
        navigation.selectMacPane(key)
        assertTrue(local.surface.state.value.closed); assertFalse(navigation.restore(key, workspace))
    }
    @Test fun accountTeamAndComputerRemovalRetireStateAndPendingResults() = runTest {
        val navigation = LocalBrowserNavigation(this)
        navigation.retain(owner, true, setOf(key.computerId))
        navigation.open(canCreate = false); runCurrent(); val old = navigation.state.value.local!!.surface
        navigation.retain(owner.copy(teamId = "different"), true, setOf(key.computerId))
        assertTrue(old.state.value.closed); assertNull(navigation.state.value.local)
        navigation.open(canCreate = false); runCurrent(); val removed = navigation.state.value.local!!.surface
        navigation.retain(owner, true, emptySet())
        assertTrue(removed.state.value.closed); assertNull(navigation.state.value.local)
        navigation.open(create = { delay(100); "panel" }, remote = { fail("Signed-out request routed") })
        runCurrent(); navigation.retain(null, false, emptySet()); advanceUntilIdle()
        assertNull(navigation.state.value.local); assertNull(navigation.state.value.creating)
    }

    @Test fun confirmedWorkspaceRemovalCancelsLateCreationAndClosesRetainedPagesOnlyForItsMac() = runTest {
        val mac = NativeCredentialStore.PairedMac("a", "a", "Mac A")
        val a = key.copy(computerId = mac.origin); val b = key.copy(computerId = "mac-b")
        val navigation = LocalBrowserNavigation(this)
        navigation.open(key = b, canCreate = false); runCurrent(); val other = navigation.state.value.local!!.surface
        navigation.leave(close = false)
        navigation.open(key = a, canCreate = false); runCurrent(); val removed = navigation.state.value.local!!.surface
        navigation.leave(close = false)
        val late = CompletableDeferred<String>()
        navigation.open(key = a, create = { withContext(NonCancellable) { late.await() } }, remote = { fail("Closed workspace opened") })
        runCurrent()
        val source = NativeFeedSource(mac, availability = NativeFeedAvailability.CONNECTED, hasWorkspaceSnapshot = true)
        navigation.observeWorkspaces(source); late.complete("late-panel"); runCurrent()
        assertNull(navigation.state.value.local); assertNull(navigation.state.value.creating)
        assertTrue(removed.state.value.closed); assertFalse(navigation.restore(a, workspace))
        assertTrue(navigation.restore(b, workspace)); assertSame(other, navigation.state.value.local!!.surface)
        assertFalse(other.state.value.closed)
    }
    @Test fun missingCachedAndUnhydratedListsPreserveTheLocalPageButConfirmedAbsenceClosesIt() = runTest {
        val mac = NativeCredentialStore.PairedMac("a", "a", "A"); val scoped = key.copy(computerId = mac.origin)
        val navigation = LocalBrowserNavigation(this)
        navigation.open(key = scoped, canCreate = false); runCurrent(); val page = navigation.state.value.local!!.surface
        val base = NativeFeedSource(mac, hasWorkspaceSnapshot = true)
        assertTrue(localBrowserWorkspacePresent(null, key.workspaceId))
        for (source in listOf(base, base.copy(availability = NativeFeedAvailability.OFFLINE),
            base.copy(availability = NativeFeedAvailability.CONNECTED, hasWorkspaceSnapshot = false))) {
            assertTrue(localBrowserWorkspacePresent(source, key.workspaceId)); navigation.observeWorkspaces(source)
            assertSame(page, navigation.state.value.local!!.surface)
        }
        val fresh = base.copy(availability = NativeFeedAvailability.CONNECTED, workspaces = listOf(workspace.copy(title = "Renamed")))
        navigation.observeWorkspaces(fresh)
        assertEquals("Renamed", navigation.state.value.local!!.workspace.title)
        val empty = fresh.copy(workspaces = emptyList())
        assertFalse(localBrowserWorkspacePresent(empty, key.workspaceId)); navigation.observeWorkspaces(empty)
        assertNull(navigation.state.value.local); assertTrue(page.state.value.closed)
    }
    @Test fun authoritativeWorkspaceParserRejectsIncompleteAndAmbiguousLists() {
        for (json in listOf("{}", "{\"workspaces\":null}", "{\"workspaces\":[null]}",
            "{\"workspaces\":[{\"id\":42}]}", "{\"workspaces\":[{\"id\":\"\"}]}",
            "{\"workspaces\":[{\"id\":\"w\"},{\"id\":\"w\"}]}")) {
            assertTrue(runCatching { parseAuthoritativeWorkspaces(JSONObject(json)) }.exceptionOrNull() is java.io.IOException)
        }
        assertTrue(parseAuthoritativeWorkspaces(JSONObject("{\"workspaces\":[]}")).isEmpty())
        assertEquals("w", parseAuthoritativeWorkspaces(JSONObject("{\"workspaces\":[{\"id\":\"w\"}]}")).single().id)
    }
}
