package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalTestApi::class)
class WorkspaceActivityRestorationTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private lateinit var store: NativeCredentialStore
    private lateinit var peer: NativeFixturePeer
    private var scenario: ActivityScenario<NativeLifecycleTestActivity>? = null
    private val offline = AtomicBoolean(false)
    private val offlineAttempts = java.util.concurrent.atomic.AtomicInteger()
    private val code = "cmux-ios://attach?v=2&r=100.64.0.1:58465"
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        store = NativeCredentialStore(InstrumentationRegistry.getInstrumentation().targetContext); store.clear()
        store.update { it.put("refresh_token", "activity-pane-fixture").put("pairing_code", code) }
        store.rememberMac(code, "fixture-mac", "Fixture Mac")
        peer = NativeFixturePeer(); listing()
        NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
            if (offline.get()) { offlineAttempts.incrementAndGet(); error("Fixture Mac offline") }
            MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
        }
    }
    @After fun cleanup() {
        scenario?.close(); NativeLifecycleTestActivity.connector = null
        if (::peer.isInitialized) { peer.close(); store.clear() }
    }
    private fun listing(terminals: String = """[{"id":"terminal-1","title":"First shell"},{"id":"terminal-2","title":"Focused shell","is_focused":true}]""", surfaces: String = "[]") {
        peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"workspace-1","title":"Retained workspace","terminals":$terminals,"surfaces":$surfaces}]}""")
    }
    private fun launch(open: Boolean = true) {
        scenario = ActivityScenario.launch(NativeLifecycleTestActivity::class.java); waitFor("Retained workspace")
        if (open) compose.onNodeWithText("Retained workspace").performClick()
    }
    private fun waitFor(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun calls(method: String) = peer.requests.filter { it.optString("method") == method }
    private fun session(): NativeFeedSession {
        lateinit var result: NativeFeedSession
        scenario!!.onActivity { result = androidx.lifecycle.ViewModelProvider(it)[NativeFeedSession::class.java] }
        return result
    }
    @Test fun explicitTerminalAndBackDestinationSurviveRecreation() {
        launch(); waitFor("Focused shell ▾"); compose.onNodeWithText("First shell").performClick(); waitFor("First shell ▾")
        val replays = calls("mobile.terminal.replay").size
        scenario!!.recreate(); waitFor("First shell ▾")
        compose.waitUntil(15_000) { calls("mobile.terminal.replay").size > replays }
        assertEquals("terminal-1", calls("mobile.terminal.replay").last().getJSONObject("params").getString("surface_id"))
        compose.onNodeWithContentDescription("Back to workspaces").performClick(); waitFor("Retained workspace")
        scenario!!.recreate(); waitFor("Retained workspace"); compose.onNodeWithText("First shell ▾").assertDoesNotExist()
    }
    @Test fun offlineRecreationKeepsTheSelectedMacPanel() {
        listing(surfaces = """[{"surface_id":"project","kind":"project","title":"Project panel","is_focused":true}]""")
        launch(); waitFor("Project panel ▾"); offline.set(true)
        scenario!!.recreate(); waitFor("Project panel ▾")
        compose.waitUntil(15_000) { offlineAttempts.get() > 0 }
        waitFor("Reconnecting to your Mac…")
        compose.onNodeWithText("Open on Mac").assertIsNotEnabled()
        assertTrue(calls("mobile.terminal.replay").isEmpty())
    }
    @Test fun browserRecreationRestartsTheSameStream() {
        listing(surfaces = """[{"surface_id":"browser","kind":"browser","title":"Browser","is_focused":true}]""")
        launch(); compose.waitUntil(15_000) { calls("mobile.browser.stream.start").isNotEmpty() }
        val count = calls("mobile.browser.stream.start").size
        scenario!!.recreate(); compose.waitUntil(15_000) { calls("mobile.browser.stream.start").size > count }
        assertEquals(setOf("browser"), calls("mobile.browser.stream.start").map { it.getJSONObject("params").getString("panel_id") }.toSet())
        assertTrue(calls("mobile.terminal.replay").isEmpty())
    }
    @Test fun emptyWorkspaceStaysOpenAndAcceptsItsLateTerminalAfterRecreation() {
        listing(terminals = "[]"); launch(); waitFor("Waiting for workspace panes…")
        scenario!!.recreate(); waitFor("Waiting for workspace panes…")
        listing(); waitFor("Focused shell ▾")
    }
    @Test fun createdTerminalKeepsItsOriginalDeadlineAndDoesNotCreateAgain() {
        peer.terminalCreationResponse = {
            listing(terminals = """[{"id":"new-terminal","title":"New shell","is_ready":false},{"id":"terminal-1","title":"Ready shell"}]""")
            JSONObject(peer.customWorkspaceListing.toString()).put("created_terminal_id", "new-terminal")
        }
        listing(terminals = "[]"); launch(); waitFor("Waiting for workspace panes…")
        compose.onNodeWithText("New terminal").performClick(); waitFor("Starting terminal…")
        val before = session().terminalStartup.state.value.pending!!
        scenario!!.recreate(); waitFor("Starting terminal…")
        assertEquals(before, session().terminalStartup.state.value.pending)
        assertEquals(1, calls("terminal.create").size)
        assertTrue(calls("mobile.terminal.replay").isEmpty())
        listing(terminals = """[{"id":"new-terminal","title":"New shell","is_ready":true},{"id":"terminal-1","title":"Ready shell"}]""")
        compose.waitUntil(15_000) { calls("mobile.terminal.replay").any { it.getJSONObject("params").optString("surface_id") == "new-terminal" } }
        compose.onNodeWithText("New shell ▾").assertExists()
    }
    @Test fun pendingRememberedBrowserSurvivesRecreationWithItsInterimTerminal() {
        val available = AtomicBoolean(false)
        val descriptor = JSONObject().put("panel_id", "remembered-browser").put("workspace_id", "workspace-1")
            .put("title", "Remembered browser").put("page_width", 800).put("page_height", 600)
            .put("can_go_back", false).put("can_go_forward", false).put("is_loading", false)
        peer.browserCreationSupported = true
        peer.browserResponse = { method, _ -> if (method == "mobile.browser.list") {
            if (available.get()) JSONObject().put("panels", JSONArray().put(descriptor)) else JSONObject()
        } else descriptor }
        val login = store.taskSession()!!
        val key = workspaceTabKey(login, null, store.pairedMacs().single(), "workspace-1")!!
        store.rememberWorkspaceTab(login, key, NativeWorkspaceTab(NativeWorkspaceTabKind.BROWSER_STREAM, "remembered-browser"))
        launch(); waitFor("Focused shell ▾")
        val before = session().workspaceTabs.pending.value!!
        scenario!!.recreate(); waitFor("Focused shell ▾")
        assertEquals(before, session().workspaceTabs.pending.value)
        available.set(true); compose.waitUntil(15_000) { calls("mobile.browser.stream.start").isNotEmpty() }
        assertEquals("remembered-browser", store.lastWorkspaceTab(login, key)?.id)
    }
    @Test fun replacedLoginDoesNotRestoreThePreviousAccountsPane() {
        launch(); waitFor("Focused shell ▾")
        store.clear(); store.update { it.put("refresh_token", "replacement-activity-fixture").put("pairing_code", code) }
        store.rememberMac(code, "fixture-mac", "Fixture Mac")
        scenario!!.recreate(); waitFor("Retained workspace")
        compose.onNodeWithText("Focused shell ▾").assertDoesNotExist()
        assertNull(session().terminalStartup.state.value.pending)
    }
    @Test fun simulatorSelectionSurvivesRecreation() {
        peer.customWorkspaceListing!!.getJSONArray("workspaces").getJSONObject(0).put("simulators", JSONArray().put(
            JSONObject().put("panel_id", "11111111-1111-4111-8111-111111111111").put("workspace_id", "workspace-1")
                .put("title", "Retained Simulator").put("status", "ready").put("is_ready", true)
                .put("supports_touch", true).put("supports_keyboard", true).put("supports_hardware_buttons", true).put("supports_rotation", true)))
        launch(); waitFor("Retained Simulator ▾")
        scenario!!.recreate(); waitFor("Retained Simulator ▾")
        assertTrue(calls("mobile.terminal.replay").isEmpty())
    }
    @Test fun changesSurvivesRecreationAndHostRefreshThenClosesToWorkspaceList() {
        launch(open = false)
        compose.onNodeWithContentDescription("Actions for Retained workspace").performClick()
        compose.onNodeWithText("View changes").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Changes in Retained workspace").fetchSemanticsNodes().isNotEmpty() }
        scenario!!.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Close changes").fetchSemanticsNodes().isNotEmpty() }
        val requests = calls("mobile.workspace.list").size
        peer.pushTerminalEvent("workspace.updated", JSONObject())
        compose.waitUntil(15_000) { calls("mobile.workspace.list").size > requests }
        compose.waitForIdle(); compose.onNodeWithContentDescription("Close changes").performClick(); waitFor("Retained workspace")
        compose.onNodeWithText("Focused shell ▾").assertDoesNotExist()
        assertTrue(calls("mobile.terminal.replay").isEmpty())
    }
}
