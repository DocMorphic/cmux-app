package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.CountDownLatch

/** Real composer and socket upload boundary; no workspace may follow cancelled preparation. */
@OptIn(ExperimentalTestApi::class)
class NativeTaskCancellationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: NativeCredentialStore
    private lateinit var repository: TaskDraftRepository
    private lateinit var peer: NativeFixturePeer
    private lateinit var client: MobileRpcClient
    private val id = UUID.randomUUID().toString()
    private val models = TaskModelRepository()
    private var left = false
    private var opened = false
    private var mounted by mutableStateOf(true)
    private val requests = mutableListOf<JSONObject>()

    @Before fun setup() {
        TaskDraftRepository.clearMemory()
        store = NativeCredentialStore(context).also { it.clear(); it.update { state -> state.put("refresh_token", "cancellation-fixture") } }
        repository = TaskDraftRepository.get(context, store.taskSession()!!)
        peer = NativeFixturePeer()
        client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
        runBlocking { client.connect(); client.hostStatus() }
    }
    @After fun cleanup() {
        peer.releaseTaskUpload?.countDown()
        compose.activity.finish(); client.close(); peer.close()
        TaskDraftRepository.clearMemory(); TaskDraftRepository.clearAttachments(context); store.clear()
    }
    private fun show(persist: suspend () -> Unit = repository::persistNow,
        create: suspend (JSONObject) -> JSONObject = { client.request("workspace.create", it) }) {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            if (mounted) NativeTaskComposerView(client, listOf("/repo"), "cancel-mac", models,
                onCreated = { opened = true }, onBack = { left = true; mounted = false },
                savedDrafts = repository.drafts, draftId = id, catalog = { awaitCancellation() },
                persistDrafts = persist, flushDrafts = repository::flush,
                attachmentRepository = repository, supportsAttachments = true,
                createTask = { requests += JSONObject(it.toString()); create(it) })
        } } }
        compose.waitUntil(10_000) { models.cached(TaskModelRepository.Key("cancel-mac", TaskAgentCommand.CLAUDE)) != null }
        compose.waitForIdle()
    }
    private fun waitFor(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun typeAndCreate() {
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Preserve my task")
        compose.onNodeWithContentDescription("Create Task").performClick()
    }
    private fun cancelPreparation() {
        compose.onNodeWithContentDescription("Back to workspaces").assertIsEnabled().performClick()
        waitFor("Save this draft?")
    }

    @Test fun cancelDuringSaveKeepsEditsAndExplicitRetryCreatesOnlyOnce() {
        val entered = CompletableDeferred<Unit>()
        var holdSave = true
        show(persist = {
            if (holdSave) { entered.complete(Unit); awaitCancellation() }
            repository.persistNow()
        })
        typeAndCreate()
        compose.waitUntil(10_000) { entered.isCompleted }
        cancelPreparation()
        assertNull(repository.drafts.state.value.getValue(id).lastRequest)
        assertTrue(requests.isEmpty())
        compose.onNodeWithText("Keep Editing").performClick()
        compose.onNodeWithContentDescription("Task prompt").assertTextContains("Preserve my task")
        compose.runOnIdle { holdSave = false }
        compose.onNodeWithContentDescription("Create Task").performClick()
        compose.waitUntil(10_000) { opened }
        assertEquals(1, requests.size)
        assertFalse(left)
    }

    @Test fun cancelPendingUploadSavesDraftAndIgnoresLateReply() {
        val editor = repository.drafts.begin(id, "cancel-mac", "Mac", "/repo")
        val bytes = "Keep this attachment".toByteArray()
        val item = ComposerAttachment(name = "keep.txt", size = bytes.size)
        runBlocking { repository.attach(editor, AttachmentFiles.Prepared(item, bytes)) }
        repository.drafts.end(editor)
        val release = CountDownLatch(1)
        peer.releaseTaskUpload = release
        show()
        typeAndCreate()
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.task.attachment.upload" } }
        cancelPreparation()
        compose.onNodeWithText("Save Draft").performClick()
        compose.waitUntil(10_000) { left }
        release.countDown()
        // A subsequent RPC confirms the socket consumed the delayed response.
        runBlocking { client.workspaces() }
        compose.waitForIdle()
        assertTrue(requests.isEmpty())
        assertFalse(opened)
        val durable = TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value.getValue(id)
        assertEquals("Preserve my task", durable.prompt)
        assertNull(durable.lastRequest)
        assertEquals(listOf(item), durable.attachments)
        assertArrayEquals(bytes, runBlocking { repository.readAttachment(item) })
        assertTrue(peer.requests.none { it.optString("method") == "workspace.create" })
    }

    @Test fun cancellingRetryPreparationPreservesPreviousUncertainIdentity() {
        var holdSave = false
        val entered = CompletableDeferred<Unit>()
        show(persist = {
            if (holdSave) { entered.complete(Unit); awaitCancellation() }
            repository.persistNow()
        }, create = { throw MobileRpcException("request_timeout", "Unconfirmed") })
        typeAndCreate()
        waitFor("The Mac did not respond in time. Check your workspace list before retrying.")
        val previous = repository.drafts.state.value.getValue(id).lastRequest
        assertNotNull(previous)
        compose.onNodeWithContentDescription("Task prompt").performTextReplacement("Different task, cancelled before creation")
        compose.runOnIdle { holdSave = true }
        compose.onNodeWithContentDescription("Create Task").performClick()
        compose.waitUntil(10_000) { entered.isCompleted }
        cancelPreparation()
        assertEquals(previous, repository.drafts.state.value.getValue(id).lastRequest)
        compose.onNodeWithText("Keep Editing").performClick()
        compose.onNodeWithContentDescription("Task prompt").performTextReplacement("Preserve my task")
        compose.runOnIdle { holdSave = false }
        compose.onNodeWithContentDescription("Create Task").performClick()
        waitFor("The Mac did not respond in time. Check your workspace list before retrying.")
        assertEquals(2, requests.size)
        assertEquals(requests.first().getString("operation_id"), requests.last().getString("operation_id"))
    }

    @Test fun committedCreationLocksBackAndParentDisposalDropsLateResult() {
        val release = CompletableDeferred<Unit>()
        show(create = { parameters ->
            // Simulate a non-cooperative late transport completion after removal.
            withContext(NonCancellable) { release.await(); client.request("workspace.create", parameters) }
        })
        try {
            typeAndCreate()
            compose.waitUntil(10_000) { requests.size == 1 }
            compose.onNodeWithContentDescription("Back to workspaces").assertIsNotEnabled()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("Save this draft?").assertDoesNotExist()
            assertFalse(left)
            val submitted = repository.drafts.state.value.getValue(id).lastRequest
            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            release.complete(Unit)
            compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "workspace.create" } }
            runBlocking { client.workspaces() }
            compose.waitForIdle()
            assertFalse(opened)
            assertEquals(submitted, repository.drafts.state.value.getValue(id).lastRequest)
            assertEquals(1, requests.size)
        } finally { release.complete(Unit) }
    }
}
