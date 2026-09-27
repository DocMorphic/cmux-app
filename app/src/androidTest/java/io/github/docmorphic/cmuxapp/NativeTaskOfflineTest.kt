package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Offline editor lifetime and explicit submission through the real screen/RPC boundary. */
@OptIn(ExperimentalTestApi::class)
class NativeTaskOfflineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: NativeCredentialStore
    private lateinit var repository: TaskDraftRepository
    private lateinit var peer: NativeFixturePeer
    private var rpc: MobileRpcClient? = null
    private val code = "cmux-ios://attach?v=2&r=100.64.0.1:58465"

    @Before fun setup() {
        store = NativeCredentialStore(context)
        store.clear(); store.update { it.put("refresh_token", "offline-fixture-only") }
        TaskDraftRepository.clearMemory()
        repository = TaskDraftRepository.get(context, store.taskSession()!!)
        peer = NativeFixturePeer()
    }
    @After fun cleanup() {
        compose.activity.finish(); rpc?.close(); peer.close()
        TaskDraftRepository.clearMemory(); store.clear()
    }
    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun offlineScreenSavesDraftAndReconnectNeverSubmitsAutomatically() {
        store.rememberMac(code, "fixture-mac", "Fixture Mac")
        val ready = CompletableDeferred<Unit>()
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                ready.await()
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        waitFor(hasContentDescription("New Task"))
        compose.onNodeWithContentDescription("New Task").assertIsEnabled().performClick()
        waitFor(hasContentDescription("Task prompt"))
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Write this while offline 中")
        compose.onNodeWithContentDescription("Create Task").assertIsEnabled().performClick()
        waitFor(hasText("That Mac is not connected. Open cmux on the Mac, then try again."))
        val draft = repository.drafts.state.value.values.single()
        assertNull(draft.lastRequest)
        assertEquals(draft.prompt, TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value.getValue(draft.id).prompt)
        assertTrue(peer.requests.isEmpty())
        compose.runOnIdle { ready.complete(Unit) }
        compose.waitUntil(15_000) { peer.requests.any { it.optString("method") == "mobile.task.models.list" } }
        compose.onNodeWithContentDescription("Task prompt").assertTextContains(draft.prompt)
        assertTrue(peer.requests.none { it.optString("method") == "workspace.create" })
        compose.onNodeWithContentDescription("Create Task").performClick()
        compose.waitUntil(15_000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" } }
        val sent = peer.requests.single { it.optString("method") == "workspace.create" }.getJSONObject("params")
        assertEquals(draft.prompt, sent.getJSONObject("initial_env").getString("CMUX_TASK_PROMPT"))
    }

    @Test fun noPairingStillAllowsSavingAndResumingDrafts() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ -> error("No pairing must mean no connection") })
        } } }
        waitFor(hasText("New task"))
        compose.onNodeWithText("New task").performClick()
        waitFor(hasContentDescription("Task prompt"))
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Keep my unpaired draft")
        compose.onNodeWithContentDescription("Create Task").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back to workspaces").performClick()
        compose.onNodeWithText("Save Draft").performClick()
        waitFor(hasText("New task"))
        compose.onNodeWithText("New task").performClick()
        waitFor(hasContentDescription("Drafts"))
        compose.onNodeWithContentDescription("Drafts").performClick()
        compose.onNodeWithText("Keep my unpaired draft").performClick()
        waitFor(hasContentDescription("Task prompt"))
        compose.onNodeWithContentDescription("Task prompt").assertTextContains("Keep my unpaired draft")
        assertTrue(peer.requests.isEmpty())
    }

    @Test fun draftOpenedDuringFirstHandshakeAdoptsVerifiedMacWithoutLosingEdits() {
        store.update { it.put("pairing_code", code) }
        val ready = CompletableDeferred<Unit>()
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                ready.await()
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            })
        } } }
        waitFor(hasContentDescription("New Task"))
        compose.onNodeWithContentDescription("New Task").performClick()
        waitFor(hasContentDescription("Task prompt"))
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Keep these first-pairing edits")
        compose.onNodeWithContentDescription("Create Task").assertIsNotEnabled()
        compose.runOnIdle { ready.complete(Unit) }
        waitFor(hasContentDescription("Create Task") and isEnabled())
        compose.onNodeWithContentDescription("Task prompt").assertTextContains("Keep these first-pairing edits")
        val draft = repository.drafts.state.value.values.single()
        assertEquals(store.pairedMacs().single().origin, draft.origin)
        assertEquals("Fixture Mac", draft.macName)
        assertNull(draft.lastRequest)
        assertTrue(peer.requests.none { it.optString("method") == "workspace.create" })
    }

    @Test fun disconnectPreservesEditorAndGroupUntilFreshInventory() {
        val id = UUID.randomUUID().toString()
        val editor = repository.drafts.begin(id, "offline-mac", "Fixture Mac", "/repo")
        repository.drafts.edit(editor) { it.copy(prompt = "Preserve this task", groupId = "group-one") }
        repository.drafts.end(editor)
        rpc = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { runBlocking { it.connect() } }
        var online by mutableStateOf(true)
        var groups by mutableStateOf(listOf(NativeGroup("group-one", "Work", false, false)))
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
            NativeTaskComposerView(if (online) rpc else null, listOf("/repo"), "offline-mac", remember { TaskModelRepository() }, {}, {},
                savedDrafts = repository.drafts, draftId = id, savedTemplates = repository.templates,
                persistDrafts = repository::persistNow, flushDrafts = repository::flush, catalog = { awaitCancellation() },
                supportsGroups = if (online) true else null, groupsLoaded = online, workspaceGroups = groups)
        } } }
        waitFor(hasContentDescription("Task prompt"))
        compose.runOnIdle { online = false }
        compose.onNodeWithContentDescription("Task prompt").assertIsEnabled().performTextInput(" and offline changes")
        compose.onNodeWithContentDescription("Create Task").assertIsNotEnabled()
        assertEquals("group-one", repository.drafts.state.value.getValue(id).groupId)
        compose.onNodeWithContentDescription("Task Options").performClick()
        compose.onNodeWithText("Waiting for this Mac’s groups.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Browse folders").performClick()
        waitFor(hasText("Reconnect to this Mac, then try again."))
        compose.onNodeWithText("Search folders").performTextInput("repo")
        waitFor(hasContentDescription("Use folder: /repo"))
        compose.onNodeWithContentDescription("Use folder: /repo").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { groups = emptyList(); online = true }
        compose.onNodeWithText("The selected group is unavailable. Open Task Options to choose another group or None.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Create Task").assertIsNotEnabled()
        assertTrue(repository.drafts.state.value.getValue(id).prompt.contains("offline changes"))
        assertEquals("group-one", repository.drafts.state.value.getValue(id).groupId)
        assertTrue(peer.requests.none { it.optString("method") == "workspace.create" })
    }
}
