package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalTestApi::class)
class NativeBrowserTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var peer: NativeFixturePeer
    private lateinit var client: MobileRpcClient
    private var shorter by mutableStateOf(false)
    private var visible by mutableStateOf(true)
    private val panel = "browser-one"
    @Before fun setup() {
        compose.runOnUiThread { compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        peer = NativeFixturePeer()
        client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
        runBlocking { client.connect() }
    }
    @After fun cleanup() { compose.activity.finish(); client.close(); peer.close() }
    private fun show(clock: BrowserRecoveryClock = MonotonicBrowserRecoveryClock) {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Column { Box(if (shorter) Modifier.fillMaxWidth().height(350.dp) else Modifier.fillMaxSize()) {
                if (visible) NativeBrowserView(client, panel, "Browser", onBack = { visible = false }, recoveryClock = clock)
            } }
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Browser fixture").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun requests(method: String) = peer.requests.filter { it.optString("method") == "mobile.browser.$method" }
    private fun pushState(title: String = "Updated page", url: String = "https://cmux.com/docs", editable: Boolean = false) {
        peer.pushBrowserEvent("browser.state", JSONObject().put("panel_id", panel).put("title", title).put("url", url)
            .put("can_go_back", true).put("can_go_forward", false).put("is_loading", true).put("progress", 0.6).put("editable_focused", editable))
        compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun frame(sequence: Int, width: Int = 100): JSONObject {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(20, 90, 130)) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
        return JSONObject().put("panel_id", panel).put("seq", sequence).put("format", "png").put("pixel_width", width).put("pixel_height", 200)
            .put("page_width", 100).put("page_height", 200).put("data_b64", java.util.Base64.getEncoder().encodeToString(bytes))
    }
    private fun keyboardView(): TerminalKeyboardView {
        fun find(view: View): TerminalKeyboardView? {
            if (view is TerminalKeyboardView && view.contentDescription == "Browser keyboard input") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.window.decorView))
    }

    @Test fun macModeReasonsAndFreshAdmissionPreserveCurrentPage() {
        var availability = MacBrowserAvailability.NEEDS_MAC_UPDATE
        var revision by mutableIntStateOf(0)
        var opened: String? = null
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeRemoteBrowserPane(client, NativeBrowser(panel, "Browser"), false, null, {}, {},
                modeRevision = revision, availability = { availability }, onOnDevice = { opened = it })
        } } }
        compose.waitUntil(15000) { requests("stream.start").isNotEmpty() }
        compose.onNodeWithContentDescription("Browser mode").performClick()
        compose.onNodeWithText("On Android").assertIsNotEnabled()
        compose.onNodeWithText("Update cmux on this Mac").assertIsDisplayed()
        compose.onNodeWithText("✓  Streamed").performClick()
        compose.runOnIdle { availability = MacBrowserAvailability.ROUTE_WITHOUT_LANES; revision++ }
        compose.onNodeWithContentDescription("Browser mode").performClick()
        compose.onNodeWithText("On Android").assertIsNotEnabled()
        compose.onNodeWithText("Not available on this connection").assertIsDisplayed()
        compose.onNodeWithText("✓  Streamed").performClick()
        compose.runOnIdle { availability = MacBrowserAvailability.AVAILABLE; revision++ }
        pushState(url = "http://localhost:3000/phone-page")
        compose.onNodeWithContentDescription("Browser mode").performClick()
        compose.onNodeWithText("On Android").assertIsEnabled()
        // Revoke admission without recomposing the visible menu.
        compose.runOnIdle { availability = MacBrowserAvailability.NOT_CONNECTED }
        compose.onNodeWithText("On Android").performClick()
        compose.runOnIdle { assertNull(opened) }
        compose.runOnIdle { availability = MacBrowserAvailability.AVAILABLE; revision++ }
        compose.onNodeWithContentDescription("Browser mode").performClick()
        compose.onNodeWithText("On Android").performClick()
        compose.waitUntil(10000) { opened != null }
        assertEquals("http://localhost:3000/phone-page", opened)
    }

    @Test fun rememberedMacModeRestoresWhileDisconnectedButNotOnLegacyRoute() {
        var availability = MacBrowserAvailability.NEEDS_MAC_UPDATE
        var revision by mutableIntStateOf(0)
        var opened = 0
        val checked = java.util.concurrent.CopyOnWriteArrayList<MacBrowserAvailability>()
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeRemoteBrowserPane(null, NativeBrowser(panel, "Browser"), false, null, {}, {},
                modeRevision = revision, prefersOnDevice = true, availability = { availability.also { checked += it } }, onOnDevice = { opened++ })
        } } }
        compose.waitUntil(10000) { checked.lastOrNull() == MacBrowserAvailability.NEEDS_MAC_UPDATE }
        compose.runOnIdle { assertEquals(0, opened); availability = MacBrowserAvailability.ROUTE_WITHOUT_LANES; revision++ }
        compose.waitUntil(10000) { checked.lastOrNull() == MacBrowserAvailability.ROUTE_WITHOUT_LANES }
        compose.runOnIdle { assertEquals(0, opened); availability = MacBrowserAvailability.NOT_CONNECTED; revision++ }
        compose.waitUntil(10000) { checked.lastOrNull() == MacBrowserAvailability.NOT_CONNECTED }
        compose.runOnIdle { assertEquals("Availability checks: $checked", 1, opened) }
    }

    @Test fun remoteNavigationAddressEditingAndViewportDoNotRestartStream() {
        show()
        compose.onNodeWithContentDescription("Browser Back").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Browser Forward").assertIsNotEnabled()
        pushState()
        compose.onNodeWithContentDescription("Browser Back").assertIsEnabled()
        compose.onNodeWithContentDescription("Browser loading").assertIsDisplayed()
        compose.onNodeWithContentDescription("Browser address").performClick().performTextReplacement("my new search")
        pushState("Another state", "https://cmux.com/changed")
        compose.onNodeWithContentDescription("Browser address").assertTextEquals("my new search")
        compose.onNodeWithContentDescription("Browser address").performImeAction()
        compose.waitUntil(10_000) { requests("navigate").isNotEmpty() }
        assertEquals("my new search", requests("navigate").single().getJSONObject("params").getString("url"))
        peer.pushBrowserEvent("browser.frame", frame(1))
        compose.waitUntil(10_000) { requests("frame.ack").isNotEmpty() }
        val initial = requests("stream.start").single().getJSONObject("params")
        compose.runOnIdle { shorter = true }
        compose.waitUntil(10_000) { requests("viewport").any { it.getJSONObject("params").getInt("viewport_height") < initial.getInt("viewport_height") } }
        assertEquals(1, requests("stream.start").size)
        assertTrue(requests("stream.stop").isEmpty())
        compose.onNodeWithContentDescription("Browser Back").performClick()
        compose.waitUntil(10_000) { requests("back").size == 1 }
    }

    @Test fun keyboardCommitsCompositionAndNativeKeysInOrderWithoutTextSendBar() {
        show(); pushState(editable = true)
        compose.waitUntil(10_000) { keyboardView().hasFocus() }
        compose.onNodeWithText("Type in page").assertDoesNotExist()
        compose.runOnUiThread {
            val view = keyboardView(); val attributes = EditorInfo(); val ime = checkNotNull(view.onCreateInputConnection(attributes))
            assertEquals(EditorInfo.IME_ACTION_GO, attributes.imeOptions and EditorInfo.IME_MASK_ACTION)
            ime.setComposingText("中", 1)
            assertTrue(requests("input.text").isEmpty())
            ime.commitText("中文🙂", 1)
            ime.deleteSurroundingText(1, 0)
            ime.performEditorAction(EditorInfo.IME_ACTION_GO)
            ime.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON))
        }
        compose.waitUntil(10_000) { requests("input.key").size == 3 }
        val inputs = peer.requests.filter { it.optString("method").startsWith("mobile.browser.input.") }
        assertEquals(listOf("mobile.browser.input.text", "mobile.browser.input.key", "mobile.browser.input.key", "mobile.browser.input.key"), inputs.map { it.getString("method") })
        assertEquals("中文🙂", inputs[0].getJSONObject("params").getString("text"))
        assertEquals(listOf("delete", "return", "a"), inputs.drop(1).map { it.getJSONObject("params").getString("key") })
        assertEquals("control", inputs.last().getJSONObject("params").getJSONArray("modifiers").getString(0))
        inputs.forEach { assertEquals(panel, it.getJSONObject("params").getString("panel_id")) }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Hide browser keyboard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Hide browser keyboard").performClick()
        pushState("Still editing on Mac", editable = true)
        compose.runOnIdle { assertFalse(keyboardView().hasFocus()); assertFalse(keyboardView().isEnabled) }
    }

    @Test fun framesRejectRegressionWrongPanelAndWrongDimensionsAndStreamStopsOnExit() {
        show(); peer.pushBrowserEvent("browser.frame", frame(8))
        compose.waitUntil(10_000) { requests("frame.ack").any { it.getJSONObject("params").optInt("seq") == 8 } }
        compose.onNodeWithContentDescription("Mac browser page").assertIsDisplayed()
        peer.pushBrowserEvent("browser.frame", frame(7))
        peer.pushBrowserEvent("browser.frame", frame(9, width = 101))
        peer.pushBrowserEvent("browser.frame", frame(10).put("panel_id", "different-panel"))
        pushState("Frames checked") // Ordered after invalid frames on the same connection.
        assertEquals(listOf(8), requests("frame.ack").map { it.getJSONObject("params").getInt("seq") })
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots/browser-bottom-controls.png")
        output.parentFile!!.mkdirs(); output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        compose.onNodeWithText("‹  Workspaces").performClick()
        compose.waitUntil(10_000) { requests("stream.stop").isNotEmpty() }
        assertEquals(panel, requests("stream.stop").single().getJSONObject("params").getString("panel_id"))
    }

    @Test fun lateDialogReplyCannotDismissItsReplacement() {
        show()
        val release = CountDownLatch(1)
        peer.browserResponse = { method, _ -> if (method == "mobile.browser.dialog.respond") check(release.await(10, TimeUnit.SECONDS)); JSONObject() }
        fun dialog(id: String, label: String) = JSONObject().put("panel_id", panel).put("dialog_id", id).put("title", label).put("message", "Fixture prompt")
            .put("buttons", JSONArray().put(JSONObject().put("id", "ok").put("label", "Accept").put("role", "default")))
        try {
            peer.pushBrowserEvent("browser.dialog", dialog("first", "First prompt"))
            compose.waitUntil(10_000) { compose.onAllNodesWithText("First prompt").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Accept").performClick()
            compose.waitUntil(10_000) { requests("dialog.respond").isNotEmpty() }
            peer.pushBrowserEvent("browser.dialog", dialog("second", "Second prompt"))
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Second prompt").fetchSemanticsNodes().isNotEmpty() }
            release.countDown()
            pushState("Reply received")
            compose.onNodeWithText("Second prompt").assertIsDisplayed()
            compose.onNodeWithText("Accept").assertIsEnabled()
            assertEquals("first", requests("dialog.respond").single().getJSONObject("params").getString("dialog_id"))
        } finally { release.countDown() }
    }

    @Test fun unansweredInputRearmsStreamWithoutReplayAndRejectsOldSubscriptionFrames() {
        val clock = RecoveryClock()
        show(clock)
        val originalStream = peer.requests.single { it.optString("method") == "mobile.events.subscribe" }.getJSONObject("params").getString("stream_id")
        peer.pushBrowserEvent("browser.frame", frame(8))
        compose.waitUntil(10_000) { requests("frame.ack").size == 1 }
        compose.runOnIdle { clock.advance(60_000) }
        assertEquals(1, requests("stream.start").size)
        compose.onNodeWithContentDescription("Reload browser").performClick()
        compose.waitUntil(10_000) { requests("reload").size == 1 && clock.waiting > 0 }
        pushState("State events do not prove frame delivery")
        compose.runOnIdle { clock.advance(2_550) }
        compose.waitUntil(10_000) { requests("stream.start").size == 2 }
        compose.onNodeWithContentDescription("Mac browser page").assertIsDisplayed()
        peer.pushBrowserEvent("browser.frame", frame(99), originalStream)
        peer.pushBrowserEvent("browser.frame", frame(1))
        compose.waitUntil(10_000) { requests("frame.ack").any { it.getJSONObject("params").optInt("seq") == 1 } }
        assertEquals(listOf(8, 1), requests("frame.ack").map { it.getJSONObject("params").getInt("seq") })
        assertEquals(1, requests("reload").size)
        compose.runOnIdle { clock.advance(60_000) }
        assertEquals(2, requests("stream.start").size)
    }

    @Test fun backgroundStopsTheStreamAndForegroundRearmsItWithoutPausingIdleInput() {
        val clock = RecoveryClock()
        show(clock)
        peer.pushBrowserEvent("browser.frame", frame(8))
        compose.waitUntil(10_000) { requests("frame.ack").size == 1 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(10_000) { requests("stream.stop").size == 1 }
        compose.runOnUiThread { clock.advance(60_000) }
        assertEquals(1, requests("stream.start").size)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { requests("stream.start").size == 2 }
        compose.onNodeWithText("Resume browser input").assertDoesNotExist()
        peer.pushBrowserEvent("browser.frame", frame(1))
        compose.waitUntil(10_000) { requests("frame.ack").any { it.getJSONObject("params").optInt("seq") == 1 } }
        compose.onNodeWithContentDescription("Reload browser").assertIsEnabled()
    }

    @Test fun pageFlingUsesNativeScrollRpcAndNeverBecomesAClick() {
        show(RecoveryClock())
        peer.pushBrowserEvent("browser.frame", frame(1))
        compose.waitUntil(10_000) { requests("frame.ack").size == 1 }
        compose.onNodeWithContentDescription("Mac browser page").performTouchInput {
            swipe(Offset(centerX, height * .2f), Offset(centerX, height * .65f), durationMillis = 80)
        }
        compose.waitUntil(15_000) {
            requests("input.scroll").any { it.getJSONObject("params").optString("phase") == "momentum_ended" }
        }
        val scrolls = requests("input.scroll").map { it.getJSONObject("params") }
        assertEquals("began", scrolls.first().getString("phase"))
        assertTrue(scrolls.any { it.getString("phase") == "ended" })
        assertTrue(scrolls.any { it.getString("phase") == "momentum_began" })
        assertTrue(scrolls.any { it.getString("phase").startsWith("momentum_") && it.getDouble("dy") > 0 })
        assertTrue(scrolls.all { it.getString("panel_id") == panel && it.getDouble("y") in 0.0..200.0 })
        assertTrue(requests("input.pointer").isEmpty())
        assertEquals(1, requests("stream.start").size)
    }

    private class RecoveryClock : BrowserRecoveryClock {
        @Volatile private var now = 0L
        private val sleepers = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, CompletableDeferred<Unit>>>()
        val waiting get() = sleepers.count { it.second.isActive }
        override fun nowMillis() = now
        override suspend fun sleep(millis: Long) {
            val signal = CompletableDeferred<Unit>(); val entry = now + millis to signal
            sleepers += entry
            try { signal.await() } finally { sleepers.remove(entry); signal.cancel() }
        }
        fun advance(millis: Long) { now += millis; sleepers.filter { it.first <= now }.forEach { it.second.complete(Unit) } }
    }
}
