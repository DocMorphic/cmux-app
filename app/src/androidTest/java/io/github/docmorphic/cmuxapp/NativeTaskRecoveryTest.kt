package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch

@OptIn(ExperimentalTestApi::class)
class NativeTaskRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var peer: NativeFixturePeer
    private lateinit var client: MobileRpcClient
    private val drafts = TaskDrafts()
    private val id = UUID.randomUUID().toString()
    private val requests = mutableListOf<JSONObject>()
    private val order = mutableListOf<String>()
    private var persisted = JSONObject()
    private var opened: JSONObject? = null
    private var failSave = false
    private val models = TaskModelRepository()

    @Before fun start() {
        peer = NativeFixturePeer()
        client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
        runBlocking { client.connect() }
    }
    @After fun close() { compose.activity.finish(); client.close(); peer.close() }
    private fun show(composerClient: () -> MobileRpcClient = { client },
        refresh: suspend () -> Unit = { client.workspaces(); Unit },
        create: suspend (JSONObject) -> JSONObject) {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().background(Color(0xFF0B0C0E))
            .statusBarsPadding().navigationBarsPadding().imePadding()) {
            NativeTaskComposerView(composerClient(), listOf("/repo"), "recovery-mac", models,
                onCreated = { opened = it }, onBack = {}, catalog = { awaitCancellation() },
                savedDrafts = drafts, draftId = id,
                persistDrafts = { if (failSave) throw IOException("fixture save failed"); persisted = drafts.saved() },
                refreshWorkspaces = { order += "refresh"; refresh() },
                createTask = { params ->
                    order += "create"; requests += JSONObject(params.toString()); create(params)
                })
        } } }
    }
    private fun missing(): Nothing = throw MobileRpcException("  ALREADY_COMPLETED  ", "Fixture tombstone")
    private fun startTask() {
        compose.waitUntil(10_000) { models.cached(TaskModelRepository.Key("recovery-mac", TaskAgentCommand.CLAUDE))?.source == TaskModelSource.DISCOVERED }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Recover my task 中")
        compose.onNodeWithContentDescription("Create Task").performClick()
        waitFor("Refresh Workspaces")
        compose.onNodeWithContentDescription("Create Task").assertIsNotEnabled()
    }
    private fun waitFor(text: String) { compose.waitUntil(10_000) { compose.onAllNodes(hasText(text) or hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty() } }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val path = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(path, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun durable() = TaskDrafts(persisted).state.value.getValue(id)

    @Test fun refreshReusesCompletedRequestAndOpensRecoveredWorkspace() {
        val other = drafts.begin(UUID.randomUUID().toString(), "other-mac", "Other", "/elsewhere")
        drafts.edit(other) { it.copy(prompt = "Keep other draft") }; drafts.end(other)
        show { if (requests.size == 1) missing() else client.request("workspace.create", it) }
        startTask()
        val original = requests.single().getString("operation_id")
        assertEquals(original, JSONObject(durable().completedRequest!!).getString("operation_id"))
        assertNotEquals(original, JSONObject(durable().lastRequest!!).getString("operation_id"))
        compose.onNodeWithText("Start Again").assertDoesNotExist()
        screenshot("task-recovery-required")
        compose.onNodeWithText("Refresh Workspaces").performClick()
        compose.waitUntil(10_000) { opened != null }
        assertEquals(listOf("create", "refresh", "create"), order)
        assertEquals(requests[0].toString(), requests[1].toString())
        assertEquals("task-created", opened!!.getString("created_workspace_id"))
        assertFalse(id in drafts.state.value)
        assertEquals("Keep other draft", drafts.state.value.getValue(other.id).prompt)
    }

    @Test fun missingAfterRefreshRequiresConfirmationAndThenUsesRetiredIdentity() {
        show { if (requests.size <= 3) missing() else client.request("workspace.create", it) }
        startTask()
        val freshId = JSONObject(durable().lastRequest!!).getString("operation_id")
        compose.onNodeWithText("Refresh Workspaces").performClick()
        waitFor("Refresh Again")
        compose.onNodeWithContentDescription("Create Task").assertIsNotEnabled()
        screenshot("task-recovery-still-missing")
        compose.onNodeWithText("Refresh Again").performClick()
        compose.waitUntil(10_000) { requests.size == 3 }
        compose.waitForIdle()
        assertEquals(1, requests.map { it.getString("operation_id") }.distinct().size)
        compose.onNodeWithText("Start Again").performClick()
        waitFor("Start this task again?")
        screenshot("task-recovery-confirm")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(3, requests.size)
        compose.onNodeWithText("Start Again").performClick()
        compose.onNode(hasText("Start Again") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { opened != null }
        assertEquals(4, requests.size)
        assertEquals(freshId, requests.last().getString("operation_id"))
        assertTrue(TaskSubmissionIdentity.sameRequest(requests.first(), requests.last()))
        assertNull(durable().completedRequest)
    }

    @Test fun refreshAndPersistenceFailuresDoNotAuthorizeStartingAgain() {
        var failRefresh = true
        show(refresh = { if (failRefresh) throw MobileRpcException("already_completed", "List unavailable") }) { missing() }
        startTask()
        compose.onNodeWithText("Refresh Workspaces").performClick()
        waitFor("List unavailable. Check your workspace list before retrying.")
        compose.onNodeWithText("Start Again").assertDoesNotExist()
        assertEquals(1, requests.size)
        failRefresh = false; failSave = true
        compose.onNodeWithText("Refresh Workspaces").performClick()
        waitFor("fixture save failed. Check your workspace list before retrying.")
        compose.onNodeWithText("Start Again").assertDoesNotExist()
        assertEquals(listOf("create", "refresh"), order)
        failSave = false
        compose.onNodeWithText("Refresh Workspaces").performClick()
        waitFor("Refresh Again")
        assertEquals(2, requests.size)
    }

    @Test fun editingDetachesRecoveryRevertingRestoresItAndNewSubmissionClearsIt() {
        show { if (requests.size == 1) missing() else throw MobileRpcException("request_timeout", "Unconfirmed") }
        startTask()
        val oldId = requests.single().getString("operation_id")
        compose.onNodeWithContentDescription("Task prompt").performTextReplacement("Different task")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Refresh Workspaces").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Create Task").assertIsEnabled()
        compose.onNodeWithContentDescription("Task prompt").performTextReplacement("Recover my task 中")
        waitFor("Refresh Workspaces")
        compose.onNodeWithContentDescription("Create Task").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Task prompt").performTextReplacement("Different task")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Refresh Workspaces").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Create Task").performClick()
        waitFor("Unconfirmed. Check your workspace list before retrying.")
        assertNull(durable().completedRequest)
        assertNotEquals(oldId, requests.last().getString("operation_id"))
        compose.onNodeWithContentDescription("Task prompt").performTextReplacement("Recover my task 中")
        compose.waitForIdle()
        compose.onNodeWithText("Refresh Workspaces").assertDoesNotExist()
        compose.onNodeWithContentDescription("Create Task").assertIsEnabled()
    }

    @Test fun lateModelDiscoveryCannotDetachAcceptedRequestRecovery() {
        val discovery = CountDownLatch(1)
        val completed = CompletableDeferred<Unit>()
        peer.releaseTaskModels = discovery
        try {
            show { if (requests.size == 1) { completed.await(); missing() } else client.request("workspace.create", it) }
            compose.onNodeWithContentDescription("Task prompt").performTextInput("Keep the original default")
            compose.onNodeWithContentDescription("Create Task").performClick()
            compose.waitUntil(10_000) { requests.size == 1 }
            assertFalse(requests.single().getString("initial_command").contains("--effort"))
            discovery.countDown()
            compose.waitUntil(10_000) { models.cached(TaskModelRepository.Key("recovery-mac", TaskAgentCommand.CLAUDE))?.source == TaskModelSource.DISCOVERED }
            completed.complete(Unit)
            waitFor("Refresh Workspaces")
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Create Task").assertIsNotEnabled()
            compose.onNodeWithText("Refresh Workspaces").performClick()
            compose.waitUntil(10_000) { opened != null }
            assertEquals(requests.first().toString(), requests.last().toString())
        } finally { discovery.countDown(); completed.complete(Unit) }
    }

    @Test fun lateCompletedResponseCannotInstallRecoveryOnReplacementConnection() {
        val released = CompletableDeferred<Unit>()
        val active = mutableStateOf(client)
        val replacement = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
        try {
            show(composerClient = { active.value }) { released.await(); missing() }
            compose.waitUntil(10_000) { models.cached(TaskModelRepository.Key("recovery-mac", TaskAgentCommand.CLAUDE)) != null }
            compose.onNodeWithContentDescription("Task prompt").performTextInput("Old client")
            compose.onNodeWithContentDescription("Create Task").performClick()
            compose.waitUntil(10_000) { requests.size == 1 }
            compose.runOnIdle { active.value = replacement }
            compose.waitForIdle(); released.complete(Unit)
            waitFor("Connection changed before the task could be opened.")
            assertNull(drafts.state.value.getValue(id).completedRequest)
            compose.onNodeWithText("Refresh Workspaces").assertDoesNotExist()
            assertNull(opened)
        } finally { released.complete(Unit); replacement.close() }
    }
}
