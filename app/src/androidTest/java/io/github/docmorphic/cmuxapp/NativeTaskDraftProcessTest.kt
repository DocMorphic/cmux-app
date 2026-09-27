package io.github.docmorphic.cmuxapp

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
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
import java.util.UUID

/** Run in two separate instrumentation processes with -e draftPhase seed, then verify. */
@OptIn(ExperimentalTestApi::class)
class NativeTaskDraftProcessTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())

    @Test fun interruptedTaskRetainsItsRequestAcrossProcessDeath() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val phase = InstrumentationRegistry.getArguments().getString("draftPhase")
        val completedRecovery = InstrumentationRegistry.getArguments().getString("completedRecovery") == "true"
        Assume.assumeTrue("Requires explicit seed/verify process phases", phase == "seed" || phase == "verify")
        val context = instrumentation.targetContext
        val marker = context.getSharedPreferences("task_draft_process_fixture", Context.MODE_PRIVATE)
        val store = NativeCredentialStore(context)
        TaskDraftRepository.clearMemory()
        if (phase == "seed") {
            store.clear()
            store.update { it.put("refresh_token", "process-draft-fixture") }
            marker.edit().clear().commit()
        } else assertNotEquals("Verification must run in a fresh app process", marker.getInt("pid", -1), Process.myPid())
        val repository = runBlocking(Dispatchers.IO) { TaskDraftRepository.get(context, store.taskSession()!!) }
        val id = if (phase == "seed") UUID.randomUUID().toString() else marker.getString("id", null)!!
        val peer = NativeFixturePeer()
        val client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
        val models = TaskModelRepository()
        var submitted: JSONObject? = null
        var completed = false
        try {
            runBlocking { client.connect() }
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().background(Color(0xFF0B0C0E))
                .statusBarsPadding().navigationBarsPadding().imePadding()) {
                NativeTaskComposerView(client, listOf("/repo"), "process-mac", models,
                    onCreated = { completed = true }, onBack = {}, catalog = { awaitCancellation() },
                    savedDrafts = repository.drafts, draftId = id, macName = "Process Fixture Mac",
                    persistDrafts = repository::persistNow, flushDrafts = repository::flush,
                    createTask = { params ->
                        submitted = JSONObject(params.toString())
                        val durable = TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value.getValue(id)
                        val durableRequest = if (completedRecovery && phase == "verify") durable.completedRequest else durable.lastRequest
                        assertEquals(params.getString("operation_id"), JSONObject(durableRequest!!).getString("operation_id"))
                        if (phase == "seed" && completedRecovery) throw MobileRpcException("already_completed", "Completed fixture")
                        else if (phase == "seed") withTimeout(1) { awaitCancellation() }
                        else client.request("workspace.create", params)
                    })
            } } }
            fun state(label: String, value: String) {
                val matcher = hasContentDescription(label) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
                compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
            }
            if (phase == "seed") {
                state("Effort", "High")
                compose.onNodeWithText("Codex", useUnmergedTree = true).performClick()
                compose.waitUntil(10_000) { models.cached(TaskModelRepository.Key("process-mac", TaskAgentCommand.CODEX))?.source == TaskModelSource.DISCOVERED }
                state("Effort", "High")
                compose.onNodeWithContentDescription("Model").performClick()
                compose.onNodeWithText("Local codex").performClick()
                compose.onNodeWithContentDescription("Effort").performClick()
                compose.onNodeWithText("Low").performClick()
                compose.onNodeWithText("Task prompt").performTextInput("Recover this task 中\nKeep my 'quotes'")
            } else {
                state("Model", "Local codex"); state("Effort", "Low")
                compose.onNodeWithText("Task prompt").assertTextContains("Recover this task 中\nKeep my 'quotes'")
            }
            if (phase == "verify" && completedRecovery) {
                compose.onNodeWithText("Create Task").assertIsNotEnabled()
                compose.onNodeWithText("Start Again").assertDoesNotExist()
                val loaded = repository.drafts.state.value.getValue(id)
                assertNotEquals(JSONObject(loaded.completedRequest!!).getString("operation_id"), JSONObject(loaded.lastRequest!!).getString("operation_id"))
                compose.onNodeWithText("Refresh Workspaces").performClick()
            } else compose.onNodeWithText("Create Task").performClick()
            if (phase == "seed") {
                compose.waitUntil(10_000) { compose.onAllNodesWithText(if (completedRecovery) "Refresh Workspaces" else "Check your workspace list before retrying.",
                    substring = true).fetchSemanticsNodes().isNotEmpty() }
                runBlocking { repository.persistNow() }
                assertTrue(marker.edit().putInt("pid", Process.myPid()).putString("id", id)
                    .putString("operation", submitted!!.getString("operation_id"))
                    .putString("command", submitted!!.getString("initial_command")).commit())
            } else {
                compose.waitUntil(10_000) { completed }
                assertEquals(marker.getString("operation", null), submitted!!.getString("operation_id"))
                assertEquals(marker.getString("command", null), submitted!!.getString("initial_command"))
                runBlocking { repository.persistNow() }
                assertTrue(TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value.isEmpty())
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("draft_phase", phase); putInt("draft_process_id", Process.myPid()); putBoolean("completed_recovery", completedRecovery) })
        } finally {
            compose.activity.finish(); client.close(); peer.close(); TaskDraftRepository.clearMemory()
            if (phase == "verify") { store.clear(); marker.edit().clear().commit() }
        }
    }
}
