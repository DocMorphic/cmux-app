package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Uses synthetic credentials only on the owned emulator. */
@OptIn(ExperimentalTestApi::class)
class WorkspaceDefaultRoutingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var store: NativeCredentialStore
    private lateinit var peer: NativeFixturePeer
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only account fixture" }
        store = NativeCredentialStore(InstrumentationRegistry.getInstrumentation().targetContext)
        store.clear()
        store.update { it.put("refresh_token", "workspace-selection-emulator-fixture")
            .put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465") }
        peer = NativeFixturePeer()
    }
    @After fun cleanup() {
        if (::peer.isInitialized) { compose.activity.finish(); peer.close(); store.clear() }
    }
    private fun show(terminals: String, surfaces: String = "[]") {
        peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"workspace-1","title":"Selection workspace",
            "terminals":$terminals,"surfaces":$surfaces}]}""")
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        waitFor("Selection workspace"); compose.onNodeWithText("Selection workspace").performClick()
    }
    private fun waitFor(text: String) = compose.waitUntil(15000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    @Test fun readyFocusedTerminalWinsOverEarlierStartingAndReadyTerminals() {
        show("""[{"id":"starting","title":"Starting","is_ready":false,"is_focused":true},
            {"id":"ready","title":"Ready"},{"id":"terminal-1","title":"Focused shell","is_focused":true}]""")
        waitFor("Focused shell ▾")
        compose.waitUntil(5000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" } }
        assertEquals("terminal-1", peer.requests.first { it.optString("method") == "mobile.terminal.replay" }
            .getJSONObject("params").getString("surface_id"))
    }
    @Test fun focusedMacSurfaceWinsAndExplicitTerminalPickerRemainsAuthoritative() {
        show("""[{"id":"terminal-1","title":"Shell"}]""", """[
            {"surface_id":"project","kind":"project","title":"Project panel","is_focused":true}]""")
        waitFor("Project panel ▾")
        assertTrue(peer.requests.none { it.optString("method").startsWith("mobile.terminal.") })
        compose.onNodeWithText("Project panel ▾").performClick(); compose.onNodeWithText("Shell").performClick()
        waitFor("Shell ▾")
        assertTrue(peer.requests.none { it.optString("method") == "mobile.surface.focus" })
    }
    @Test fun terminalLessWorkspaceUsesSpatialOrderInsteadOfAlwaysChoosingBrowser() {
        show("[]", """[{"surface_id":"project","kind":"project","title":"First panel"},
            {"surface_id":"browser","kind":"browser","title":"Later browser"}]""")
        waitFor("First panel ▾")
        assertTrue(peer.requests.none { it.optString("method") == "mobile.browser.stream.start" })
    }
    @Test fun focusedBrowserStartsItsExactStreamInsteadOfTheTerminal() {
        show("""[{"id":"terminal-1","title":"Shell"}]""", """[
            {"surface_id":"browser-first","kind":"browser","title":"First browser"},
            {"surface_id":"browser-focused","kind":"browser","title":"Focused browser","is_focused":true}]""")
        compose.waitUntil(15000) { peer.requests.any { it.optString("method") == "mobile.browser.stream.start" } }
        assertEquals("browser-focused", peer.requests.first { it.optString("method") == "mobile.browser.stream.start" }
            .getJSONObject("params").getString("panel_id"))
        assertTrue(peer.requests.none { it.optString("method").startsWith("mobile.terminal.") })
    }
}
