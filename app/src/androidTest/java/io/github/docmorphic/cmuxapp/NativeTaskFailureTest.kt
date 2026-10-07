package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

@OptIn(ExperimentalTestApi::class)
class NativeTaskFailureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: NativeCredentialStore
    private lateinit var repository: TaskDraftRepository
    private lateinit var peer: NativeFixturePeer
    private lateinit var client: MobileRpcClient
    private val id = UUID.randomUUID().toString()
    private val models = TaskModelRepository()
    private var opened = false

    @Before fun setup() {
        TaskDraftRepository.clearMemory()
        store = NativeCredentialStore(context).also { it.clear(); it.update { state -> state.put("refresh_token", "failure-fixture") } }
        repository = TaskDraftRepository.get(context, store.taskSession()!!)
        peer = NativeFixturePeer()
        client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
        runBlocking { client.connect(); client.hostStatus() }
    }
    @After fun cleanup() {
        compose.activity.finish(); client.close(); peer.close()
        TaskDraftRepository.clearMemory(); TaskDraftRepository.clearAttachments(context); store.clear()
    }
    private fun show() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            NativeTaskComposerView(client, listOf("/repo"), "failure-mac", models, { opened = true }, {},
                savedDrafts = repository.drafts, draftId = id, catalog = { awaitCancellation() },
                persistDrafts = repository::persistNow, flushDrafts = repository::flush,
                attachmentRepository = repository, supportsAttachments = true)
        } } }
        compose.waitUntil(10_000) { models.cached(TaskModelRepository.Key("failure-mac", TaskAgentCommand.CLAUDE)) != null }
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Keep the task and explain the failure")
    }
    private fun banner(title: String, message: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("TaskComposerFailureTitle").assertTextEquals(title).assertIsDisplayed()
        compose.onNodeWithTag("TaskComposerFailureMessage").assertTextEquals(message).assertIsDisplayed()
        compose.onNodeWithTag("TaskComposerFailureTitle").assert(hasAnyAncestor(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)))
        compose.onNodeWithContentDescription("Create Task").assertIsEnabled()
        compose.onNodeWithContentDescription("Task prompt").assertTextContains("Keep the task and explain the failure")
    }
    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val painted = java.util.concurrent.CountDownLatch(1)
        compose.runOnUiThread {
            val window = android.view.inspector.WindowInspector.getGlobalWindowViews().first { it.hasWindowFocus() }
            window.postOnAnimation { window.postOnAnimation { painted.countDown() } }
        }
        assertTrue(painted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        i.waitForIdleSync()
        val folder = java.io.File(context.getExternalFilesDir(null), "task-failures").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun hostRejectionAndTimeoutHaveDifferentBannersAndExplicitRetriesReuseIdentity() {
        show()
        for ((code, message) in listOf(
            "invalid_working_directory" to "Choose an existing folder on that Mac.",
            "busy" to "Another workspace action is still finishing.",
            "unauthorized" to "That Mac did not authorize the request.",
            "request_timeout" to "The Mac did not respond in time. Check your workspace list before retrying.")) {
            peer.nextTaskCreateError.set(code)
            compose.onNodeWithContentDescription("Create Task").performClick()
            banner(if (code == "request_timeout") "Task status unconfirmed" else "Couldn’t start this task", message)
            if (code == "invalid_working_directory" || code == "request_timeout") capture(code)
            assertFalse(opened)
        }
        val failed = peer.requests.filter { it.optString("method") == "workspace.create" }
        assertEquals(4, failed.size)
        assertEquals(1, failed.map { it.getJSONObject("params").getString("operation_id") }.distinct().size)
        compose.onNodeWithContentDescription("Task prompt").performTextInput(" with a correction")
        compose.onNodeWithTag("TaskComposerFailureTitle").assertDoesNotExist()
        compose.onNodeWithContentDescription("Create Task").performClick()
        compose.waitUntil(10_000) { opened }
        val last = peer.requests.last { it.optString("method") == "workspace.create" }
        assertNotEquals(failed.first().getJSONObject("params").getString("operation_id"), last.getJSONObject("params").getString("operation_id"))
    }

    @Test fun uploadRejectionPreservesFilesAndDoesNotClaimTaskWasSent() {
        val editor = repository.drafts.begin(id, "failure-mac", "Mac", "/repo")
        val bytes = "Keep attachment bytes after rejection".toByteArray()
        val item = ComposerAttachment(name = "keep.txt", size = bytes.size)
        runBlocking { repository.attach(editor, AttachmentFiles.Prepared(item, bytes)) }
        repository.drafts.end(editor)
        peer.rejectedMethods = setOf("mobile.task.attachment.upload")
        peer.rejectedMethodCode = "unauthorized"
        show()
        compose.onNodeWithContentDescription("Create Task").performClick()
        banner("Couldn’t start this task", "That Mac did not authorize the attachment upload.")
        capture("upload-rejected")
        assertTrue(peer.requests.none { it.optString("method") == "workspace.create" })
        assertEquals(listOf(item), repository.drafts.state.value.getValue(id).attachments)
        assertArrayEquals(bytes, runBlocking { repository.readAttachment(item) })
        val attempt = peer.requests.single { it.optString("method") == "mobile.task.attachment.upload" }.getJSONObject("params")
        peer.rejectedMethods = emptySet()
        compose.onNodeWithContentDescription("Create Task").performClick()
        compose.waitUntil(10_000) { opened }
        val created = peer.requests.single { it.optString("method") == "workspace.create" }.getJSONObject("params")
        assertEquals(attempt.getString("operation_id"), created.getString("operation_id"))
        val uploads = peer.requests.filter { it.optString("method") == "mobile.task.attachment.upload" }
        assertEquals(2, uploads.size)
        assertEquals(attempt.getString("upload_id"), uploads.last().getJSONObject("params").getString("upload_id"))
    }
}
