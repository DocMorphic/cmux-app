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

/** Synthetic account and server: never run on a user's physical device. */
@OptIn(ExperimentalTestApi::class)
class WorkspaceDelayedPanesTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private lateinit var store: NativeCredentialStore
    private lateinit var peer: NativeFixturePeer
    private var scenario: ActivityScenario<NativeLifecycleTestActivity>? = null
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        store = NativeCredentialStore(InstrumentationRegistry.getInstrumentation().targetContext); store.clear()
        store.update { it.put("refresh_token", "delayed-panes-emulator-fixture")
            .put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465") }
        peer = NativeFixturePeer(); listing()
        NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
            MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
        }
    }
    @After fun cleanup() {
        scenario?.close(); NativeLifecycleTestActivity.connector = null
        if (::peer.isInitialized) { peer.close(); store.clear() }
    }
    private fun listing(terminals: String = "[]", surfaces: String = "[]") {
        peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"workspace-1","title":"Delayed workspace",
            "terminals":$terminals,"surfaces":$surfaces}]}""")
    }
    private val shell = """[{"id":"terminal-1","title":"Ready shell"}]"""
    private val panel = """[{"surface_id":"discovered-browser","kind":"project","title":"Mac panel","is_focused":true}]"""
    private fun launch(open: Boolean = true) {
        scenario = ActivityScenario.launch(NativeLifecycleTestActivity::class.java); waitFor("Delayed workspace")
        if (open) compose.onNodeWithText("Delayed workspace").performClick()
    }
    private fun waitFor(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun calls(method: String) = peer.requests.filter { it.optString("method") == method }
    private fun descriptor() = JSONObject().put("panel_id", "discovered-browser").put("workspace_id", "workspace-1")
        .put("title", "Discovered browser").put("page_width", 800).put("page_height", 600)
        .put("can_go_back", false).put("can_go_forward", false).put("is_loading", false)
    private fun discover(gate: CountDownLatch? = null) {
        peer.browserCreationSupported = true
        peer.browserResponse = { method, _ ->
            if (method == "mobile.browser.list") {
                gate?.await(15, TimeUnit.SECONDS); JSONObject().put("panels", JSONArray().put(descriptor()))
            } else descriptor()
        }
    }
    private fun awaitBrowser() {
        compose.waitUntil(15_000) { calls("mobile.browser.stream.start").isNotEmpty() }
        assertEquals("discovered-browser", calls("mobile.browser.stream.start").first().getJSONObject("params").getString("panel_id"))
    }

    @Test fun emptyWorkspaceOpensAndAttachesToALateTerminal() {
        launch(); waitFor("Waiting for workspace panes…")
        assertTrue(calls("mobile.terminal.replay").isEmpty())
        val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(directory, "workspace-waiting.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
        listing(terminals = shell); waitFor("Ready shell ▾")
        compose.waitUntil(5_000) { calls("mobile.terminal.replay").isNotEmpty() }
        compose.onNodeWithTag("WorkspaceWaiting").assertDoesNotExist()
    }
    @Test fun emptyWorkspaceSelectsALateMacPanelWithoutTerminalTraffic() {
        launch(); waitFor("Waiting for workspace panes…"); listing(surfaces = panel); waitFor("Mac panel ▾")
        assertTrue(calls("mobile.terminal.replay").isEmpty())
    }
    @Test fun browserOnlyDiscoveryOpensWithoutAWireSurface() {
        discover(); launch(); awaitBrowser(); assertTrue(calls("mobile.terminal.replay").isEmpty())
    }
    @Test fun discoveryAddsAMenuChoiceWithoutReplacingAReadyTerminal() {
        listing(terminals = shell); discover(); launch(); waitFor("Ready shell ▾")
        compose.onNodeWithText("Ready shell ▾").performClick(); waitFor("Discovered browser")
        assertTrue(calls("mobile.browser.stream.start").isEmpty())
        compose.onNodeWithText("Discovered browser").performClick(); awaitBrowser()
    }
    @Test fun selectedMacPanelUpgradesToItsDiscoveredBrowser() {
        val gate = CountDownLatch(1)
        listing(surfaces = panel); discover(gate)
        try {
            launch(); waitFor("Mac panel ▾")
            compose.waitUntil(5_000) { calls("mobile.browser.list").isNotEmpty() }
            assertTrue(calls("mobile.browser.stream.start").isEmpty()); gate.countDown(); awaitBrowser()
        } finally { gate.countDown() }
    }
    @Test fun explicitTerminalChoiceSurvivesInFlightBrowserDiscovery() {
        val gate = CountDownLatch(1)
        listing(terminals = shell, surfaces = panel); discover(gate)
        try {
            launch(); waitFor("Mac panel ▾")
            compose.waitUntil(5_000) { calls("mobile.browser.list").isNotEmpty() }
            compose.onNodeWithText("Mac panel ▾").performClick(); compose.onNodeWithText("Ready shell").performClick()
            waitFor("Ready shell ▾"); gate.countDown()
            compose.onNodeWithText("Ready shell ▾").performClick(); waitFor("Discovered browser")
            assertTrue(calls("mobile.browser.stream.start").isEmpty())
        } finally { gate.countDown() }
    }
    @Test fun leavingEmptyWorkspaceRejectsLateDiscovery() {
        val gate = CountDownLatch(1)
        discover(gate)
        try {
            launch(); waitFor("Waiting for workspace panes…")
            compose.waitUntil(5_000) { calls("mobile.browser.list").isNotEmpty() }
            compose.onNodeWithContentDescription("Back to workspaces").performClick()
            gate.countDown(); compose.waitForIdle()
            compose.onNodeWithTag("WorkspaceWaiting").assertDoesNotExist()
            assertTrue(calls("mobile.browser.stream.start").isEmpty())
        } finally { gate.countDown() }
    }
    @Test fun waitingWorkspaceCanCreateOneTerminalAndWaitForItsReadiness() {
        peer.terminalCreationResponse = {
            listing(terminals = """[{"id":"new-terminal","title":"Created shell","is_ready":false}]""")
            JSONObject(peer.customWorkspaceListing.toString()).put("created_terminal_id", "new-terminal")
        }
        launch(); waitFor("Waiting for workspace panes…"); compose.onNodeWithText("New terminal").performClick()
        waitFor("Starting terminal…"); assertTrue(calls("mobile.terminal.replay").isEmpty())
        listing(terminals = """[{"id":"new-terminal","title":"Created shell","is_ready":true}]""")
        compose.waitUntil(15_000) { calls("mobile.terminal.replay").any { it.getJSONObject("params").optString("surface_id") == "new-terminal" } }
        assertEquals(1, calls("terminal.create").size)
    }
    @Test fun newlyCreatedEmptyWorkspaceWaitsForItsFirstPane() {
        peer.workspaceCreationResponse = {
            val created = JSONObject("""{"id":"created-workspace","title":"Created workspace","terminals":[]}""")
            val updated = JSONObject(peer.customWorkspaceListing.toString())
            updated.getJSONArray("workspaces").put(created); peer.customWorkspaceListing = updated
            JSONObject().put("created_workspace_id", "created-workspace").put("workspaces", JSONArray().put(created))
        }
        launch(open = false); compose.onNodeWithText("+").performClick(); compose.onNodeWithText("New workspace").performClick()
        waitFor("Waiting for workspace panes…"); waitFor("Created workspace")
        val updated = JSONObject(peer.customWorkspaceListing.toString())
        updated.getJSONArray("workspaces").getJSONObject(1).put("terminals", JSONArray(shell)); peer.customWorkspaceListing = updated
        waitFor("Ready shell ▾")
        assertEquals(1, calls("workspace.create").size)
    }
}
