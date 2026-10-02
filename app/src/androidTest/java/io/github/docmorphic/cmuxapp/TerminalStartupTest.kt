package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalTestApi::class)
class TerminalStartupTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private lateinit var store: NativeCredentialStore
    private lateinit var peer: NativeFixturePeer
    private var scenario: ActivityScenario<NativeLifecycleTestActivity>? = null
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        store = NativeCredentialStore(InstrumentationRegistry.getInstrumentation().targetContext); store.clear()
        store.update { it.put("refresh_token", "startup-emulator-fixture").put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465") }
        peer = NativeFixturePeer(); listing(created = false)
        peer.terminalCreationResponse = { params ->
            assertEquals("workspace-1", params.getString("workspace_id"))
            listing()
            JSONObject(peer.customWorkspaceListing.toString()).put("created_terminal_id", "new-terminal")
        }
        NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
            MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
        }
    }
    @After fun cleanup() {
        scenario?.close(); NativeLifecycleTestActivity.connector = null
        if (::peer.isInitialized) { peer.close(); store.clear() }
    }
    private fun listing(created: Boolean = true, ready: Boolean = false) {
        peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"workspace-1","title":"Startup workspace","terminals":[
            {"id":"terminal-1","title":"Ready shell","is_focused":true}${if (created) ",{\"id\":\"new-terminal\",\"title\":\"New shell\",\"is_ready\":$ready}" else ""}]},
            {"id":"workspace-2","title":"Other workspace","terminals":[{"id":"terminal-2","title":"Other shell"}]}]}""")
    }
    private fun launch() { scenario = ActivityScenario.launch(NativeLifecycleTestActivity::class.java); waitFor("Startup workspace") }
    private fun waitFor(text: String, timeout: Long = 15_000) = compose.waitUntil(timeout) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun create() {
        compose.onNodeWithContentDescription("Actions for Startup workspace").performClick()
        compose.onNodeWithText("New terminal").performClick()
    }
    private fun terminalCalls(id: String) = peer.requests.filter { it.optString("method").startsWith("mobile.terminal.") &&
        it.optJSONObject("params")?.optString("surface_id") == id }
    private fun assertCreatedOnce() = assertEquals(1, peer.requests.count { it.optString("method") == "terminal.create" })
    private fun assertOnlyPreparation(id: String) {
        val calls = terminalCalls(id)
        assertTrue("An unready terminal must never receive input or viewport mutations", calls.all { call ->
            val params = call.getJSONObject("params")
            call.getString("method") == "mobile.terminal.replay" && !params.has("client_id") &&
                !params.has("viewport_columns") && !params.has("viewport_rows") &&
                params.getString("surface_id") == id && params.getInt("max_scrollback_rows") == 0
        })
    }

    @Test fun createdTerminalPreparesLazySurfaceBeforeAttachingAndKeepsInputDisabled() {
        launch(); create(); waitFor("Starting terminal…")
        compose.onNodeWithText("New shell ▾").assertExists()
        compose.onNodeWithText("Keyboard").assertIsNotEnabled()
        compose.onNodeWithTag("native-terminal").assertDoesNotExist()
        compose.waitUntil(10_000) { terminalCalls("new-terminal").isNotEmpty() }
        assertEquals(1, terminalCalls("new-terminal").size)
        assertEquals("workspace-1", terminalCalls("new-terminal").single().getJSONObject("params").getString("workspace_id"))
        assertOnlyPreparation("new-terminal")
        val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(directory, "terminal-starting.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
        listing(ready = true)
        compose.waitUntil(15_000) { terminalCalls("new-terminal").any {
            it.optString("method") == "mobile.terminal.replay" && it.getJSONObject("params").has("viewport_columns")
        } }
        compose.onNodeWithTag("native-terminal").assertExists(); compose.onNodeWithText("New shell ▾").assertExists()
        assertCreatedOnce()
    }
    @Test fun explicitSiblingSelectionCancelsThePinAndLateReadinessCannotStealFocus() {
        launch(); create(); waitFor("Starting terminal…")
        compose.onNodeWithText("Ready shell", substring = false).performClick(); waitFor("Ready shell ▾")
        listing(ready = true)
        compose.onNodeWithContentDescription("Back to workspaces").performClick(); waitFor("Startup workspace")
        compose.onNodeWithText("Startup workspace").performClick(); waitFor("Ready shell ▾")
        assertOnlyPreparation("new-terminal"); assertCreatedOnce()
    }
    @Test fun timeoutFallsBackAndLateReadinessClearsBannerWithoutSwitchingBack() {
        launch(); create(); waitFor("Starting terminal…")
        waitFor(NativeTerminalStartup.TIMEOUT_MESSAGE, 40_000); waitFor("Ready shell ▾")
        assertOnlyPreparation("new-terminal"); assertCreatedOnce()
        listing(ready = true)
        compose.waitUntil(15_000) { compose.onAllNodesWithText(NativeTerminalStartup.TIMEOUT_MESSAGE).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Ready shell ▾").assertExists(); assertCreatedOnce()
    }
    @Test fun retryIsOneExplicitCreateAndDoesNotRepeatTheTimedOutMutation() {
        launch(); create(); waitFor("Starting terminal…")
        scenario!!.onActivity { activity ->
            val startup = androidx.lifecycle.ViewModelProvider(activity)[NativeFeedSession::class.java].terminalStartup
            val pending = startup.state.value.pending!!
            startup.begin(pending.key, NativeTerminal(pending.terminalId, "New shell", isReady = false),
                android.os.SystemClock.elapsedRealtime() - NativeTerminalStartup.TIMEOUT)
        }
        waitFor(NativeTerminalStartup.TIMEOUT_MESSAGE); assertCreatedOnce()
        peer.terminalCreationResponse = {
            val updated = JSONObject(peer.customWorkspaceListing.toString())
            updated.getJSONArray("workspaces").getJSONObject(0).getJSONArray("terminals").put(
                JSONObject().put("id", "retry-terminal").put("title", "Retry shell").put("is_ready", true))
            peer.customWorkspaceListing = updated
            JSONObject(updated.toString()).put("created_terminal_id", "retry-terminal")
        }
        compose.onNodeWithTag("MobileTerminalCreationRetry").performClick(); waitFor("Retry shell ▾")
        compose.onNodeWithTag("MobileTerminalCreationRecovery").assertDoesNotExist()
        assertEquals(2, peer.requests.count { it.optString("method") == "terminal.create" })
        assertOnlyPreparation("new-terminal")
    }
    @Test fun confirmedDisappearanceReturnsToReadySibling() {
        launch(); create(); waitFor("Starting terminal…")
        listing(created = false); waitFor("Ready shell ▾")
        compose.onNodeWithText(NativeTerminalStartup.TIMEOUT_MESSAGE).assertDoesNotExist()
        assertOnlyPreparation("new-terminal"); assertCreatedOnce()
    }
    @Test fun delayedCreateResponseDoesNotInterruptAnotherWorkspace() {
        val gate = CountDownLatch(1)
        peer.terminalCreationResponse = { gate.await(15, TimeUnit.SECONDS); listing(); JSONObject(peer.customWorkspaceListing.toString()).put("created_terminal_id", "new-terminal") }
        try {
            launch(); create()
            compose.waitUntil(5_000) { peer.requests.any { it.optString("method") == "terminal.create" } }
            compose.onNodeWithText("Other workspace").performClick(); waitFor("Other shell ▾")
            gate.countDown()
            // Returning and opening the first row also proves the mutation completed and its feed refreshed.
            compose.onNodeWithContentDescription("Back to workspaces").performClick(); waitFor("Startup workspace")
            compose.onNodeWithText("Other workspace").performClick(); waitFor("Other shell ▾")
            assertTrue(terminalCalls("new-terminal").isEmpty()); assertCreatedOnce()
        } finally { gate.countDown() }
    }
    @Test fun newWorkspaceUsesStartupPinAndPreservesUnrelatedRowsFromPartialResponse() {
        peer.workspaceCreationResponse = {
            val created = JSONObject("""{"id":"created-workspace","title":"Created workspace","terminals":[
                {"id":"new-terminal","title":"New shell","is_ready":false},{"id":"ready-created","title":"Ready shell","is_ready":true}]}""")
            val listing = JSONObject(peer.customWorkspaceListing.toString())
            listing.getJSONArray("workspaces").put(created); peer.customWorkspaceListing = listing
            JSONObject().put("created_workspace_id", "created-workspace").put("created_terminal_id", "new-terminal")
                .put("workspaces", org.json.JSONArray().put(created))
        }
        launch(); compose.onNodeWithText("+").performClick(); compose.onNodeWithText("New workspace").performClick()
        waitFor("Starting terminal…"); compose.onNodeWithText("New shell ▾").assertExists()
        assertOnlyPreparation("new-terminal")
        compose.onNodeWithContentDescription("Back to workspaces").performClick()
        waitFor("Startup workspace"); waitFor("Other workspace"); waitFor("Created workspace")
        assertEquals(1, peer.requests.count { it.optString("method") == "workspace.create" })
    }
}
