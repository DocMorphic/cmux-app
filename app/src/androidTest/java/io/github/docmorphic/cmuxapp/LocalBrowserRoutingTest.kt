package io.github.docmorphic.cmuxapp

import android.os.Build
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

/** Account fixtures are restricted to the owned emulator; never run on the user's Pixel. */
@OptIn(ExperimentalTestApi::class)
class LocalBrowserRoutingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var peer: NativeFixturePeer
    private lateinit var store: NativeCredentialStore
    private val pages = MockWebServer()
    @Before fun setup() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only account fixture" }
        store = NativeCredentialStore(context); store.clear()
        store.update { it.put("refresh_token", "local-browser-emulator-fixture").put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465") }
        peer = NativeFixturePeer()
        peer.customWorkspaceListing = JSONObject("""{"workspaces":[
            {"id":"workspace-1","title":"Browser workspace","terminals":[{"id":"terminal-1","title":"Shell"}]},
            {"id":"workspace-2","title":"Other workspace","terminals":[{"id":"terminal-2","title":"Other shell"}]}]}""")
        pages.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse()
                .setHeader("Content-Type", "text/html").setBody("<title>Local fixture page</title><body style='background:#00ff00'>Workspace browser page</body>")
        }
        pages.start()
    }
    @After fun cleanup() {
        if (::store.isInitialized) { compose.activity.finish(); peer.close(); pages.shutdown(); store.clear() }
    }
    private fun show() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        waitFor(hasText("Browser workspace"))
    }
    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntil(15000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    private fun newFromRow(title: String = "Browser workspace") {
        compose.onNodeWithContentDescription("Actions for $title").performClick()
        compose.onNodeWithText("New browser").performClick()
    }
    private fun browseFixture() {
        waitFor(hasTestTag("LocalBrowserAddress"))
        compose.onNodeWithTag("LocalBrowserAddress").performTextReplacement(pages.url("/workspace").toString())
        compose.onNodeWithTag("LocalBrowserAddress").performImeAction()
        waitFor(hasText("Local fixture page ▾"))
        val bounds = compose.onNodeWithTag("LocalBrowserPage").fetchSemanticsNode().boundsInWindow
        var accepted: Bitmap? = null
        compose.waitUntil(5000) {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val pixel = bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt())
            if (Color.green(pixel) > 220 && Color.red(pixel) < 30 && Color.blue(pixel) < 30) {
                accepted = bitmap; true
            } else { bitmap.recycle(); false }
        }
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "local-browser-workspace.png").outputStream().use { accepted!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        accepted!!.recycle()
    }

    @Test fun terminalMenuFallbackBackRestoreAndCloseReturnToTheTerminal() {
        show(); compose.onNodeWithText("Browser workspace").performClick(); waitFor(hasText("Shell ▾"))
        compose.onNodeWithText("Shell ▾").performClick(); compose.onNodeWithText("New Browser").performClick()
        browseFixture()
        assertTrue(peer.requests.none { it.optString("method") == "mobile.browser.create" })
        compose.onNodeWithContentDescription("Back to workspaces").performClick(); waitFor(hasText("Browser workspace"))
        compose.onNodeWithText("Browser workspace").performClick(); waitFor(hasText("Local fixture page ▾"))
        compose.onNodeWithTag("LocalBrowserAddress").assertTextEquals(pages.url("/workspace").toString())
        compose.onNodeWithContentDescription("Close Browser").performClick(); waitFor(hasText("Shell ▾"))
        compose.onNodeWithTag("LocalBrowserPane").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to workspaces").performClick(); waitFor(hasText("Browser workspace"))
        compose.onNodeWithText("Browser workspace").performClick(); waitFor(hasText("Shell ▾"))
    }

    @Test fun malformedRemoteCreationFallsBackOnceAndSurfacePickerClosesLocalState() {
        peer.browserCreationSupported = true
        show(); newFromRow(); browseFixture()
        assertEquals(1, peer.requests.count { it.optString("method") == "mobile.browser.create" })
        compose.onNodeWithText("Local fixture page ▾").performClick(); compose.onNodeWithText("Shell").performClick()
        waitFor(hasText("Shell ▾")); compose.onNodeWithTag("LocalBrowserPane").assertDoesNotExist()
    }

    @Test fun localPickerCreatesTerminalInItsOwningWorkspace() {
        peer.terminalCreationResponse = { params ->
            assertEquals("workspace-1", params.getString("workspace_id"))
            val listing = JSONObject(peer.customWorkspaceListing.toString())
            listing.getJSONArray("workspaces").getJSONObject(0).getJSONArray("terminals")
                .put(JSONObject().put("id", "created-shell").put("title", "Created shell"))
            peer.customWorkspaceListing = listing
            JSONObject(listing.toString()).put("created_terminal_id", "created-shell")
        }
        show(); newFromRow(); browseFixture()
        compose.onNodeWithTag("terminal-picker").performClick()
        compose.onNodeWithTag("terminal-picker-new-browser").assertIsSelected()
        compose.onNodeWithText("Terminals").assertExists()
        compose.onNodeWithText("New Terminal").performScrollTo().performClick()
        waitFor(hasText("Created shell ▾"))
        assertEquals(1, peer.requests.count { it.optString("method") == "terminal.create" })
        compose.onNodeWithTag("LocalBrowserPane").assertDoesNotExist()
    }

    @Test fun localPickerCanCreateWorkspaceFromAnOtherwiseEmptyWorkspace() {
        peer.customWorkspaceListing!!.getJSONArray("workspaces").getJSONObject(0).put("terminals", JSONArray())
        show(); newFromRow(); browseFixture()
        compose.onNodeWithTag("terminal-picker").performClick()
        compose.onNodeWithText("New Workspace").performScrollTo().performClick()
        waitFor(hasText("Agent ▾"))
        assertEquals(1, peer.requests.count { it.optString("method") == "workspace.create" })
        compose.onNodeWithTag("LocalBrowserPane").assertDoesNotExist()
    }

    @Test fun workspaceWithNoMacPanesCanReopenItsLocalBrowser() {
        peer.customWorkspaceListing!!.getJSONArray("workspaces").getJSONObject(0).put("terminals", JSONArray())
        show(); newFromRow(); browseFixture()
        compose.onNodeWithContentDescription("Back to workspaces").performClick(); waitFor(hasText("Browser workspace"))
        compose.onNodeWithText("Browser workspace").performClick(); waitFor(hasText("Local fixture page ▾"))
        compose.onNodeWithTag("LocalBrowserAddress").assertTextEquals(pages.url("/workspace").toString())
    }

    @Test fun successfulCreationRoutesTheReturnedPanelInTheRequestedWorkspace() {
        peer.browserCreationSupported = true
        peer.browserResponse = { method, params ->
            if (method == "mobile.browser.create") {
                assertEquals("workspace-2", params.getString("workspace_id"))
                peer.customWorkspaceListing!!.getJSONArray("workspaces").getJSONObject(1).put("surfaces", JSONArray().put(
                    JSONObject().put("surface_id", "created-panel").put("kind", "browser").put("title", "Remote browser")))
            }
            JSONObject().put("panel_id", "created-panel").put("workspace_id", "workspace-2")
                .put("url", "https://example.test").put("title", "Remote browser")
        }
        show(); newFromRow("Other workspace")
        compose.waitUntil(15000) { peer.requests.any { it.optString("method") == "mobile.browser.stream.start" } }
        assertEquals(1, peer.requests.count { it.optString("method") == "mobile.browser.create" })
        assertEquals("created-panel", peer.requests.first { it.optString("method") == "mobile.browser.stream.start" }.getJSONObject("params").getString("panel_id"))
        compose.onNodeWithTag("LocalBrowserPane").assertDoesNotExist()
    }

    @Test fun confirmedWorkspaceRemovalPreventsLateCreationFromOpeningAFallback() {
        peer.browserCreationSupported = true
        val response = CountDownLatch(1)
        peer.browserResponse = { _, _ -> response.await(15, TimeUnit.SECONDS); JSONObject() }
        try {
            show(); newFromRow(); waitFor(hasText("Opening browser…"))
            compose.waitUntil(5000) { peer.requests.any { it.optString("method") == "mobile.browser.create" } }
            peer.customWorkspaceListing = JSONObject().put("workspaces", JSONArray().put(
                JSONObject().put("id", "workspace-2").put("title", "Other workspace")
                    .put("terminals", JSONArray().put(JSONObject().put("id", "terminal-2").put("title", "Other shell")))))
            response.countDown()
            compose.waitUntil(15000) {
                compose.onAllNodesWithText("Opening browser…").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText("Browser workspace").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("LocalBrowserPane").assertDoesNotExist()
            compose.onNodeWithText("Other workspace").performClick(); waitFor(hasText("Other shell ▾"))
            assertTrue(peer.requests.none { it.optString("method") == "mobile.browser.stream.start" })
            assertEquals(1, peer.requests.count { it.optString("method") == "mobile.browser.create" })
        } finally { response.countDown() }
    }

    @Test fun cancelledCreationDoesNotOpenOverANewerTerminal() {
        peer.browserCreationSupported = true
        val response = CountDownLatch(1)
        peer.browserResponse = { _, _ -> response.await(15, TimeUnit.SECONDS); JSONObject().put("panel_id", "late-panel") }
        try {
            show(); newFromRow(); waitFor(hasText("Opening browser…"))
            compose.waitUntil(5000) { peer.requests.any { it.optString("method") == "mobile.browser.create" } }
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Other workspace").performClick()
            response.countDown(); waitFor(hasText("Other shell ▾"))
            compose.onNodeWithTag("LocalBrowserPane").assertDoesNotExist()
            assertTrue(peer.requests.none { it.optString("method") == "mobile.browser.stream.start" })
            assertEquals(1, peer.requests.count { it.optString("method") == "mobile.browser.create" })
        } finally { response.countDown() }
    }
}
