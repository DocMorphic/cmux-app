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
        val customTemplate = InstrumentationRegistry.getArguments().getString("customTemplate") == "true"
        val attachments = InstrumentationRegistry.getArguments().getString("taskAttachments") == "true"
        val destination = InstrumentationRegistry.getArguments().getString("taskDestination") == "true"
        Assume.assumeTrue("Requires explicit seed/verify process phases", phase == "seed" || phase == "verify")
        val context = instrumentation.targetContext
        val marker = context.getSharedPreferences("task_draft_process_fixture", Context.MODE_PRIVATE)
        val store = NativeCredentialStore(context)
        TaskDraftRepository.clearMemory()
        if (phase == "seed") {
            store.clear(); TaskDraftRepository.clearAttachments(context)
            store.update { it.put("refresh_token", "process-draft-fixture") }
            marker.edit().clear().commit()
        } else assertNotEquals("Verification must run in a fresh app process", marker.getInt("pid", -1), Process.myPid())
        val repository = runBlocking(Dispatchers.IO) { TaskDraftRepository.get(context, store.taskSession()!!) }
        if (phase == "seed" && customTemplate) runBlocking {
            repository.updateTemplates(TaskTemplateChange.Save(TaskTemplate(UUID.randomUUID().toString(), "Custom process Codex",
                "agent:codex", "codex --full-auto -- \"\$CMUX_TASK_PROMPT\"", "/cold-template"), true))
        }
        val id = if (phase == "seed") UUID.randomUUID().toString() else marker.getString("id", null)!!
        val attachmentBytes = "Task file surviving process death 中".toByteArray()
        if (attachments) {
            if (phase == "seed") {
                val editor = repository.drafts.begin(id, "process-mac", "Process Fixture Mac", "/repo")
                val item = ComposerAttachment(name = "process.txt", size = attachmentBytes.size)
                runBlocking { repository.attach(editor, AttachmentFiles.Prepared(item, attachmentBytes)) }
                marker.edit().putString("attachment", item.id).commit()
                repository.drafts.end(editor)
            } else {
                val item = repository.drafts.state.value.getValue(id).attachments.single()
                assertEquals(marker.getString("attachment", null), item.id)
                assertArrayEquals(attachmentBytes, runBlocking { repository.readAttachment(item) })
            }
        }
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
                    attachmentRepository = repository, supportsAttachments = true,
                    savedTemplates = repository.templates, persistTemplateChange = repository::updateTemplates,
                    supportsGroups = true, workspaceGroups = listOf(NativeGroup("process-group", "Process group", false, false)),
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
                compose.openTaskPicker("Agent")
                compose.onNodeWithText(if (customTemplate) "Custom process Codex" else "Codex", useUnmergedTree = true).performClick()
                compose.waitUntil(10_000) { models.cached(TaskModelRepository.Key("process-mac", TaskAgentCommand.CODEX))?.source == TaskModelSource.DISCOVERED }
                state("Effort", "High")
                compose.openTaskPicker("Model")
                compose.onNodeWithText("Local codex").performClick()
                compose.openTaskPicker("Effort")
                compose.onNodeWithText("Low").performClick()
                compose.onNodeWithContentDescription("Task prompt").performTextInput("Recover this task 中\nKeep my 'quotes'")
                if (destination) {
                    compose.onNodeWithContentDescription("Task Options").performClick()
                    compose.onNodeWithText("Workspace name (optional)").performTextInput("Cold named task 👩🏽‍💻")
                    compose.onNodeWithContentDescription("Workspace group").performClick()
                    compose.onNodeWithText("Process group").performClick()
                    compose.onNodeWithText("Done").performClick()
                }
            } else {
                if (customTemplate) {
                    state("Agent", "Custom process Codex")
                    compose.assertTaskDirectory("/cold-template")
                }
                state("Model", "Local codex"); state("Effort", "Low")
                compose.onNodeWithContentDescription("Task prompt").assertTextContains("Recover this task 中\nKeep my 'quotes'")
                if (destination) {
                    val loaded = repository.drafts.state.value.getValue(id)
                    assertEquals("Cold named task 👩🏽‍💻", loaded.workspaceName)
                    assertEquals("process-group", loaded.groupId)
                }
            }
            if (phase == "verify" && completedRecovery) {
                compose.onNodeWithContentDescription("Create Task").assertIsNotEnabled()
                compose.onNodeWithText("Start Again").assertDoesNotExist()
                val loaded = repository.drafts.state.value.getValue(id)
                assertNotEquals(JSONObject(loaded.completedRequest!!).getString("operation_id"), JSONObject(loaded.lastRequest!!).getString("operation_id"))
                compose.onNodeWithText("Refresh Workspaces").performClick()
            } else compose.onNodeWithContentDescription("Create Task").performClick()
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
                if (destination) { assertEquals("Cold named task 👩🏽‍💻", submitted!!.getString("title")); assertEquals("process-group", submitted!!.getString("group_id")) }
                runBlocking { repository.persistNow() }
                assertTrue(TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value.isEmpty())
                if (customTemplate) assertEquals("Custom process Codex", repository.templates.state.value.selected().name)
            }
            if (attachments) {
                val uploads = peer.requests.filter { it.optString("method") == "mobile.task.attachment.upload" }
                if (phase == "verify" && completedRecovery) {
                    assertTrue(uploads.isEmpty())
                    assertFalse(submitted!!.getJSONObject("initial_env").has("CMUX_TASK_ATTACHMENTS"))
                } else {
                    val sent = uploads.single().getJSONObject("params")
                    assertEquals(marker.getString("attachment", null), sent.getString("upload_id"))
                    assertEquals(submitted!!.getString("operation_id"), sent.getString("operation_id"))
                    assertArrayEquals(attachmentBytes, java.util.Base64.getDecoder().decode(sent.getString("data_b64")))
                    assertEquals("/tmp/cmux fixture.txt", submitted!!.getJSONObject("initial_env").getString("CMUX_TASK_ATTACHMENTS"))
                }
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("draft_phase", phase); putInt("draft_process_id", Process.myPid()); putBoolean("completed_recovery", completedRecovery); putBoolean("custom_template", customTemplate); putBoolean("task_destination", destination); putBoolean("task_attachments", attachments) })
        } finally {
            compose.activity.finish(); client.close(); peer.close(); TaskDraftRepository.clearMemory()
            if (phase == "verify") { TaskDraftRepository.clearAttachments(context); store.clear(); marker.edit().clear().commit() }
        }
    }
}
