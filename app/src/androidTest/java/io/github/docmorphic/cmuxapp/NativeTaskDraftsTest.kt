package io.github.docmorphic.cmuxapp

import android.content.Context
import android.graphics.Bitmap
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
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.IOException
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalTestApi::class)
class NativeTaskDraftsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: NativeCredentialStore
    private lateinit var repository: TaskDraftRepository
    private lateinit var peer: NativeFixturePeer
    private lateinit var client: MobileRpcClient
    private val models = TaskModelRepository()
    private var activeId by mutableStateOf(UUID.randomUUID().toString())

    @Before fun start() {
        TaskDraftRepository.clearMemory()
        store = NativeCredentialStore(context).also { it.clear(); it.update { state -> state.put("refresh_token", "draft-fixture-session") } }
        repository = runBlocking(Dispatchers.IO) { TaskDraftRepository.get(context, store.taskSession()!!) }
        peer = NativeFixturePeer()
        client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
        runBlocking { client.connect() }
    }
    @After fun close() {
        compose.activity.finish(); client.close(); peer.close()
        TaskDraftRepository.clearMemory(); store.clear()
    }
    private fun show(persist: suspend () -> Unit = repository::persistNow,
        supportsCreation: () -> Boolean = { true }, onBack: () -> Unit = {},
        create: suspend (JSONObject) -> JSONObject = { client.request("workspace.create", it) }) {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().background(Color(0xFF0B0C0E))
            .statusBarsPadding().navigationBarsPadding().imePadding()) {
            key(activeId) {
                NativeTaskComposerView(client, listOf("/repo"), "draft-mac", models, {}, onBack,
                    catalog = { awaitCancellation() }, createTask = create,
                    savedDrafts = repository.drafts, draftId = activeId, macName = "Fixture Mac",
                    persistDrafts = persist, flushDrafts = repository::flush,
                    onNewDraft = { activeId = UUID.randomUUID().toString() }, onResumeDraft = { activeId = it.id },
                    supportsTaskCreation = supportsCreation())
            }
        } } }
    }
    private fun state(label: String, value: String) {
        val matcher = hasContentDescription(label) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun saveSwitchResumeAndDeleteKeepIndependentDrafts() {
        show(); state("Effort", "High")
        compose.openTaskPicker("Agent")
        compose.onNodeWithText("Codex", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { models.cached(TaskModelRepository.Key("draft-mac", TaskAgentCommand.CODEX))?.source == TaskModelSource.DISCOVERED }
        state("Effort", "High")
        compose.openTaskPicker("Model")
        compose.onNodeWithText("Local codex").performClick()
        compose.openTaskPicker("Effort")
        compose.onNodeWithText("Low").performClick()
        compose.onNodeWithContentDescription("Task prompt").performTextInput("First saved task 中")
        val firstId = activeId
        compose.onNodeWithContentDescription("Drafts").performClick()
        compose.onNodeWithText("No Other Drafts").assertIsDisplayed()
        compose.onNodeWithText("＋ New Draft").performClick()
        compose.waitUntil(10_000) { activeId != firstId }
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Second saved task")
        val secondId = activeId
        compose.onNodeWithContentDescription("Drafts").performClick()
        screenshot("task-drafts-list")
        compose.onNodeWithText("First saved task 中").performClick()
        compose.waitUntil(10_000) { activeId == firstId }
        compose.onNodeWithContentDescription("Task prompt").assertTextContains("First saved task 中")
        state("Model", "Local codex"); state("Effort", "Low")
        screenshot("task-draft-restored")
        compose.onNodeWithContentDescription("Drafts").performClick()
        compose.onNodeWithContentDescription("Delete draft: Second saved task").performClick()
        compose.waitUntil(10_000) { secondId !in repository.drafts.state.value }
        compose.onNodeWithText("No Other Drafts").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        runBlocking { repository.persistNow() }
        val saved = TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value
        assertEquals(setOf(firstId), saved.keys)
        assertEquals("low", saved.getValue(firstId).selection.effortId)
    }

    @Test fun leaveOffersSaveDeleteAndKeepEditing() {
        var closed = 0
        show(onBack = { closed++ }); state("Effort", "High")
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Keep this before leaving")
        compose.onNodeWithContentDescription("Back to workspaces").performClick()
        screenshot("task-draft-leave")
        compose.onNodeWithText("Keep Editing").performClick()
        assertEquals(0, closed)
        compose.onNodeWithContentDescription("Task prompt").assertTextContains("Keep this before leaving")
        compose.onNodeWithContentDescription("Back to workspaces").performClick()
        compose.onNodeWithText("Save Draft").performClick()
        compose.waitUntil(10_000) { closed == 1 }
        assertEquals("Keep this before leaving", TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value.getValue(activeId).prompt)
        compose.onNodeWithContentDescription("Back to workspaces").performClick()
        compose.onNodeWithText("Delete Draft").performClick()
        compose.waitUntil(10_000) { closed == 2 }
        assertTrue(TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value.isEmpty())
    }

    @Test fun failedDurableSaveAndUnsupportedHostCannotSendTask() {
        var supported by mutableStateOf(false)
        var attempts = 0
        show(persist = { throw IOException("Disk unavailable") }, supportsCreation = { supported }, create = { attempts++; JSONObject() })
        state("Effort", "High")
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Do not send without a saved retry ID")
        compose.onNodeWithContentDescription("Create Task").assertIsNotEnabled()
        compose.onNodeWithText("Update cmux on this Mac to create tasks.").assertIsDisplayed()
        compose.runOnIdle { supported = true }
        compose.onNodeWithContentDescription("Create Task").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Disk unavailable", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, attempts)
        compose.onNodeWithContentDescription("Task prompt").assertTextContains("Do not send without a saved retry ID")
        assertNotNull(repository.drafts.state.value.getValue(activeId).lastRequest)
    }

    @Test fun encryptedColdReloadAndSignOutFenceOldWriters() = runBlocking {
        val editor = repository.drafts.begin(UUID.randomUUID().toString(), "draft-mac", "Fixture Mac", "/repo")
        repository.drafts.edit(editor) { it.copy(prompt = "PRIVATE_DRAFT_MARKER_中") }
        repository.persistNow()
        val stored = context.getSharedPreferences("native_cmux", Context.MODE_PRIVATE).getString("state", "")!!
        assertFalse(stored.contains("PRIVATE_DRAFT_MARKER"))
        val old = repository
        TaskDraftRepository.clearMemory()
        repository = TaskDraftRepository.get(context, store.taskSession()!!)
        assertEquals("PRIVATE_DRAFT_MARKER_中", repository.drafts.state.value.getValue(editor.id).prompt)
        NativeAccount(store).signOut()
        store.update { it.put("refresh_token", "different-account-fixture") }
        val nextSession = store.taskSession()!!
        assertNotEquals(old.session, nextSession)
        val result = runCatching { old.persistNow() }
        assertTrue(result.isFailure)
        assertFalse(store.load()!!.has("task_drafts"))
        assertEquals("different-account-fixture", store.load()!!.getString("refresh_token"))
    }

    @Test fun staleTokenRefreshCannotOverwriteOrClearNewAccountDrafts() = runBlocking {
        for (rejected in listOf(false, true)) {
            store.clear(); store.update { it.put("refresh_token", "old-refresh-$rejected") }
            store.taskSession()
            val started = CountDownLatch(1); val release = CountDownLatch(1)
            val account = NativeAccount(store) {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                if (rejected) throw NativeAccount.InvalidRefreshToken()
                "stale-access-token"
            }
            val refresh = async(Dispatchers.IO) { account.accessToken() }
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS))
                store.update { it.put("refresh_token", "new-refresh").put("access_token", "new-access")
                    .put("task_session", UUID.randomUUID().toString()).put("task_drafts", JSONObject().put("sentinel", "new-account")) }
                release.countDown()
                assertNull(refresh.await())
                val current = store.load()!!
                assertEquals("new-access", current.getString("access_token"))
                assertEquals("new-refresh", current.getString("refresh_token"))
                assertEquals("new-account", current.getJSONObject("task_drafts").getString("sentinel"))
            } finally { release.countDown(); refresh.cancel() }
        }
    }
}
