package io.github.docmorphic.cmuxapp

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
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalTestApi::class)
class NativeTaskTemplatesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: NativeCredentialStore
    private lateinit var repository: TaskDraftRepository
    private lateinit var peer: NativeFixturePeer
    private lateinit var client: MobileRpcClient
    private val models = TaskModelRepository()
    private var generation by mutableIntStateOf(0)
    private var draftId by mutableStateOf(UUID.randomUUID().toString())
    private var failSave = false
    private var request: JSONObject? = null

    @Before fun start() {
        TaskDraftRepository.clearMemory()
        store = NativeCredentialStore(context).also { it.clear(); it.update { state -> state.put("refresh_token", "template-fixture") } }
        repository = runBlocking(Dispatchers.IO) { TaskDraftRepository.get(context, store.taskSession()!!) }
        peer = NativeFixturePeer()
        client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
        runBlocking { client.connect() }
        compose.runOnUiThread { compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
    }
    @After fun close() { compose.activity.finish(); client.close(); peer.close(); TaskDraftRepository.clearMemory(); store.clear() }
    private fun show() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().background(Color(0xFF0B0C0E))
            .statusBarsPadding().navigationBarsPadding().imePadding()) {
            key(generation) { NativeTaskComposerView(client, listOf("/open-project"), "template-mac", models, {}, {},
                catalog = { awaitCancellation() }, savedDrafts = repository.drafts, draftId = draftId,
                savedTemplates = repository.templates, persistDrafts = repository::persistNow, flushDrafts = repository::flush,
                persistTemplateChange = { if (failSave) throw IOException("Template save failed"); repository.updateTemplates(it) },
                createTask = { params -> request = JSONObject(params.toString()); client.request("workspace.create", params) }) }
        } } }
    }
    private fun waitFor(text: String) { compose.waitUntil(10_000) { compose.onAllNodes(hasText(text) or hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty() } }
    private fun agent(name: String) {
        val matcher = hasContentDescription("Agent") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, name)
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun editors() { compose.openTaskPicker("Agent"); compose.onNodeWithText("Edit Agents").performClick(); waitFor("Task Templates") }
    private fun add(name: String, command: String = "", directory: String = "") {
        compose.onNodeWithContentDescription("Add Template").performClick()
        compose.onNodeWithText("Name").performTextInput(name)
        if (command.isNotEmpty()) compose.onNodeWithText("Command").performTextInput(command)
        if (directory.isNotEmpty()) compose.onNodeWithText("Default directory").performTextInput(directory)
    }
    private fun save() { compose.onNodeWithText("Save").performClick(); waitFor("Task Templates") }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun customScriptEmojiAndDirectoryReachRpcAndEncryptedSuccessfulDefaults() {
        show(); agent("Claude"); editors()
        val script = "printf '%s\\n' \"\$CMUX_TASK_PROMPT\"\nprintf 'done'"
        add("Review 中", script, "/custom project")
        compose.onNodeWithText("Custom emoji").performTextInput("👩🏽‍💻other")
        screenshot("task-template-form")
        save()
        compose.onNodeWithText("Done").performClick(); agent("Review 中")
        editors(); screenshot("task-template-list")
        compose.onNodeWithText("Done").performClick(); agent("Review 中")
        compose.onNodeWithContentDescription("Model").assertDoesNotExist()
        compose.assertTaskDirectory("/custom project")
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Keep 'quotes' and 中")
        compose.onNodeWithContentDescription("Create Task").performClick()
        waitFor("Task Created")
        assertEquals(script, request!!.getString("initial_command"))
        assertEquals("Keep 'quotes' and 中", request!!.getJSONObject("initial_env").getString("CMUX_TASK_PROMPT"))
        runBlocking { repository.persistNow() }
        val restored = TaskTemplates(store.load()!!.getJSONObject("task_drafts").getJSONObject("templates")).state.value
        val template = restored.selected()
        assertEquals("Review 中", template.name); assertEquals("👩🏽‍💻", template.icon)
        assertEquals("template-mac", restored.lastOrigin)
        assertEquals("/custom project", restored.recent.getValue("template-mac").first().path)
        assertTrue(TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value.isEmpty())
    }

    @Test fun freshTaskRestoresPickersWithoutRestoringPreviousPrompt() {
        show(); agent("Claude")
        compose.chooseTaskDirectory(peer, "/remember this Mac")
        compose.openTaskPicker("Agent"); compose.onNodeWithText("Codex").performClick()
        agent("Codex")
        compose.waitUntil(10_000) { models.cached(TaskModelRepository.Key("template-mac", TaskAgentCommand.CODEX))?.source == TaskModelSource.DISCOVERED }
        compose.openTaskPicker("Model"); compose.onNodeWithText("Local codex").performClick()
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Keep only in this draft")
        runBlocking { repository.persistNow() }
        val firstId = draftId
        compose.runOnIdle { draftId = UUID.randomUUID().toString(); generation++ }
        agent("Codex")
        compose.assertTaskDirectory("/remember this Mac")
        compose.onNodeWithContentDescription("Task prompt").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithContentDescription("Model").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Local codex"))
        assertEquals("Keep only in this draft", repository.drafts.state.value.getValue(firstId).prompt)
        compose.waitUntil(10_000) { android.view.inspector.WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus() } }
        screenshot("task-pickers-fresh")
    }

    @Test fun durableMacSwitchRestoresTargetChoicesAndRejectsRetiredEditor() = runBlocking {
        val template = repository.templates.state.value.entries[1]
        val id = UUID.randomUUID().toString()
        val first = repository.drafts.begin(id, "mac-stable", "Stable", "/stable")
        repository.drafts.edit(first) { it.selecting(template, "/stable").copy(prompt = "Task",
            directory = "/stable typed", didEditDirectory = true, groupId = "stable-group") }
        repository.selectMac(first, "mac-nightly", "Nightly", "/nightly")
        val moved = repository.drafts.state.value.getValue(id)
        assertEquals("/nightly", moved.directory); assertFalse(moved.didEditDirectory); assertNull(moved.groupId)
        assertFalse(repository.drafts.isCurrent(first))
        assertNull(repository.drafts.editIfCurrent(first) { it.copy(directory = "/late callback") })
        val next = repository.drafts.begin(id, "mac-nightly", "Nightly", "/nightly")
        repository.drafts.edit(next) { it.copy(directory = "/nightly typed", didEditDirectory = true) }
        repository.selectMac(next, "mac-stable", "Stable", null)
        val restored = TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value.getValue(id)
        assertEquals("/stable typed", restored.directory); assertEquals("stable-group", restored.groupId)
        assertEquals("Task", restored.prompt)
        val choices = TaskTemplates(store.load()!!.getJSONObject("task_drafts").getJSONObject("templates")).state.value
        assertEquals("/nightly typed", choices.pickers.getValue("mac-nightly").directory)
        assertEquals("/stable typed", choices.pickers.getValue("mac-stable").directory)
    }

    @Test fun firstHandshakeKeepsInputAndFailedOwnerChangePublishesNothing() = runBlocking {
        val id = UUID.randomUUID().toString()
        val editor = repository.drafts.begin(id, "unresolved-code", "Mac", "~")
        repository.drafts.edit(editor) { it.selecting(repository.templates.state.value.entries.first(), "~")
            .copy(prompt = "During pairing", directory = "/typed while connecting", didEditDirectory = true) }
        repository.selectMac(editor, "verified-mac", "Verified", "/host", adoptingIdentity = true)
        val adopted = repository.drafts.state.value.getValue(id)
        assertEquals("/typed while connecting", adopted.directory); assertTrue(adopted.didEditDirectory)
        assertFalse(repository.templates.state.value.pickers.containsKey("unresolved-code"))
        val next = repository.drafts.begin(id, "verified-mac", "Verified", "/host")
        val before = repository.templates.state.value
        store.update { it.put("task_session", UUID.randomUUID().toString()) }
        try {
            repository.selectMac(next, "other", "Other", "/other")
            fail("A replaced account must reject the durable owner change")
        } catch (_: IllegalStateException) { }
        assertEquals(adopted, repository.drafts.state.value.getValue(id))
        assertEquals(before, repository.templates.state.value)
        assertTrue(repository.drafts.isCurrent(next))
    }

    @Test fun renamedBuiltInStaysProtectedAndCustomPlainShellCanBeDeleted() {
        show(); agent("Claude"); editors()
        compose.onNodeWithText("Claude").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Claude Local")
        compose.onNodeWithText("Default directory").performTextInput("/templates/claude")
        save()
        compose.onNodeWithContentDescription("Delete template: Claude Local").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick(); agent("Claude Local")
        compose.assertTaskDirectory("/templates/claude")
        editors(); add("Scratch", " \n ")
        save(); compose.onNodeWithText("Done").performClick(); agent("Scratch")
        compose.onNodeWithContentDescription("Model").assertDoesNotExist()
        compose.onNodeWithContentDescription("Workspace title (optional)").assertIsDisplayed()
        compose.onNodeWithContentDescription("Create Task").assertIsEnabled()
        editors()
        compose.onNodeWithContentDescription("Delete template: Scratch").performClick()
        compose.waitUntil(10_000) { repository.templates.state.value.entries.none { it.name == "Scratch" } }
        compose.onNodeWithText("Done").performClick(); agent("Claude Local")
        assertEquals(TaskCommand.Agent.CLAUDE, repository.templates.state.value.entries.first().builtInKind)
    }

    @Test fun typedDirectoryAndSelectedTemplateSurviveComposerRecreation() {
        show(); agent("Claude")
        compose.chooseTaskDirectory(peer, "/my typed path")
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Retain this template draft")
        editors(); add("My Codex", "codex --full-auto -- \"\$CMUX_TASK_PROMPT\"", "/ignored-default")
        save(); compose.onNodeWithText("Done").performClick(); agent("My Codex")
        compose.assertTaskDirectory("/my typed path")
        compose.waitUntil(10_000) { models.cached(TaskModelRepository.Key("template-mac", TaskAgentCommand.CODEX))?.source == TaskModelSource.DISCOVERED }
        compose.openTaskPicker("Model"); compose.onNodeWithText("Local codex").performClick()
        runBlocking { repository.persistNow() }
        val stored = TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value.getValue(draftId)
        assertEquals("My Codex", stored.templateName); assertTrue(stored.didEditDirectory)
        assertEquals("codex-live", stored.selection.explicit?.id)
        compose.runOnIdle { generation++ }
        agent("My Codex")
        compose.assertTaskDirectory("/my typed path")
        compose.onNodeWithContentDescription("Task prompt").assertTextContains("Retain this template draft")
        compose.onNodeWithContentDescription("Model").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Local codex"))
        screenshot("task-template-restored")
    }

    @Test fun failedSaveRetainsFormAndOldAccountCannotWriteTemplates() {
        show(); agent("Claude"); editors(); add("Keep unsaved name", "echo \"\$CMUX_TASK_PROMPT\"")
        failSave = true
        compose.onNodeWithText("Save").performClick(); waitFor("Template save failed")
        compose.onNodeWithText("Name").assertTextContains("Keep unsaved name")
        assertEquals(4, repository.templates.state.value.entries.size)
        failSave = false; save()
        assertEquals(5, repository.templates.state.value.entries.size)
        compose.onNodeWithText("Done").performClick()
        compose.activity.finish()
        val previous = repository
        NativeAccount(store).signOut()
        store.update { it.put("refresh_token", "new-template-account").put("task_session", UUID.randomUUID().toString()) }
        val fresh = runBlocking(Dispatchers.IO) { TaskDraftRepository.get(context, store.taskSession()!!) }
        val failure = runCatching { runBlocking { previous.updateTemplates(TaskTemplateChange.Save(TaskTemplate(UUID.randomUUID().toString(), "Stale"), true)) } }.exceptionOrNull()
        assertNotNull(failure)
        runBlocking { fresh.persistNow() }
        assertEquals(4, TaskTemplates(store.load()!!.getJSONObject("task_drafts").getJSONObject("templates")).state.value.entries.size)
    }
}
