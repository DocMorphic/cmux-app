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
