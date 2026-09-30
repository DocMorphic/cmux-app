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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Full screen/account fixtures are restricted to the owned emulator. */
@OptIn(ExperimentalTestApi::class)
class WorkspaceTabRestorationTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private lateinit var store: NativeCredentialStore
    private lateinit var peer: NativeFixturePeer
    private var scenario: ActivityScenario<NativeLifecycleTestActivity>? = null
    private lateinit var key: NativeWorkspaceTabKey
    private lateinit var login: String
    private val code = "cmux-ios://attach?v=2&r=100.64.0.1:58465"
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        store = NativeCredentialStore(InstrumentationRegistry.getInstrumentation().targetContext); store.clear()
        store.update { it.put("refresh_token", "tab-restore-emulator-fixture").put("pairing_code", code) }
        store.rememberMac(code, "fixture-mac", "Fixture Mac")
        login = store.taskSession()!!; key = workspaceTabKey(login, null, store.pairedMacs().single(), "workspace-1")!!
        peer = NativeFixturePeer()
        listing()
        NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
            MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
        }
    }
    @After fun cleanup() {
        scenario?.close(); NativeLifecycleTestActivity.connector = null
        if (::peer.isInitialized) { peer.close(); store.clear() }
    }
    private fun listing(starting: Boolean = false, surfaces: String = "[]", terminals: Boolean = true) {
        peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"workspace-1","title":"Remembered workspace",
            "terminals":${if (terminals) """[{"id":"terminal-1","title":"First shell","is_ready":${!starting}},
                {"id":"terminal-2","title":"Ready shell","is_focused":true}]""" else "[]"},"surfaces":$surfaces}]}""")
    }
    private fun launch() {
        scenario = ActivityScenario.launch(NativeLifecycleTestActivity::class.java)
        waitFor("Remembered workspace")
    }
    private fun open() { compose.onNodeWithText("Remembered workspace").performClick() }
    private fun waitFor(text: String) = compose.waitUntil(15000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun seed(kind: NativeWorkspaceTabKind, id: String) { store.rememberWorkspaceTab(login, key, NativeWorkspaceTab(kind, id)) }
    private fun remembered() = store.lastWorkspaceTab(login, key)
    private fun browserDescriptor() = JSONObject().put("panel_id", "remembered-browser").put("workspace_id", "workspace-1")
        .put("title", "Remembered browser").put("page_width", 800).put("page_height", 600)
        .put("can_go_back", false).put("can_go_forward", false).put("is_loading", false)

    @Test fun explicitTerminalSurvivesBackAndAFreshActivityWithChangedMacFocus() {
        launch(); open(); waitFor("Ready shell ▾")
        compose.onNodeWithText("First shell").performClick(); waitFor("First shell ▾")
        compose.waitUntil(5000) { remembered()?.id == "terminal-1" }
        compose.onNodeWithContentDescription("Back to workspaces").performClick(); waitFor("Remembered workspace")
        open(); waitFor("First shell ▾")
        scenario!!.close(); scenario = null; launch(); open(); waitFor("First shell ▾")
        assertEquals("terminal-1", remembered()?.id)
    }
    @Test fun rememberedMacSurfaceOverridesTerminalFocusAfterActivityRecreation() {
        listing(surfaces = """[{"surface_id":"project","kind":"project","title":"Project panel"}]""")
        seed(NativeWorkspaceTabKind.MAC_SURFACE, "project")
        launch(); open(); waitFor("Project panel ▾")
        scenario!!.recreate(); waitFor("Remembered workspace"); open(); waitFor("Project panel ▾")
        assertTrue(peer.requests.none { it.optString("method").startsWith("mobile.terminal.") })
    }
    @Test fun startingRememberedTerminalKeepsMemoryUntilReadyThenReplacesTheFallback() {
        listing(starting = true); seed(NativeWorkspaceTabKind.TERMINAL, "terminal-1")
        launch(); open(); waitFor("Ready shell ▾")
        assertEquals("terminal-1", remembered()?.id)
        listing(); waitFor("First shell ▾")
        assertEquals("terminal-1", remembered()?.id)
    }
    @Test fun choosingTheCurrentFallbackCancelsAWaitingRestore() {
        listing(starting = true); seed(NativeWorkspaceTabKind.TERMINAL, "terminal-1")
        launch(); open(); waitFor("Ready shell ▾")
        compose.onNodeWithText("Ready shell", substring = false).performClick()
        compose.waitUntil(5000) { remembered()?.id == "terminal-2" }
        listing(); scenario!!.recreate(); waitFor("Remembered workspace"); open(); waitFor("Ready shell ▾")
        assertEquals("terminal-2", remembered()?.id)
    }
    @Test fun browserDiscoveryRestoresTheExactPanelWithoutOverwritingMemory() {
        peer.browserCreationSupported = true
        val gate = CountDownLatch(1)
        peer.browserResponse = { method, _ ->
            if (method == "mobile.browser.list") { gate.await(15, TimeUnit.SECONDS); JSONObject().put("panels", JSONArray().put(browserDescriptor())) }
            else browserDescriptor()
        }
        seed(NativeWorkspaceTabKind.BROWSER_STREAM, "remembered-browser")
        try {
            launch(); open(); waitFor("Ready shell ▾")
            compose.waitUntil(5000) { peer.requests.any { it.optString("method") == "mobile.browser.list" } }
            assertEquals(NativeWorkspaceTabKind.BROWSER_STREAM, remembered()?.kind)
            gate.countDown()
            compose.waitUntil(15000) { peer.requests.any { it.optString("method") == "mobile.browser.stream.start" } }
            assertEquals("remembered-browser", peer.requests.first { it.optString("method") == "mobile.browser.stream.start" }
                .getJSONObject("params").getString("panel_id"))
        } finally { gate.countDown() }
    }
    @Test fun localBrowserMemoryReopensWithoutAMacCreateAndCloseClearsIt() {
        seed(NativeWorkspaceTabKind.LOCAL_BROWSER, "local")
        launch(); open()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("LocalBrowserAddress").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(peer.requests.none { it.optString("method") == "mobile.browser.create" })
        compose.onNodeWithContentDescription("Close Browser").performClick(); waitFor("First shell ▾")
        compose.waitUntil(5000) { remembered()?.kind == NativeWorkspaceTabKind.TERMINAL }
        scenario!!.close(); scenario = null; launch(); open(); waitFor("First shell ▾")
        compose.onNodeWithTag("LocalBrowserPane").assertDoesNotExist()
    }
    @Test fun hostReplacementOfTheInterimTerminalKeepsTheRememberedBrowserIntent() {
        peer.browserCreationSupported = true
        val browserAvailable = java.util.concurrent.atomic.AtomicBoolean(false)
        peer.browserResponse = { method, _ ->
            if (method == "mobile.browser.list") {
                if (browserAvailable.get()) JSONObject().put("panels", JSONArray().put(browserDescriptor()))
                else JSONObject() // Incomplete discovery is not confirmed absence.
            } else browserDescriptor()
        }
        seed(NativeWorkspaceTabKind.BROWSER_STREAM, "remembered-browser")
        launch(); open(); waitFor("Ready shell ▾")
        compose.waitUntil(5000) { peer.requests.any { it.optString("method") == "mobile.browser.list" } }
        val changed = JSONObject(peer.customWorkspaceListing.toString())
        changed.getJSONArray("workspaces").getJSONObject(0).put("terminals", JSONArray("""[{"id":"replacement","title":"Replacement shell"}]"""))
        peer.customWorkspaceListing = changed
        peer.pushTerminalEvent("workspace.updated", JSONObject())
        waitFor("Replacement shell ▾")
        assertEquals("remembered-browser", remembered()?.id)
        browserAvailable.set(true)
        compose.waitUntil(15000) { peer.requests.any { it.optString("method") == "mobile.browser.stream.start" } }
        assertEquals("remembered-browser", peer.requests.first { it.optString("method") == "mobile.browser.stream.start" }
            .getJSONObject("params").getString("panel_id"))
    }
    @Test fun lateBrowserDiscoveryCannotReplaceANewerExplicitTerminal() {
        peer.browserCreationSupported = true
        val gate = CountDownLatch(1)
        peer.browserResponse = { method, _ ->
            if (method == "mobile.browser.list") { gate.await(15, TimeUnit.SECONDS); JSONObject().put("panels", JSONArray().put(browserDescriptor())) }
            else browserDescriptor()
        }
        seed(NativeWorkspaceTabKind.BROWSER_STREAM, "remembered-browser")
        try {
            launch(); open(); waitFor("Ready shell ▾")
            compose.waitUntil(5000) { peer.requests.any { it.optString("method") == "mobile.browser.list" } }
            compose.onNodeWithText("First shell").performClick(); waitFor("First shell ▾")
            gate.countDown()
            compose.waitUntil(10000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" &&
                it.getJSONObject("params").optString("surface_id") == "terminal-1" } }
            compose.waitForIdle()
            assertEquals("terminal-1", remembered()?.id)
            assertTrue(peer.requests.none { it.optString("method") == "mobile.browser.stream.start" })
        } finally { gate.countDown() }
    }
    @Test fun rememberedSimulatorSelectsItsDescriptorInsteadOfTheFocusedTerminal() {
        val id = "11111111-1111-4111-8111-111111111111"
        peer.customWorkspaceListing!!.getJSONArray("workspaces").getJSONObject(0).put("simulators", JSONArray().put(
            JSONObject().put("panel_id", id).put("workspace_id", "workspace-1").put("title", "Remembered Simulator")
                .put("status", "ready").put("is_ready", true).put("supports_touch", true).put("supports_keyboard", true)
                .put("supports_hardware_buttons", true).put("supports_rotation", true)))
        seed(NativeWorkspaceTabKind.SIMULATOR_STREAM, id)
        launch(); open(); waitFor("Remembered Simulator ▾")
        assertEquals(NativeWorkspaceTabKind.SIMULATOR_STREAM, remembered()?.kind)
        assertTrue(peer.requests.none { it.optString("method").startsWith("mobile.terminal.") })
    }
    @Test fun browserRestoreSurvivesBackgroundAndStartsOnlyAfterReturn() {
        peer.browserCreationSupported = true
        val gate = CountDownLatch(1)
        val replied = CountDownLatch(1)
        peer.browserResponse = { method, _ ->
            if (method == "mobile.browser.list") {
                gate.await(15, TimeUnit.SECONDS); replied.countDown()
                JSONObject().put("panels", JSONArray().put(browserDescriptor()))
            } else browserDescriptor()
        }
        seed(NativeWorkspaceTabKind.BROWSER_STREAM, "remembered-browser")
        try {
            launch(); open(); waitFor("Ready shell ▾")
            compose.waitUntil(5000) { peer.requests.any { it.optString("method") == "mobile.browser.list" } }
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            gate.countDown(); assertTrue(replied.await(5, TimeUnit.SECONDS))
            assertTrue(peer.requests.none { it.optString("method") == "mobile.browser.stream.start" })
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitUntil(15000) { peer.requests.any { it.optString("method") == "mobile.browser.stream.start" } }
            assertEquals(NativeWorkspaceTabKind.BROWSER_STREAM, remembered()?.kind)
        } finally { gate.countDown() }
    }
    @Test fun closingARememberedLocalBrowserInAnEmptyWorkspaceDoesNotReopenIt() {
        listing(terminals = false); seed(NativeWorkspaceTabKind.LOCAL_BROWSER, "local")
        launch()
        // An empty row has no live Mac panes, but persisted local browser memory makes it navigable.
        open()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("LocalBrowserAddress").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Close Browser").performClick(); waitFor("Remembered workspace")
        compose.onNodeWithTag("LocalBrowserPane").assertDoesNotExist()
        assertNull(remembered())
        compose.onNodeWithText("This workspace pane is no longer available.").assertDoesNotExist()
    }
}
