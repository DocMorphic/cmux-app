package io.github.docmorphic.cmuxapp

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.Base64

@OptIn(ExperimentalTestApi::class)
class NativeArtifactFilesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var peer: NativeFixturePeer
    private lateinit var client: MobileRpcClient
    private lateinit var rpc: ArtifactRpc
    private var visible by mutableStateOf(true)
    private val terminal = ArtifactAuthorization.Terminal("workspace", "surface")
    @Before fun setup() {
        compose.activity.getSharedPreferences("cmux-display", Context.MODE_PRIVATE).edit().remove("show-missing-files").commit()
        peer = NativeFixturePeer(); client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
        runBlocking { client.connect() }
        rpc = ArtifactRpc(client, setOf("terminal.artifact.v1", "chat.artifact.gallery.v1", "chat.artifact.folders.v1", "terminal.artifact.list.v1"))
    }
    @After fun cleanup() { compose.activity.finish(); client.close(); peer.close() }
    private fun item(path: String, kind: String = "text") = JSONObject().put("path", path).put("kind", kind).put("size", 10)
    private fun scan(session: Boolean = true) = JSONObject().put("session_id", if (session) "session" else JSONObject.NULL)
        .put("artifacts", JSONArray().put(item("/visible.txt")))
    private fun gallery(vararg items: JSONObject) = JSONObject().put("session_id", "session").put("generation", "g1")
        .put("referenced", JSONArray(items.toList())).put("referenced_total", items.size)
    private fun stat(text: String) = JSONObject().put("exists", true).put("is_directory", false).put("kind", "text")
        .put("size", text.toByteArray().size).put("mime_type", "text/plain")
    private fun chunk(text: String) = JSONObject().put("offset", 0).put("total_size", text.toByteArray().size)
        .put("data_b64", Base64.getEncoder().encodeToString(text.toByteArray())).put("eof", true)
    private fun show() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            if (visible) ArtifactFilesSheet(rpc, terminal) { visible = false }
        } } }
    }
    private fun waitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun waitDescription(value: String) = compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(value).fetchSemanticsNodes().isNotEmpty() }

    @Test fun scopesFiltersGridAndMissingPreferenceUseWholeSessionGallery() {
        peer.artifactResponse = { method, _ ->
            if (method.endsWith("scan")) scan() else gallery(item("/README.md"), item("/main.py"), item("/output.log"), item("/folder", "directory"), item("/gone.txt").put("exists", false))
        }
        show(); waitDescription("Open file /main.py")
        compose.onNodeWithContentDescription("Open file /gone.txt").assertDoesNotExist()
        compose.onNodeWithText("Code", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Open file /main.py").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open file /README.md").assertDoesNotExist()
        compose.onNodeWithText("All", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Icons").performClick()
        compose.onNodeWithContentDescription("List").assertIsDisplayed()
        compose.onNodeWithText("Show missing").performClick()
        waitDescription("Open file /gone.txt")
        compose.onNodeWithText("In view").performClick()
        waitDescription("Open file /visible.txt")
        compose.onNodeWithContentDescription("Open file /main.py").assertDoesNotExist()
        compose.onNodeWithText("Session").performClick(); waitDescription("Open file /main.py")
        assertTrue(peer.requests.any { it.getString("method") == "mobile.chat.artifact.gallery" && it.getJSONObject("params").getInt("page_size") == 60 })
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        compose.activity.openFileOutput("artifact-files-grid.png", Context.MODE_PRIVATE).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }
    @Test fun remoteSearchCanFindFilesAbsentFromLoadedPageAndCopyExactPath() {
        peer.artifactResponse = { method, params ->
            if (method.endsWith("scan")) scan() else if (params.optString("query") == "archive") gallery(item("/history/archive.txt")) else gallery(item("/current.txt"))
        }
        show(); waitDescription("Open file /current.txt")
        compose.onNode(hasSetTextAction()).performTextInput("archive")
        waitDescription("Open file /history/archive.txt")
        compose.onNodeWithContentDescription("Open file /current.txt").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open file /history/archive.txt").performTouchInput { longClick(durationMillis = 650) }
        compose.onNodeWithText("Copy path").performClick()
        compose.runOnUiThread {
            assertEquals("/history/archive.txt", compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithText("Clear").performClick(); waitDescription("Open file /current.txt")
        assertTrue(peer.requests.any { it.optString("method") == "mobile.chat.artifact.gallery" && it.getJSONObject("params").optString("query") == "archive" })
    }
    @Test fun nestedFolderAndPreviewKeepSessionAuthorizationAndBackStack() {
        val text = "Preview from a nested folder"
        peer.artifactResponse = { method, params -> when {
            method.endsWith("scan") -> scan()
            method.endsWith("gallery") -> gallery(item("/folder", "directory"))
            method.endsWith("list") -> JSONObject().put("entries", JSONArray().put(JSONObject()
                .put("name", if (params.getString("path") == "/folder") "nested" else "note.txt")
                .put("is_directory", params.getString("path") == "/folder").put("kind", "text").put("size", text.length)))
            method.endsWith("stat") -> stat(text)
            else -> chunk(text)
        } }
        show(); waitDescription("Open folder /folder")
        compose.onNodeWithContentDescription("Open folder /folder").performClick(); waitDescription("Open folder /folder/nested")
        compose.onNodeWithContentDescription("Open folder /folder/nested").performClick(); waitDescription("Open file /folder/nested/note.txt")
        compose.onNodeWithContentDescription("Open file /folder/nested/note.txt").performClick(); waitText(text)
        compose.onNodeWithText("‹ Back").performClick(); waitDescription("Open file /folder/nested/note.txt")
        compose.onNodeWithText("‹ Back").performClick(); waitDescription("Open folder /folder/nested")
        val reads = peer.requests.filter { it.optString("method").let { method -> method.endsWith(".list") || method.endsWith(".stat") || method.endsWith(".fetch") } }
        assertTrue(reads.isNotEmpty())
        reads.forEach { assertTrue(it.getString("method").startsWith("mobile.chat.artifact.")); assertEquals("session", it.getJSONObject("params").getString("session_id")) }
    }
    @Test fun terminalOnlyPreviewRetriesCorruptTransferAndClosesCompletely() {
        val text = "Terminal scoped preview"; var corrupted = true
        peer.artifactResponse = { method, _ -> when {
            method.endsWith("scan") -> scan(session = false)
            method.endsWith("stat") -> stat(text)
            else -> chunk(text).also { if (corrupted) { corrupted = false; it.put("offset", 99) } }
        } }
        show(); waitDescription("Open file /visible.txt")
        compose.onNodeWithText("Session").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open file /visible.txt").performClick()
        waitText("Couldn't load preview"); compose.onNodeWithText("Retry").performClick(); waitText(text)
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { !visible }
        assertTrue(peer.requests.filter { it.getString("method").endsWith(".fetch") }.all {
            it.getString("method") == "mobile.terminal.artifact.fetch" && it.getJSONObject("params").getString("surface_id") == "surface"
        })
    }
}
