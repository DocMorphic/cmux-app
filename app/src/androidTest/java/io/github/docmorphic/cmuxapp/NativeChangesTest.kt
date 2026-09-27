package io.github.docmorphic.cmuxapp

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class NativeChangesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var peer: NativeFixturePeer
    private lateinit var client: MobileRpcClient
    private var visible by mutableStateOf(true)
    @Before fun setup() {
        compose.activity.getSharedPreferences("cmux-display", Context.MODE_PRIVATE).edit().remove("diff-font-size").commit()
        peer = NativeFixturePeer()
        client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
        runBlocking { client.connect() }
    }
    @After fun cleanup() { compose.activity.finish(); client.close(); peer.close() }
    private fun files(vararg paths: String) = JSONObject().put("workspace_id", "ws").put("repo_root", "/fixture/repo")
        .put("branch", "feature").put("base_ref", "main").put("files_changed", paths.size).put("additions", paths.size)
        .put("files", JSONArray(paths.map { JSONObject().put("path", it).put("status", "modified").put("additions", 1).put("deletions", 1) }))
    private fun diff(path: String, extra: Boolean = false, truncated: Boolean = false): JSONObject {
        val number = if (path.endsWith("App.kt")) 3 else 1
        return JSONObject().put("path", path).put("unified_diff", "@@ -$number +$number @@\n-old ${path.substringAfterLast('/')}\n+new ${path.substringAfterLast('/')}\n" +
            if (extra) "+extra line\n" else "").put("truncated", truncated).put("diff_total_lines", 100_000)
    }
    private fun show() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().safeDrawingPadding()) { if (visible) NativeChangesView(client, "ws", "Fixture repo", { visible = false }) } } } }
    }
    private fun waitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun requests() = peer.requests.filter { it.optString("method") == "mobile.workspace.changes.file_diff" }

    @Test fun collapsedTreeOpensExactFileAndPagerKeepsCopyActionsAndCache() {
        peer.changesResponse = { method, params -> if (method.endsWith(".files")) files("README.md", "src/ui/App.kt") else diff(params.getString("path")) }
        show(); waitText("App.kt")
        compose.onNodeWithContentDescription("Collapse folder src/ui").performClick()
        compose.onNodeWithText("App.kt").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand folder src/ui").performClick()
        compose.onNodeWithContentDescription("Open diff src/ui/App.kt").performClick()
        waitText("new App.kt")
        compose.onNodeWithText("2 of 2").assertIsDisplayed()
        compose.onNodeWithText("@@ -3 +3 @@").performTouchInput { longClick(durationMillis = 650) }
        compose.onNodeWithText("Copy Hunk").performClick()
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("@@ -3 +3 @@\n-old App.kt\n+new App.kt", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithContentDescription("Previous changed file").performClick()
        waitText("1 of 2"); compose.onNodeWithText("new README.md").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next changed file").performClick()
        waitText("2 of 2"); compose.onNodeWithText("new App.kt").assertIsDisplayed()
        assertEquals(1, requests().count { it.getJSONObject("params").getString("path") == "src/ui/App.kt" })
    }

    @Test fun notRepositoryAndDiffFailureCanRecoverWithoutClosingChanges() {
        peer.changesResponse = { method, params -> if (method.endsWith(".files")) files("a.txt") else diff(params.getString("path")) }
        peer.changesErrorCode = "not_a_repo"
        show(); waitText("Not a Git repository")
        peer.changesErrorCode = null
        compose.onNodeWithContentDescription("Refresh changes").performClick(); waitText("a.txt")
        peer.changesErrorCode = "temporarily_unavailable"
        compose.onNodeWithContentDescription("Open diff a.txt").performClick(); waitText("Couldn't load diff")
        peer.changesErrorCode = null
        compose.onNodeWithText("Retry").performClick(); waitText("new a.txt")
        compose.onNodeWithText("Couldn't load diff").assertDoesNotExist()
    }

    @Test fun continuationUsesFullBudgetsAndPinchFontSurvivesReopening() {
        peer.changesResponse = { method, params -> if (method.endsWith(".files")) files("big.txt") else
            diff(params.getString("path"), extra = params.optInt("max_lines", 6000) > 6000, truncated = true) }
        show(); waitText("big.txt")
        compose.onNodeWithContentDescription("Open diff big.txt").performClick(); waitText("new big.txt")
        compose.onNodeWithContentDescription("Diff big.txt").performTouchInput {
            pinch(start0 = Offset(centerX - width * .1f, centerY), end0 = Offset(centerX - width * .3f, centerY),
                start1 = Offset(centerX + width * .1f, centerY), end1 = Offset(centerX + width * .3f, centerY), durationMillis = 240)
        }
        compose.onNodeWithContentDescription("Diff text size 22").assertExists()
        compose.onNodeWithText("Show more lines").performScrollTo().performClick()
        waitText("extra line")
        compose.onNodeWithText("Show more lines").performScrollTo().performClick()
        waitText("See the remaining diff on your Mac.")
        assertEquals(listOf(null, 24000, 96000), requests().map { val params = it.getJSONObject("params"); if (params.has("max_lines")) params.getInt("max_lines") else null })
        compose.runOnIdle { visible = false }; compose.waitForIdle()
        compose.runOnIdle { visible = true }; waitText("big.txt")
        compose.onNodeWithContentDescription("Open diff big.txt").performClick(); waitText("new big.txt")
        compose.onNodeWithContentDescription("Diff text size 22").assertExists()
    }
    @Test fun hiddenContextUsesMatchingRevisionAndReusesCurrentFileContent() {
        val content = "first unchanged\nsecond unchanged\nnew App.kt\nlast unchanged\n"
        val fingerprint = "stat:${content.toByteArray().size}:123:4:5:6"
        peer.changesResponse = { method, params -> when {
            method.endsWith(".files") -> files("App.kt")
            method.endsWith(".file_diff") -> diff(params.getString("path")).put("content_fingerprint", fingerprint)
            method.endsWith(".file_stat") -> JSONObject().put("exists", true).put("is_directory", false)
                .put("size", content.toByteArray().size).put("kind", "text").put("content_fingerprint", fingerprint)
            else -> JSONObject().put("data_b64", java.util.Base64.getEncoder().encodeToString(content.toByteArray()))
                .put("offset", 0).put("total_size", content.toByteArray().size).put("eof", true).put("content_fingerprint", fingerprint)
        } }
        show(); waitText("App.kt")
        compose.onNodeWithContentDescription("Open diff App.kt").performClick(); waitText("new App.kt")
        compose.onNodeWithContentDescription("Expand 2 lines above").performClick(); waitText("first unchanged")
        compose.onNodeWithText("second unchanged").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand 1 lines below").performScrollTo().performClick(); waitText("last unchanged")
        assertEquals(1, peer.requests.count { it.optString("method").endsWith(".file_fetch") })
        val contentRequests = peer.requests.filter { it.optString("method").endsWith(".file_fetch") || it.optString("method").endsWith(".file_stat") }
        assertTrue(contentRequests.all { it.getJSONObject("params").let { p -> p.getString("path") == "App.kt" && p.getString("revision") == "current" && p.getString("workspace_id") == "ws" } })
        val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        compose.activity.openFileOutput("changes-diff-expanded.png", Context.MODE_PRIVATE).use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        compose.onNodeWithContentDescription("Refresh diff App.kt").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Expand 2 lines above").fetchSemanticsNodes().isNotEmpty() }
        waitText("new App.kt")
        compose.onNodeWithText("first unchanged").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand 2 lines above").assertExists()
    }

    @Test fun changedRevisionReloadsDiffInsteadOfShowingMismatchedContext() {
        val latest = java.util.concurrent.atomic.AtomicBoolean(false)
        val content = "wrong revision\nwrong revision\nnew App.kt\n"
        val firstFingerprint = "stat:${content.length}:123:4:5:6"
        val changedFingerprint = "stat:${content.length}:124:4:5:6"
        peer.changesResponse = { method, params -> when {
            method.endsWith(".files") -> files("App.kt")
            method.endsWith(".file_diff") -> diff(params.getString("path")).put("content_fingerprint", if (latest.get()) changedFingerprint else firstFingerprint)
            method.endsWith(".file_stat") -> {
                latest.set(true)
                JSONObject().put("exists", true).put("is_directory", false).put("size", content.length)
                    .put("kind", "text").put("content_fingerprint", changedFingerprint)
            }
            else -> JSONObject().put("data_b64", java.util.Base64.getEncoder().encodeToString(content.toByteArray()))
                .put("offset", 0).put("total_size", content.length).put("eof", true).put("content_fingerprint", changedFingerprint)
        } }
        show(); waitText("App.kt")
        compose.onNodeWithContentDescription("Open diff App.kt").performClick(); waitText("new App.kt")
        compose.onNodeWithContentDescription("Expand 2 lines above").performClick()
        compose.waitUntil(10_000) { requests().size == 2 }
        waitText("new App.kt")
        compose.onNodeWithText("wrong revision").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand 2 lines above").assertExists()
    }

}
