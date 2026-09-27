package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalTestApi::class)
class NativeTaskDestinationsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var peer: NativeFixturePeer
    private lateinit var client: MobileRpcClient
    private lateinit var repository: TaskDraftRepository
    private val draftId = UUID.randomUUID().toString()
    private var groups by mutableStateOf(listOf(NativeGroup("group-one", "Work", false, false)))
    private var inventory by mutableStateOf(true)
    private var generation by mutableIntStateOf(0)
    private var created: JSONObject? = null
    private var beforePersist: (() -> Unit)? = null
    @Before fun setup() {
        compose.runOnUiThread { compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        val store = NativeCredentialStore(context); store.clear(); store.update { it.put("refresh_token", "destination-test-fixture") }
        TaskDraftRepository.clearMemory()
        repository = TaskDraftRepository.get(context, store.taskSession()!!)
        peer = NativeFixturePeer()
        client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
        runBlocking { client.connect() }
    }
    @After fun cleanup() { compose.activity.finish(); if (::client.isInitialized) client.close(); if (::peer.isInitialized) peer.close(); TaskDraftRepository.clearMemory(); NativeCredentialStore(context).clear() }
    private fun show(directory: String = "/root") {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().background(Color(0xFF0B0C0E)).statusBarsPadding().navigationBarsPadding().imePadding()) {
            key(generation) { NativeTaskComposerView(client, listOf(directory), "destination-mac", remember { TaskModelRepository() }, {}, {},
                savedDrafts = repository.drafts, savedTemplates = repository.templates, draftId = draftId,
                persistDrafts = { repository.persistNow(); beforePersist?.invoke() }, flushDrafts = repository::flush, catalog = { awaitCancellation() },
                supportsGroups = true, workspaceGroups = groups, groupsLoaded = inventory,
                groupIsCurrent = { id -> id == null || (inventory && groups.count { it.id == id } == 1) },
                refreshWorkspaces = {}, createTask = { params -> created = JSONObject(params.toString()); client.request("workspace.create", params) }) }
        } } }
        waitFor("Task Options")
    }
    private fun waitFor(text: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun options() { compose.onNodeWithText("Task Options").performClick(); waitFor("Workspace name (optional)") }
    private fun browse() { options(); compose.onNodeWithContentDescription("Browse folders").performClick(); waitFor("Search folders") }
    private fun screenshot(name: String) {
        compose.waitForIdle(); val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    private fun page(path: String, names: List<String>, offset: Int = 0, total: Int = names.size, unreadable: String? = null) =
        JSONObject().put("current_path", path).put("parent_path", if (path == "/") JSONObject.NULL else path.substringBeforeLast('/').ifEmpty { "/" })
            .put("entries", JSONArray(names.map { name -> JSONObject().put("name", name).put("path", path.trimEnd('/') + "/" + name)
                .put("is_hidden", name.startsWith('.')).put("is_package", false).put("is_symbolic_link", false).put("is_readable", name != unreadable) }))
            .put("offset", offset).put("limit", 50).put("total_count", total).put("next_offset", (offset + names.size).takeIf { it < total })

    @Test fun folderBrowsePaginatesAndSendsExactChosenPathWithExplicitNameAndGroup() {
        assertEquals("👩🏽‍💻".repeat(60), TaskCommand.parameters(TaskCommand.Agent.SHELL, "👩🏽‍💻".repeat(61), null,
            UUID.randomUUID()).getString("title"))
        peer.directoryResponse = { method, params ->
            assertEquals("mobile.directory.list", method)
            val path = params.getString("path"); val offset = params.getInt("offset")
            assertEquals(50, params.getInt("limit"))
            if (path == "/root") page(path, (offset until minOf(offset + 50, 52)).map { "folder-${it.toString().padStart(2, '0')}" }, offset, 52, "folder-00")
            else page(path, emptyList())
        }
        show(); compose.onNodeWithText("Task prompt").performTextInput("Build this task 中")
        options(); compose.onNodeWithText("Workspace name (optional)").performTextInput(" Named workspace 👩🏽‍💻 ")
        compose.onNodeWithContentDescription("Workspace group").performClick(); compose.onNodeWithText("Work").performClick()
        screenshot("task-options")
        compose.onNodeWithContentDescription("Browse folders").performClick(); waitFor("Choose root")
        compose.onNodeWithContentDescription("Open folder: folder-00").assertIsNotEnabled()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(49)
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.directory.list" && it.getJSONObject("params").optInt("offset") == 50 } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(51)
        compose.onNodeWithContentDescription("Open folder: folder-51").performClick(); waitFor("Choose folder-51")
        screenshot("task-directory-browser")
        compose.onNodeWithText("Choose folder-51").performClick(); waitFor("Workspace name (optional)")
        compose.onNodeWithText("Done").performClick(); compose.onNodeWithText("Directory on Mac").assertTextContains("/root/folder-51")
        compose.onNodeWithText("Create Task").performClick(); waitFor("Task Created")
        assertEquals("/root/folder-51", created!!.getString("working_directory"))
        assertEquals("Named workspace 👩🏽‍💻", created!!.getString("title")); assertEquals("group-one", created!!.getString("group_id"))
    }

    @Test fun searchCancelsStaleQueryAndReportsPartialCoverageWithoutDroppingLocalSuggestions() {
        val delayed = CountDownLatch(1)
        peer.directoryResponse = { method, params ->
            if (method == "mobile.directory.list") page(params.getString("path"), emptyList()) else {
                val query = params.getString("query")
                if (query == "old") check(delayed.await(15, TimeUnit.SECONDS))
                JSONObject().put("directories", JSONArray(listOf(if (query == "old") "/old" else "/root/new")))
                    .put("search_scope", "all_indexed_volumes").put("gathering_complete", false)
            }
        }
        try {
            show(); browse(); waitFor("Choose root")
            compose.onNodeWithText("Search folders").performTextInput("old")
            compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.directory.search" && it.getJSONObject("params").optString("query") == "old" } }
            compose.onNodeWithText("Search folders").performTextReplacement("root")
            delayed.countDown()
            waitFor("The Mac search index did not finish in time. Refine your search or retry.")
            compose.onNodeWithContentDescription("Use folder: /old").assertDoesNotExist()
            compose.onNodeWithContentDescription("Use folder: /root").assertExists()
            compose.onNodeWithContentDescription("Use folder: /root/new").assertExists()
            screenshot("task-directory-search")
            compose.onNodeWithContentDescription("Use folder: /root/new").performClick()
            compose.onNodeWithText("Done").performClick(); compose.onNodeWithText("Directory on Mac").assertTextContains("/root/new")
        } finally { delayed.countDown() }
    }

    @Test fun unsupportedAndPermissionErrorsRetainLocationsAndAllowExplicitRetry() {
        peer.directoryErrorCode = "permission_denied"
        peer.directoryResponse = { method, params -> if (method == "mobile.directory.list") page(params.getString("path"), listOf("project"))
            else JSONObject().put("directories", JSONArray()) }
        show(); browse()
        waitFor("Retry folder")
        compose.onNodeWithText("Choose root").assertDoesNotExist()
        screenshot("task-directory-access-error")
        peer.directoryErrorCode = null
        compose.onNodeWithText("Retry folder").performClick(); waitFor("Choose root")
        peer.directoryErrorCode = "method_not_found"
        compose.onNodeWithText("Search folders").performTextInput("root")
        waitFor("Update cmux on this Mac to search its folders. You can still choose a recent location.")
        compose.onNodeWithContentDescription("Use folder: /root").assertExists()
        compose.onNodeWithContentDescription("Clear folder search").performClick()
        compose.onNodeWithContentDescription("Parent folder").performClick()
        compose.onNodeWithContentDescription("Parent folder").performClick()
        waitFor("Home"); compose.onNodeWithText("Computer").assertExists()
    }

    @Test fun restoredGroupWaitsForInventoryAndMissingGroupRequiresExplicitResolution() {
        val editor = repository.drafts.begin(draftId, "destination-mac", "Mac", "/root")
        repository.drafts.edit(editor) { it.copy(prompt = "Keep this named draft", workspaceName = "Saved name", groupId = "group-one") }
        runBlocking { repository.persistNow() }; repository.drafts.end(editor)
        inventory = false
        show(); compose.onNodeWithText("Create Task").assertIsNotEnabled()
        options(); compose.onNodeWithText("Workspace name (optional)").assertTextContains("Saved name")
        compose.onNodeWithContentDescription("Workspace group").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Loading groups…"))
        compose.runOnIdle { groups = emptyList(); inventory = true }
        waitFor("Choose another group or select None before submitting.")
        compose.onNodeWithText("Done").performClick(); compose.onNodeWithText("Create Task").assertIsNotEnabled()
        assertTrue(peer.requests.none { it.optString("method") == "workspace.create" })
        compose.runOnIdle { generation++ }
        options(); compose.onNodeWithContentDescription("Workspace group").performClick(); compose.onNodeWithText("None").performClick()
        compose.onNodeWithText("Done").performClick(); compose.onNodeWithText("Create Task").performClick(); waitFor("Task Created")
        assertFalse(created!!.has("group_id")); assertEquals("Saved name", created!!.getString("title"))
    }

    @Test fun groupRemovedDuringDurableSaveCannotReachCreateRpc() {
        show(); compose.onNodeWithText("Task prompt").performTextInput("Do not send a missing group")
        options(); compose.onNodeWithContentDescription("Workspace group").performClick(); compose.onNodeWithText("Work").performClick()
        compose.onNodeWithText("Done").performClick()
        beforePersist = { groups = emptyList() }
        compose.onNodeWithText("Create Task").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("The selected group is no longer available.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertNull(created); assertTrue(peer.requests.none { it.optString("method") == "workspace.create" })
        compose.onNodeWithText("Task prompt").assertTextContains("Do not send a missing group")
    }
}
