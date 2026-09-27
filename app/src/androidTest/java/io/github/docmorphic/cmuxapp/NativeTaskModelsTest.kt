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
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CountDownLatch

@OptIn(ExperimentalTestApi::class)
class NativeTaskModelsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var peer: NativeFixturePeer
    private lateinit var client: MobileRpcClient
    private val repo = TaskModelRepository()
    @Before fun start() {
        peer = NativeFixturePeer()
        client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "test-only" })
        runBlocking { client.connect() }
        compose.runOnUiThread { compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
    }
    @After fun close() { peer.releaseTaskModels?.countDown(); compose.activity.finish(); client.close(); peer.close() }
    private fun show(createTask: (suspend (JSONObject) -> JSONObject)? = null,
        onCreated: (JSONObject) -> Unit = {}, isCurrent: () -> Boolean = { true },
        composerClient: () -> MobileRpcClient = { client },
        catalog: suspend (TaskAgentCommand) -> TaskModelResult = { awaitCancellation() }) {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().background(Color(0xFF0B0C0E))
            .statusBarsPadding().navigationBarsPadding().imePadding()) {
            NativeTaskComposerView(composerClient(), listOf("/tmp/project"), "fixture-mac", repo, onCreated, {}, catalog,
                createTask ?: { client.request("workspace.create", it, timeoutMillis = 30_000) }, isCurrent)
        } } }
    }
    private fun state(label: String, value: String) {
        val matcher = hasContentDescription(label) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun created(): JSONObject {
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "workspace.create" } }
        return peer.requests.single { it.optString("method") == "workspace.create" }.getJSONObject("params")
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val path = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(path, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun modelAndEffortReachCreateTaskWhilePromptRemainsData() {
        show(); state("Model", "Default"); state("Effort", "High")
        compose.onNodeWithText("Codex", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { repo.cached(TaskModelRepository.Key("fixture-mac", TaskAgentCommand.CODEX))?.source == TaskModelSource.DISCOVERED }
        state("Effort", "High")
        compose.onNodeWithContentDescription("Model").performClick()
        compose.onNodeWithText("Local codex").performClick()
        state("Model", "Local codex")
        compose.onNodeWithContentDescription("Effort").performClick()
        compose.onNodeWithText("Low").performClick()
        state("Effort", "Low")
        screenshot("task-model-options")
        val prompt = "Fix 'quotes'\nand Unicode 中"
        compose.onNodeWithText("Task prompt").performTextInput(prompt)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(prompt)).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { compose.activity.window.decorView.clearFocus() }
        screenshot("task-model-effort")
        compose.onNodeWithText("Create Task").performClick()
        val params = created()
        assertEquals("codex -c model_reasoning_effort='low' -m 'codex-live' -- \"\$CMUX_TASK_PROMPT\"", params.getString("initial_command"))
        assertEquals("Fix 'quotes'\nand Unicode 中", params.getJSONObject("initial_env").getString("CMUX_TASK_PROMPT"))
        assertEquals("/tmp/project", params.getString("working_directory"))
        assertTrue(params.getString("operation_id").isNotEmpty())
    }

    @Test fun openMenuKeepsBackendChoiceAcrossAuthoritativeHostReplacement() {
        val release = CountDownLatch(1); peer.releaseTaskModels = release
        val snapshot = TaskModel("preview-model", "Catalog preview", listOf(TaskEffort("quick", "Quick")), "quick")
        show { TaskModelResult(listOf(snapshot), TaskModelSource.BACKEND) }
        compose.waitUntil(10_000) { repo.cached(TaskModelRepository.Key("fixture-mac", TaskAgentCommand.CLAUDE))?.source == TaskModelSource.BACKEND }
        compose.onNodeWithContentDescription("Model").performClick()
        compose.onNodeWithText("Catalog preview").assertIsDisplayed()
        release.countDown()
        compose.waitUntil(10_000) { repo.cached(TaskModelRepository.Key("fixture-mac", TaskAgentCommand.CLAUDE))?.source == TaskModelSource.DISCOVERED }
        compose.onNodeWithText("Catalog preview").assertIsDisplayed()
        compose.onNodeWithText("Local claude").assertDoesNotExist()
        compose.onNodeWithText("Catalog preview").performClick()
        state("Model", "Catalog preview"); state("Effort", "Quick")
        compose.onNodeWithContentDescription("Model").performClick()
        compose.onNodeWithText("Local claude").assertIsDisplayed()
        compose.onNodeWithText("Default").performClick()
        state("Model", "Default"); state("Effort", "High")
        compose.onNodeWithText("Task prompt").performTextInput("Use the Mac default")
        compose.onNodeWithText("Create Task").performClick()
        assertEquals("claude --effort 'high' -- \"\$CMUX_TASK_PROMPT\"", created().getString("initial_command"))
    }

    @Test fun olderHostUsesCatalogWithoutInventingEfforts() {
        peer.taskModelErrorCode = "unsupported_method"
        val backend = TaskModel("catalog-model", "Catalog model")
        show { TaskModelResult(listOf(backend), TaskModelSource.BACKEND, backend) }
        compose.waitUntil(10_000) { repo.cached(TaskModelRepository.Key("fixture-mac", TaskAgentCommand.CLAUDE))?.usable == true }
        compose.onNodeWithContentDescription("Model").performClick()
        compose.onNodeWithText("Catalog model").performClick()
        state("Model", "Catalog model")
        state("Effort", "Default")
        compose.onNodeWithContentDescription("Effort").assertIsNotEnabled()
        compose.onNodeWithText("Task prompt").performTextInput("Use a catalog model")
        compose.onNodeWithText("Create Task").performClick()
        assertEquals("claude --model 'catalog-model' -- \"\$CMUX_TASK_PROMPT\"", created().getString("initial_command"))
        assertEquals(1, peer.requests.count { it.optString("method") == "mobile.task.models.list" })
    }

    @Test fun shellDoesNotOfferModelControlsOrStartAnAgent() {
        show(); state("Effort", "High")
        compose.onNodeWithText("Shell", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Workspace title (optional)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Model").assertDoesNotExist()
        compose.onNodeWithContentDescription("Effort").assertDoesNotExist()
        compose.onNodeWithText("Create Task").performClick()
        val params = created()
        assertFalse(params.has("initial_command")); assertFalse(params.has("initial_env"))
    }

    @Test fun createTimeoutKeepsPromptAndRequiresExplicitRetryWithSameOperation() {
        val attempts = mutableListOf<JSONObject>()
        show(createTask = { params ->
            attempts += JSONObject(params.toString())
            withTimeout(1) { awaitCancellation() }
        })
        state("Effort", "High")
        compose.onNodeWithText("Task prompt").performTextInput("Keep this draft")
        compose.onNodeWithText("Create Task").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Check your workspace list before retrying.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Create Task").assertIsEnabled()
        compose.onNodeWithText("Task prompt").assertTextContains("Keep this draft")
        assertEquals(1, attempts.size)
        compose.onNodeWithText("Create Task").performClick()
        compose.waitUntil(10_000) { attempts.size == 2 }
        assertEquals(attempts[0].getString("operation_id"), attempts[1].getString("operation_id"))
        assertEquals(attempts[0].getString("initial_command"), attempts[1].getString("initial_command"))
    }

    @Test fun incompleteCreateResponseKeepsDraftAndEquivalentRetryIdentity() {
        val attempts = mutableListOf<JSONObject>()
        var navigations = 0
        show(createTask = { params ->
            attempts += JSONObject(params.toString())
            JSONObject().put("created_workspace_id", "missing")
        }, onCreated = { navigations++ })
        state("Effort", "High")
        compose.onNodeWithText("Task prompt").performTextInput("Keep this task")
        compose.onNodeWithText("Create Task").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Mac did not return the created task workspace",
            substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Task prompt").assertTextContains("Keep this task")
        assertEquals(0, navigations)
        compose.onNodeWithText("Task prompt").performTextReplacement("  Keep this task  ")
        compose.onNodeWithText("Create Task").performClick()
        compose.waitUntil(10_000) { attempts.size == 2 }
        assertEquals(attempts[0].getString("operation_id"), attempts[1].getString("operation_id"))
        compose.onNodeWithText("Task prompt").performTextReplacement("Create a different task")
        compose.onNodeWithText("Create Task").performClick()
        compose.waitUntil(10_000) { attempts.size == 3 }
        assertNotEquals(attempts[0].getString("operation_id"), attempts[2].getString("operation_id"))
        assertEquals(0, navigations)
        screenshot("task-create-rejected")
    }

    @Test fun lateCreateResponseCannotNavigateAfterConnectionReplacement() {
        val release = CompletableDeferred<Unit>()
        val current = java.util.concurrent.atomic.AtomicBoolean(true)
        val active = mutableStateOf(client)
        val replacement = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "test-only" })
        runBlocking { replacement.connect() }
        try {
        var attempts = 0
        var navigations = 0
        show(createTask = {
            attempts++
            release.await()
            JSONObject("""{"created_workspace_id":"created","workspaces":[{"id":"created"}]}""")
        }, onCreated = { navigations++ }, isCurrent = current::get, composerClient = { active.value })
        state("Effort", "High")
        compose.onNodeWithText("Task prompt").performTextInput("Stay on the intended Mac")
        compose.onNodeWithText("Create Task").performClick()
        compose.waitUntil(10_000) { attempts == 1 }
        compose.runOnIdle { active.value = replacement }
        compose.waitForIdle()
        release.complete(Unit)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Connection changed before the task could be opened",
            substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, navigations)
        compose.onNodeWithText("Task prompt").assertTextContains("Stay on the intended Mac")
        current.set(false)
        compose.onNodeWithText("Create Task").performClick()
        compose.onNodeWithText("Connection changed. Reconnect to this Mac before creating the task").assertIsDisplayed()
        assertEquals(1, attempts)
        } finally { release.complete(Unit); replacement.close() }
    }
}
