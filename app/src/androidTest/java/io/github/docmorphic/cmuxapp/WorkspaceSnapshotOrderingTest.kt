package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
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
    private val code = "cmux-ios://attach?v=2&r=100.64.0.1:58465"
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        store = NativeCredentialStore(InstrumentationRegistry.getInstrumentation().targetContext); store.clear()
        store.update { it.put("refresh_token", "snapshot-order-emulator-fixture").put("pairing_code", code) }
        store.rememberMac(code, "fixture-mac", "Fixture Mac")
        peer = NativeFixturePeer(); peer.customWorkspaceListing = listing()
        NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
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

    @Test fun slowForegroundReadFromBeforeCreateCannotRemoveTheNewTerminal() {
        val gate = CountDownLatch(1); val blocked = AtomicBoolean(); val armed = AtomicBoolean(true)
        val foregroundReads = AtomicInteger()
        try {
            launch()
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
            compose.onNodeWithText("Ordered workspace").performTouchInput { longClick() }
            compose.onNodeWithText("New terminal").performClick(); waitFor("Starting terminal…")
            gate.countDown()
            compose.waitUntil(15_000) { foregroundReads.get() >= 2 }
            compose.waitForIdle()
            compose.onNodeWithText("New shell ▾").assertExists(); compose.onNodeWithTag("TerminalStarting").assertExists()
            assertEquals(1, peer.requests.count { it.optString("method") == "terminal.create" })
            assertFalse(peer.requests.any { it.optString("method") == "mobile.terminal.replay" &&
                it.optJSONObject("params")?.optString("surface_id") == "terminal-1" })
        } finally { gate.countDown() }
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
