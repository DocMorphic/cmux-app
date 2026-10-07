package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Main shell, real socket RPC, tab/search state and user actions. Never touches a physical account. */
@OptIn(ExperimentalTestApi::class)
class NativeAgentFeedRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Test fun feedTabSearchQuestionPermissionReplyAndNavigationUseRealRpc() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = NativeCredentialStore(context)
        val preferences = context.getSharedPreferences("cmux-display", android.content.Context.MODE_PRIVATE)
        val peer = NativeFixturePeer()
        val rows = JSONArray("""[
          {"id":"permission","workstream_id":"one","source":"Claude","kind":"permissionRequest","status":"pending","request_id":"req-permission","created_at":200,"updated_at":200,"title":"Permission needed","tool_name":"Read","tool_input":"Inspect README","workspace_id":"workspace-1","surface_id":"terminal-1"},
          {"id":"question","workstream_id":"two","source":"Claude","kind":"question","status":"pending","request_id":"req-question","created_at":199,"updated_at":199,"title":"Pick a color","questions":[{"id":"color","prompt":"Which color?","options":[{"id":"blue-id","label":"Blue"},{"id":"red-id","label":"Red"}]}],"workspace_id":"workspace-1","surface_id":"terminal-1"},
          {"id":"stop","workstream_id":"three","source":"Claude","kind":"stop","status":"telemetry","created_at":198,"updated_at":198,"title":"Finished report","reason":"Report ready","workspace_id":"workspace-1","surface_id":"terminal-1"}
        ]""")
        var revision = 1
        peer.agentFeedResponse = { method, params -> synchronized(rows) {
            when (method) {
                "feed.list" -> JSONObject().put("revision", revision).put("items", JSONArray(rows.toString()))
                "feed.text" -> JSONObject().put("text", "# Agent report\n\nThe task is **complete**.").put("version", 1)
                "mobile.terminal.paste" -> {
                    rows.getJSONObject(2).put("reply_text", params.getString("text")); revision++
                    JSONObject().put("submitted", true)
                }
                else -> {
                    val row = if (method == "feed.permission.reply") rows.getJSONObject(0) else rows.getJSONObject(1)
                    row.put("status", "resolved"); revision++; JSONObject()
                }
            }
        } }
        fun tab(label: String) = compose.onNode(hasText(label, substring = true) and isSelectable())
        fun search(text: String) {
            compose.onNodeWithContentDescription("Search").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement(text)
            compose.onNode(hasSetTextAction()).performImeAction()
        }
        fun awaitMethod(method: String) = compose.waitUntil(15_000) { peer.requests.any { it.optString("method") == method } }
        try {
            preferences.edit().remove(NativeDisplayPreferences.feedReplacesNotificationsKey).commit()
            store.clear(); store.update { it.put("refresh_token", "agent-feed-emulator-fixture").put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465") }
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
                NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                    MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
                })
            } } }
            awaitMethod("feed.list")
            compose.onNode(hasText("Notifications", substring = true) and isSelectable()).assertDoesNotExist()
            tab("Feed").performClick()
            compose.onNodeWithText("asked to use Read").assertIsDisplayed()
            val folder = File(context.getExternalFilesDir(null), "agent-feed").apply { mkdirs() }
            UiDevice.getInstance(instrumentation).takeScreenshot(File(folder, "timeline.png"))
            search("Pick a color")
            compose.onNodeWithTag("AgentFeedQuestionOption:color:blue-id").performScrollTo().performClick()
            // The default hides Notifications. Opt in, then hide it while selected:
            // Feed must return with its query and unanswered choice intact.
            compose.runOnIdle { preferences.edit().putBoolean(NativeDisplayPreferences.feedReplacesNotificationsKey, false).commit() }
            tab("Notifications").performClick()
            compose.onNodeWithTag("AgentFeedQuestionOption:color:blue-id").assertDoesNotExist()
            compose.onNodeWithContentDescription("Search").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("legacy-only query")
            compose.runOnIdle { preferences.edit().putBoolean(NativeDisplayPreferences.feedReplacesNotificationsKey, true).commit() }
            tab("Feed").assertIsSelected()
            compose.onNode(hasText("Notifications", substring = true) and isSelectable()).assertDoesNotExist()
            compose.onNodeWithTag("AgentFeedQuestionOption:color:blue-id").assertIsSelected()
            compose.runOnIdle { preferences.edit().putBoolean(NativeDisplayPreferences.feedReplacesNotificationsKey, false).commit() }
            tab("Feed").assertIsSelected()
            tab("Notifications").performClick()
            compose.onNodeWithContentDescription("Search").performClick()
            compose.onNode(hasSetTextAction()).assertTextContains("legacy-only query")
            compose.onNode(hasSetTextAction()).performImeAction()
            tab("Feed").performClick()
            assertTrue(peer.requests.none { it.optString("method") == "feed.question.reply" })
            compose.onNodeWithText("Send").performClick(); awaitMethod("feed.question.reply")
            assertEquals("Blue", peer.requests.last { it.optString("method") == "feed.question.reply" }
                .getJSONObject("params").getJSONArray("selections").getString(0))
            search("Permission needed")
            compose.onNodeWithText("Allow Once").performClick(); awaitMethod("feed.permission.reply")
            assertEquals("once", peer.requests.last { it.optString("method") == "feed.permission.reply" }.getJSONObject("params").getString("mode"))
            search("Finished report")
            compose.onNodeWithText("Reply", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithTag("AgentFeedComposeDraft").performTextInput("Run the next task")
            compose.onNodeWithTag("AgentFeedComposeSend").performClick(); awaitMethod("mobile.terminal.paste")
            val paste = peer.requests.last { it.optString("method") == "mobile.terminal.paste" }.getJSONObject("params")
            assertEquals("stop", paste.getString("feed_event_id")); assertEquals("terminal-1", paste.getString("surface_id"))
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Run the next task").fetchSemanticsNodes().isNotEmpty() }
            tab("Notifications").performClick()
            compose.onNodeWithText("Report ready").assertDoesNotExist()
            tab("Feed").performClick()
            compose.onNodeWithText("Report ready").assertIsDisplayed()
            compose.onNodeWithTag("AgentFeedRow:stop").performTouchInput { longClick() }
            compose.onNodeWithText("Open tab").performClick(); awaitMethod("mobile.terminal.replay")
            assertEquals("workspace-1", peer.requests.last { it.optString("method") == "mobile.terminal.replay" }.getJSONObject("params").getString("workspace_id"))
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            tab("Feed").assertIsSelected()
            compose.onNodeWithText("Report ready").assertIsDisplayed()
        } finally {
            compose.activity.finish(); peer.close(); store.clear()
            context.getSharedPreferences("native_agent_feed_read", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            preferences.edit().remove(NativeDisplayPreferences.feedReplacesNotificationsKey).commit()
        }
    }
}
