package io.github.docmorphic.cmuxapp

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Account-clearing synthetic-peer checks: run only on the owned disposable emulator. */
@OptIn(ExperimentalTestApi::class)
class NativeIdentifiedInputTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private val surface = "11111111-2222-3333-4444-555555555555"
    private fun keyboard(view: View): TerminalKeyboardView? = if (view is TerminalKeyboardView) view else
        (view as? ViewGroup)?.let { parent -> (0 until parent.childCount).firstNotNullOfOrNull { keyboard(parent.getChildAt(it)) } }
    private fun typing(scenario: ActivityScenario<NativeLifecycleTestActivity>, text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Compose").fetchSemanticsNodes().isNotEmpty() }
        scenario.onActivity { activity ->
            val view = checkNotNull(keyboard(activity.window.decorView))
            assertTrue(view.isEnabled)
            assertTrue(checkNotNull(view.onCreateInputConnection(EditorInfo())).commitText(text, 1))
        }
    }
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(context.getExternalFilesDir(null), "$name.png")))
    }
    private fun exercise(block: (NativeFixturePeer, ActivityScenario<NativeLifecycleTestActivity>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = NativeCredentialStore(context)
        val peer = NativeFixturePeer().apply {
            identifiedInput = true
            customWorkspaceListing = JSONObject("""{"groups":[],"workspaces":[{"id":"workspace-1","title":"Identified input",
                "terminals":[{"id":"$surface","title":"Shell"}]}]}""")
        }
        try {
            store.clear(); NativeCredentialStore(context, "native_terminal_drafts").clear()
            TerminalDraftRepository.get(context).drafts.clear()
            store.update { it.put("refresh_token", "identified-input-fixture") }
            store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "fixture-mac", "Fixture Mac")
            NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            }
            ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { scenario ->
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Identified input").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Identified input").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Keyboard").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Keyboard").performClick()
                block(peer, scenario)
            }
        } finally {
            NativeLifecycleTestActivity.connector = null; peer.close(); store.clear()
            NativeCredentialStore(context, "native_terminal_drafts").clear(); TerminalDraftRepository.get(context).drafts.clear()
        }
    }
    @Test fun lostReplyAndActivityRecreationKeepOriginalIdentityAndApplyOnce() = exercise { peer, scenario ->
        peer.dropReplyAfterMethod = "terminal.input"
        typing(scenario, "lost acknowledgement")
        compose.waitUntil(10_000) { "terminal.input" in peer.lostReplies }
        scenario.recreate()
        compose.waitUntil(20_000) { peer.requests.count { it.optString("method") == "terminal.input" } >= 2 &&
            compose.onAllNodesWithText("Keyboard").fetchSemanticsNodes().isNotEmpty() }
        val retried = peer.requests.filter { it.optString("method") == "terminal.input" }.map { it.getJSONObject("params") }
        assertEquals(1, retried.map { it.getString("input_stream_id") + ":" + it.getString("input_stream_seq") }.toSet().size)
        assertEquals(1, peer.appliedInputIdentities.size)
        assertTrue(retried.all { it.getString("surface_id") == surface && it.getString("text") == "lost acknowledgement" })
        compose.onNodeWithText("Resume typing").assertDoesNotExist()
        compose.onNodeWithText("Keyboard").performClick()
        typing(scenario, "after recreation")
        compose.waitUntil(10_000) { peer.appliedInputIdentities.size == 2 }
        capture("identified-input-recovered")
    }
    @Test fun busyReplyBudgetShowsPauseAndExplicitResumeUsesNewIdentity() = exercise { peer, scenario ->
        peer.identifiedInputBusy = true
        typing(scenario, "busy original")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Resume typing").fetchSemanticsNodes().isNotEmpty() }
        val attempts = peer.requests.filter { it.optString("method") == "terminal.input" }.map { it.getJSONObject("params") }
        assertEquals(3, attempts.size)
        assertEquals(1, attempts.map { it.getString("input_stream_id") }.toSet().size)
        assertTrue(peer.appliedInputIdentities.isEmpty())
        capture("identified-input-paused")
        peer.identifiedInputBusy = false
        compose.onNodeWithText("Resume typing").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Resume typing").fetchSemanticsNodes().isEmpty() }
        typing(scenario, "explicit new input")
        compose.waitUntil(10_000) { peer.appliedInputIdentities.size == 1 }
        val last = peer.requests.last { it.optString("method") == "terminal.input" }.getJSONObject("params")
        assertNotEquals(attempts.first().getString("input_stream_id"), last.getString("input_stream_id"))
        assertEquals("explicit new input", last.getString("text"))
    }
}
