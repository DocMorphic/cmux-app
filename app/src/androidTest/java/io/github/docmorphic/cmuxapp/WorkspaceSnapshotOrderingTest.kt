package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalTestApi::class)
class WorkspaceSnapshotOrderingTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private lateinit var store: NativeCredentialStore
    private lateinit var peer: NativeFixturePeer
    private lateinit var session: NativeFeedSession
    private var scenario: ActivityScenario<NativeLifecycleTestActivity>? = null
    private val connections = AtomicInteger()
    private val code = "cmux-ios://attach?v=2&r=100.64.0.1:58465"
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        store = NativeCredentialStore(InstrumentationRegistry.getInstrumentation().targetContext); store.clear()
        store.update { it.put("refresh_token", "snapshot-order-emulator-fixture").put("pairing_code", code) }
        store.rememberMac(code, "fixture-mac", "Fixture Mac")
        peer = NativeFixturePeer(); peer.customWorkspaceListing = listing()
        NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
            connections.incrementAndGet()
            MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
        }
    }
    @After fun cleanup() {
        scenario?.close(); NativeLifecycleTestActivity.connector = null
        if (::peer.isInitialized) { peer.close(); store.clear() }
    }
    private fun listing(created: Boolean = false) = JSONObject("""{"workspaces":[{"id":"workspace-1","title":"Ordered workspace",
        "terminals":[{"id":"terminal-1","title":"Ready shell","is_focused":true}${if (created) ",{\"id\":\"new-terminal\",\"title\":\"New shell\",\"is_ready\":false}" else ""}]}]}""")
    private fun launch() {
        scenario = ActivityScenario.launch(NativeLifecycleTestActivity::class.java)
        waitFor("Ordered workspace")
        scenario!!.onActivity { session = ViewModelProvider(it)[NativeFeedSession::class.java] }
        compose.waitUntil(15_000) { session.coordinator.sources.value.values.any { it.hasWorkspaceSnapshot } }
    }
    private fun waitFor(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun connectedPaneCreatesThroughItsExistingClientWhenTheSecondaryFeedIsPaused() {
        launch()
        compose.onNodeWithText("Ordered workspace").performClick(); waitFor("Ready shell ▾")
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" } }
        compose.runOnIdle { session.coordinator.pause() }
        compose.waitUntil(5_000) { session.coordinator.sources.value.values.all { it.availability == NativeFeedAvailability.OFFLINE } }
        val admittedConnections = connections.get()
        peer.terminalCreationResponse = {
            peer.customWorkspaceListing = listing(created = true)
            JSONObject(peer.customWorkspaceListing.toString()).put("created_terminal_id", "new-terminal")
        }
        compose.onNodeWithContentDescription("Choose terminal or pane").performClick()
        compose.onNodeWithText("New Terminal").performClick()
        try { waitFor("New shell ▾") } catch (failure: Throwable) {
            val connection = session.connectionState(code)
            val state = session.workspaceCreation.state.value
            val diagnostic = JSONObject().put("creationState", state.javaClass.simpleName)
                .put("creationStillAllowed", state.request?.let(session::allowsCreation))
                .put("ready", connection.connectionReady).put("clientClosed", connection.client?.isClosed)
                .put("snapshotWorkspaces", JSONArray(session.workspaceSnapshots.latest(store.pairedMacs().single())?.workspaces?.map { it.id }.orEmpty()))
                .put("methods", JSONObject(peer.requests.groupingBy { it.optString("method") }.eachCount()))
                .put("ui", compose.onRoot(useUnmergedTree = true).printToString())
            InstrumentationRegistry.getInstrumentation().sendStatus(0,
                android.os.Bundle().apply { putString("stream", "\nFOREGROUND_CREATION_DIAGNOSTIC $diagnostic\n") })
            throw failure
        }
        compose.onNodeWithTag("TerminalStarting").assertExists()
        assertEquals(1, peer.requests.count { it.optString("method") == "terminal.create" })
        assertEquals(admittedConnections, connections.get())
        assertTrue(session.coordinator.sources.value.values.all { it.availability == NativeFeedAvailability.OFFLINE })
        scenario!!.recreate(); waitFor("New shell ▾")
        assertEquals(1, peer.requests.count { it.optString("method") == "terminal.create" })
    }

    @Test fun slowForegroundReadFromBeforeCreateCannotRemoveTheNewTerminal() {
        val gate = CountDownLatch(1); val blocked = AtomicBoolean(); val armed = AtomicBoolean(true)
        val foregroundReads = AtomicInteger()
        try {
            launch()
            compose.onNodeWithText("Ordered workspace").performClick(); waitFor("Ready shell ▾")
            compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" } }
            val originalReplays = peer.requests.count { it.optString("method") == "mobile.terminal.replay" &&
                it.optJSONObject("params")?.optString("surface_id") == "terminal-1" }
            peer.workspaceListingResponse = { feed ->
                if (!feed) foregroundReads.incrementAndGet()
                if (!feed && armed.compareAndSet(true, false)) {
                    val captured = JSONObject(peer.customWorkspaceListing.toString())
                    blocked.set(true); gate.await(15, TimeUnit.SECONDS); captured
                } else JSONObject(peer.customWorkspaceListing.toString())
            }
            peer.pushTerminalEvent("workspace.updated", JSONObject())
            compose.waitUntil(5000) { blocked.get() }
            peer.terminalCreationResponse = {
                peer.customWorkspaceListing = listing(created = true)
                JSONObject(peer.customWorkspaceListing.toString()).put("created_terminal_id", "new-terminal")
            }
            compose.onNodeWithContentDescription("Choose terminal or pane").performClick()
            compose.onNodeWithText("New Terminal").performClick(); waitFor("Starting terminal…")
            gate.countDown()
            compose.waitUntil(15_000) { foregroundReads.get() >= 2 }
            compose.waitForIdle()
            compose.onNodeWithText("New shell ▾").assertExists(); compose.onNodeWithTag("TerminalStarting").assertExists()
            assertEquals(1, peer.requests.count { it.optString("method") == "terminal.create" })
            assertEquals(originalReplays, peer.requests.count { it.optString("method") == "mobile.terminal.replay" &&
                it.optJSONObject("params")?.optString("surface_id") == "terminal-1" })
        } finally { gate.countDown() }
    }

    @Test fun settingsLocalRemovalClearsOnlyTheRenderedPairingAndClosesItsRootWithoutSigningOut() {
        launch()
        val login = store.taskSession()
        val root = checkNotNull(session.connectionState("").client)
        assertEquals(1, store.pairedMacs().size)
        compose.onNodeWithContentDescription("cmux settings").performClick()
        compose.onNodeWithTag("settings.remove-local-pairing").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { store.pairedMacs().isEmpty() && session.connectionState("").client == null }
        assertTrue(root.isClosed); assertEquals(login, store.taskSession())
        assertEquals("snapshot-order-emulator-fixture", store.load()!!.getString("refresh_token"))
        assertEquals("", store.load()!!.optString("pairing_code"))
    }

    @Test fun agentFeedUsesRetainedRootWithSecondaryFeedPausedAndSurvivesRecreationWithoutActionReplay() {
        val rows = JSONArray("""[{"id":"root-permission","workstream_id":"root-turn","source":"claude",
          "kind":"permissionRequest","status":"pending","request_id":"root-request","created_at":200,"updated_at":200,
          "title":"Root permission","tool_name":"Read","workspace_id":"workspace-1","surface_id":"terminal-1"}]""")
        var revision = 1
        peer.agentFeedResponse = { method, _ -> synchronized(rows) {
            if (method == "feed.list") JSONObject().put("revision", revision).put("items", JSONArray(rows.toString()))
            else { rows.getJSONObject(0).put("status", "resolved"); revision++; JSONObject() }
        } }
        launch()
        val mac = store.pairedMacs().single()
        compose.waitUntil(10_000) { session.agentFeedSession(mac)?.state?.value?.snapshot?.items?.any { it.id == "root-permission" } == true }
        val root = session.connectionState("").client
        val agent = session.agentFeedSession(mac)
        compose.runOnIdle { session.coordinator.pause() }
        compose.onNode(hasText("Feed", substring = true) and isSelectable()).performClick()
        compose.onNodeWithText("Allow Once").performClick()
        compose.waitUntil(10_000) { peer.requests.count { it.optString("method") == "feed.permission.reply" } == 1 }
        compose.waitUntil(10_000) { agent?.state?.value?.snapshot?.items?.singleOrNull()?.status == AgentFeedStatus.RESOLVED }
        assertTrue(session.coordinator.sources.value.values.all { it.availability == NativeFeedAvailability.OFFLINE })
        assertSame(root, session.connectionState("").client)
        scenario!!.recreate()
        compose.waitUntil(10_000) { session.agentFeedSession(mac) != null }
        assertSame(root, session.connectionState("").client)
        assertSame(agent, session.agentFeedSession(mac))
        assertEquals(1, peer.requests.count { it.optString("method") == "feed.permission.reply" })
    }

    @Test fun connectedWorkspaceReordersThroughItsExistingClientWithTheSecondaryFeedPaused() {
        peer.customWorkspaceListing = JSONObject("""{"workspaces":[
            {"id":"workspace-1","title":"Ordered workspace","window_id":"fixture-window","terminals":[{"id":"terminal-1","title":"Ready shell","is_focused":true}]},
            {"id":"workspace-2","title":"Second workspace","window_id":"fixture-window","terminals":[]}]}""")
        launch()
        compose.onNodeWithText("Ordered workspace").performClick(); waitFor("Ready shell ▾")
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" } }
        compose.runOnIdle { session.coordinator.pause() }
        val mac = store.pairedMacs().single()
        compose.waitUntil(5_000) { session.workspaceMoves.sources.value[mac.origin]?.canReorderWorkspaces() == true }
        val source = session.workspaceMoves.sources.value.getValue(mac.origin)
        val admittedConnections = connections.get()
        runBlocking { withTimeout(10_000) { withContext(Dispatchers.Main) {
            session.workspaceMoves.submit(source, "workspace-2", NativeWorkspaceMove(null, "workspace-1")) { true }
        } } }
        compose.waitUntil(5_000) {
            session.workspaceMoves.sources.value[mac.origin]?.workspaces?.map { it.id } == listOf("workspace-2", "workspace-1") &&
                session.workspaceMoves.status.value[mac.origin]?.pending == 0
        }
        assertNull(session.workspaceMoves.status.value.getValue(mac.origin).error)
        val writes = peer.requests.filter { it.optString("method") == "workspace.move" }
        assertEquals(1, writes.size); assertEquals("fixture-window", writes.single().getJSONObject("params").getString("window_id"))
        assertEquals(admittedConnections, connections.get())
        assertTrue(session.coordinator.sources.value.values.all { it.availability == NativeFeedAvailability.OFFLINE })
        compose.onNodeWithText("Ready shell ▾").assertExists()
    }

    @Test fun notificationReadActionsUseTheRetainedClientWithTheSecondaryFeedPaused() {
        peer.notificationRevisionsSupported = true
        peer.notificationFeed = JSONArray().put(JSONObject().put("id", "note-one").put("workspace_id", "workspace-1")
            .put("surface_id", "terminal-1").put("title", "First fixture alert"))
            .put(JSONObject().put("id", "note-two").put("workspace_id", "workspace-1")
                .put("surface_id", "terminal-1").put("title", "Second fixture alert"))
        launch()
        compose.onNodeWithText("Ordered workspace").performClick(); waitFor("Ready shell ▾")
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" } }
        compose.runOnIdle { session.coordinator.pause() }
        val mac = store.pairedMacs().single()
        val owner = session.connectionState(code)
        val client = checkNotNull(owner.client)
        val admittedConnections = connections.get()
        runBlocking { withTimeout(10_000) { withContext(Dispatchers.Main) {
            val items = session.refreshNotifications(mac, client)
            session.setNotificationRead(NativeFeedEntry(NativeFeedSource(mac), items.single { it.id == "note-one" }), true)
            session.markNotificationsRead(listOf(mac))
        } } }
        assertTrue(owner.notifications.all { it.isRead }); assertEquals(2, owner.notifications.size)
        assertEquals(1, peer.requests.count { it.optString("method") == "notification.feed.mark_read" })
        assertEquals(1, peer.requests.count { it.optString("method") == "notification.feed.mark_all_read" })
        assertEquals(admittedConnections, connections.get())
        assertTrue(session.coordinator.sources.value.values.all { it.availability == NativeFeedAvailability.OFFLINE })
        val acknowledgedRevision = owner.notificationsRevision
        assertTrue(acknowledgedRevision >= 0)
        scenario!!.recreate(); waitFor("Ready shell ▾")
        assertSame(client, owner.client); assertEquals(acknowledgedRevision, owner.notificationsRevision)
        assertEquals(1, peer.requests.count { it.optString("method") == "notification.feed.mark_read" })
        assertEquals(1, peer.requests.count { it.optString("method") == "notification.feed.mark_all_read" })
    }

    @Test fun rejectedAdmittedForegroundRemainsHeldThroughRecreationUntilExplicitRetry() {
        launch(); compose.onNodeWithText("Ordered workspace").performClick(); waitFor("Ready shell ▾")
        val state = session.connectionState(code)
        val original = checkNotNull(state.client)
        val admitted = checkNotNull(state.admittedOwner)
        compose.runOnIdle { session.coordinator.pause(); original.retireForOwnerFailure(MobileRpcException("forbidden", "Fixture rejection", true)) }
        compose.waitUntil(5_000) { session.foregroundRecovery.state.value?.requiresReauth == true && !state.connectionReady }
        val attempts = connections.get()
        val admittedGeneration = state.admittedRetry
        assertTrue(session.foregroundRecovery.state.value!!.owner.sameIdentity(admitted))
        scenario!!.recreate()
        compose.waitUntil(5_000) { session.foregroundRecovery.state.value?.requiresReauth == true }
        compose.waitForIdle()
        // Recreation may resume the selected-team feed. The rejected foreground must remain
        // the same closed handle and admission generation until an explicit retry.
        assertTrue(connections.get() <= attempts + 1)
        assertSame(original, state.client); assertFalse(state.connectionReady)
        assertEquals(admitted, state.admittedOwner); assertEquals(admittedGeneration, state.admittedRetry)
        compose.runOnIdle { session.foregroundRetryState.intValue++ }
        compose.waitUntil(15_000) { state.connectionReady && state.client !== original }
        assertNull(session.foregroundRecovery.state.value)
        assertEquals(admitted.login, state.admittedOwner!!.login); assertEquals(admitted.code, state.admittedOwner!!.code)
    }

    @Test fun ordinaryForegroundDropReentersFreshAdmissionAndRetainsThePane() {
        launch(); compose.onNodeWithText("Ordered workspace").performClick(); waitFor("Ready shell ▾")
        val state = session.connectionState(code)
        val original = checkNotNull(state.client)
        val admitted = checkNotNull(state.admittedOwner)
        peer.disconnectClients()
        compose.waitUntil(20_000) { state.connectionReady && state.client !== original }
        waitFor("Ready shell ▾")
        assertTrue(original.isClosed); assertNull(session.foregroundRecovery.state.value)
        assertEquals(admitted, state.admittedOwner)
        assertTrue(state.admittedRetry!! > 0)
    }

    @Test fun foregroundChangesReadsRetainPresentationThroughRecreationAndRecoverOnANewAdmittedClient() {
        peer.workspaceChangesSupported = true
        peer.changesResponse = { method, params ->
            assertEquals("workspace-1", params.getString("workspace_id"))
            if (method.endsWith(".file_diff")) JSONObject().put("path", "README.md")
                .put("unified_diff", "@@ -1 +1 @@\n-old\n+Foreground fixture diff\n").put("truncated", false)
            else JSONObject("""{"workspace_id":"workspace-1","repo_root":"/fixture","files":[{"path":"README.md","status":"modified"}]}""")
        }
        launch(); compose.onNodeWithText("Ordered workspace").performClick(); waitFor("Ready shell ▾")
        val mac = store.pairedMacs().single()
        val state = session.connectionState(code)
        val originalClient = checkNotNull(state.client)
        val admittedConnections = connections.get()
        compose.runOnIdle {
            session.coordinator.pause()
            session.openChanges(mac, checkNotNull(session.workspaceSnapshots.latest(mac)).workspaces.single())
        }
        val presentation = checkNotNull(session.changesSheet)
        compose.waitUntil(5_000) { presentation.store.listing.value.snapshot?.files?.singleOrNull()?.path == "README.md" }
        compose.runOnIdle { presentation.navigation.selected = "README.md"; presentation.store.select("README.md") }
        compose.waitUntil(5_000) { presentation.store.page("README.md").value.document != null }
        assertEquals(admittedConnections, connections.get())
        val originalReads = peer.requests.count { it.optString("method") == "mobile.workspace.changes.files" }
        scenario!!.recreate()
        compose.waitForIdle(); assertSame(presentation, session.changesSheet)
        assertEquals("README.md", presentation.navigation.selected)
        assertEquals(originalReads, peer.requests.count { it.optString("method") == "mobile.workspace.changes.files" })
        peer.disconnectClients()
        compose.waitUntil(20_000) { state.connectionReady && state.client !== originalClient }
        compose.waitUntil(10_000) {
            peer.requests.count { it.optString("method") == "mobile.workspace.changes.files" } > originalReads &&
                presentation.store.page("README.md").value.document != null && !presentation.store.listing.value.loading
        }
        assertSame(presentation, session.changesSheet); assertEquals("README.md", presentation.navigation.selected)
        assertTrue(peer.requests.count { it.optString("method") == "mobile.workspace.changes.files" } > originalReads)
    }

    @Test fun lateBrowserDiscoveryCannotRestoreAWorkspaceDeletedByANewerFeedSnapshot() {
        val gate = CountDownLatch(1)
        peer.browserCreationSupported = true
        peer.browserResponse = { method, _ ->
            if (method == "mobile.browser.list") {
                gate.await(15, TimeUnit.SECONDS)
                JSONObject().put("panels", JSONArray().put(JSONObject().put("panel_id", "browser")
                    .put("workspace_id", "workspace-1").put("title", "Removed browser").put("page_width", 800).put("page_height", 600)
                    .put("can_go_back", false).put("can_go_forward", false).put("is_loading", false)))
            } else JSONObject()
        }
        val login = store.taskSession()!!
        val key = workspaceTabKey(login, null, store.pairedMacs().single(), "workspace-1")!!
        store.rememberWorkspaceTab(login, key, NativeWorkspaceTab(NativeWorkspaceTabKind.BROWSER_STREAM, "browser"))
        try {
            launch(); compose.onNodeWithText("Ordered workspace").performClick(); waitFor("Ready shell ▾")
            compose.waitUntil(5000) { peer.requests.any { it.optString("method") == "mobile.browser.list" } }
            peer.customWorkspaceListing = JSONObject().put("workspaces", JSONArray())
            peer.pushTerminalEvent("workspace.updated", JSONObject())
            compose.waitUntil(10_000) { session.coordinator.sources.value.values.any { it.hasWorkspaceSnapshot && it.workspaces.isEmpty() } }
            gate.countDown()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Ready shell ▾").fetchSemanticsNodes().isEmpty() }
            compose.waitForIdle()
            assertFalse(peer.requests.any { it.optString("method") == "mobile.browser.stream.start" })
            assertTrue(session.workspaceTabs.pending.value == null)
        } finally { gate.countDown() }
    }
}
