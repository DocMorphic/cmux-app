package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
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
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalTestApi::class)
class NativeTaskAttachmentsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: NativeCredentialStore
    private lateinit var repository: TaskDraftRepository
    private lateinit var peer: NativeFixturePeer
    private lateinit var client: MobileRpcClient
    private val id = UUID.randomUUID().toString()
    private var completed by mutableStateOf(false)

    @Before fun start() {
        TaskDraftRepository.clearMemory(); TaskDraftRepository.clearAttachments(context)
        store = NativeCredentialStore(context).also { it.clear(); it.update { state -> state.put("refresh_token", "task-attachment-fixture") } }
        repository = runBlocking { TaskDraftRepository.get(context, store.taskSession()!!) }
        peer = NativeFixturePeer()
        client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture" })
        runBlocking { client.connect() }
    }
    @After fun close() {
        compose.activity.finish(); client.close(); peer.close()
        TaskDraftRepository.clearMemory(); TaskDraftRepository.clearAttachments(context); store.clear()
    }
    private fun show(create: suspend (JSONObject) -> JSONObject = { client.request("workspace.create", it) }) {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            NativeTaskComposerView(client, listOf("/repo"), "attachment-mac", TaskModelRepository(),
                onCreated = { completed = true }, onBack = {}, catalog = { awaitCancellation() }, createTask = create,
                savedDrafts = repository.drafts, draftId = id, persistDrafts = repository::persistNow,
                flushDrafts = repository::flush, attachmentRepository = repository, supportsAttachments = true)
        } } }
    }
    private fun choose(file: File, label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
        }, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))), true)
        try {
            compose.onNodeWithText("＋ Attach").performScrollTo().performClick()
            compose.onNodeWithText(label).performClick()
            compose.waitUntil(10_000) { monitor.hits > 0 }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun pickerStagesPhotosAndEmptyFilesThenRetriesWithoutChangingTaskIdentity() {
        var rejected = false
        val sent = mutableListOf<JSONObject>()
        show { params ->
            sent += JSONObject(params.toString())
            if (!rejected) { rejected = true; throw IOException("Fixture rejection") }
            client.request("workspace.create", params)
        }
        val photo = File(context.cacheDir, "task-photo-fixture.png")
        Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.BLUE)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val empty = File(context.cacheDir, "task-empty-fixture.txt").apply { writeBytes(byteArrayOf()) }
        try {
            choose(photo, "Photos")
            compose.waitUntil(15_000) { repository.drafts.state.value[id]?.attachments?.size == 1 }
            val image = repository.drafts.state.value.getValue(id).attachments.single()
            val bytes = runBlocking { repository.readAttachment(image) }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            assertEquals(2048, bounds.outWidth); assertEquals(1024, bounds.outHeight)
            choose(empty, "Files")
            compose.waitUntil(15_000) { repository.drafts.state.value[id]?.attachments?.size == 2 }
            val staged = repository.drafts.state.value.getValue(id).attachments
            assertEquals(0, staged.last().size)
            val saved = TaskDrafts(store.load()!!.getJSONObject("task_drafts")).state.value.getValue(id)
            assertEquals(staged, saved.attachments)
            assertFalse(File(context.noBackupFilesDir, "task-attachments/${image.id}").readBytes().contentEquals(bytes))
            compose.onNodeWithText("Task prompt").performScrollTo().performTextInput("Explain these files")
            compose.onNodeWithText("Create Task").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Fixture rejection", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(staged, repository.drafts.state.value.getValue(id).attachments)
            compose.onNodeWithText("Create Task").performClick()
            compose.waitUntil(15_000) { completed }
            assertEquals(2, sent.size)
            assertEquals(sent[0].getString("operation_id"), sent[1].getString("operation_id"))
            assertFalse(sent[1].has("_cmux_task_attachments"))
            assertEquals("/tmp/cmux fixture.txt\n/tmp/cmux fixture.txt", sent[1].getJSONObject("initial_env").getString("CMUX_TASK_ATTACHMENTS"))
            val uploads = peer.requests.filter { it.optString("method") == "mobile.task.attachment.upload" }.map { it.getJSONObject("params") }
            assertEquals(staged.map { it.id } + staged.map { it.id }, uploads.map { it.getString("upload_id") })
            uploads.forEach { assertEquals(sent[0].getString("operation_id"), it.getString("operation_id")) }
            runBlocking { repository.persistNow() }
            assertTrue(File(context.noBackupFilesDir, "task-attachments").listFiles().orEmpty().isEmpty())
        } finally { photo.delete(); empty.delete() }
    }

    @Test fun encryptedFilesRestoreAndStaleEditorCannotReattachAfterSignOut() = runBlocking {
        val editor = repository.drafts.begin(id, "attachment-mac", "Mac", "/repo")
        val bytes = "Private task attachment".toByteArray()
        val item = ComposerAttachment(name = "private.txt", size = bytes.size)
        repository.attach(editor, AttachmentFiles.Prepared(item, bytes))
        val encrypted = File(context.noBackupFilesDir, "task-attachments/${item.id}")
        assertFalse(String(encrypted.readBytes()).contains("Private task attachment"))
        TaskDraftRepository.clearMemory()
        repository = TaskDraftRepository.get(context, store.taskSession()!!)
        assertEquals(listOf(item), repository.drafts.state.value.getValue(id).attachments)
        assertArrayEquals(bytes, repository.readAttachment(item))
        val resumed = repository.drafts.begin(id, "attachment-mac", "Mac", "/repo")
        repository.removeAttachment(resumed, item.id)
        assertFalse(encrypted.exists())
        repository.attach(resumed, AttachmentFiles.Prepared(item, bytes))
        store.clear(); TaskDraftRepository.clearMemory(); TaskDraftRepository.clearAttachments(context)
        assertTrue(runCatching { repository.attach(resumed, AttachmentFiles.Prepared(item.copy(id = UUID.randomUUID().toString()), bytes)) }.isFailure)
        assertFalse(encrypted.exists())
        assertTrue(runCatching { repository.readAttachment(item) }.isFailure)
    }

    @Test fun previewAndRemovalKeepPromptAndDeleteOnlySelectedAttachment() {
        val editor = repository.drafts.begin(id, "attachment-mac", "Mac", "/repo")
        val bytes = "Preview fixture".toByteArray()
        val first = ComposerAttachment(name = "preview.txt", size = bytes.size)
        val second = ComposerAttachment(name = "keep.txt", size = bytes.size)
        runBlocking { repository.attach(editor, AttachmentFiles.Prepared(first, bytes)); repository.attach(editor, AttachmentFiles.Prepared(second, bytes)) }
        repository.drafts.edit(editor) { it.copy(prompt = "Keep this prompt") }
        show()
        compose.onNodeWithText("preview.txt").performScrollTo().performClick()
        compose.onNodeWithText("${bytes.size} bytes").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Remove task attachment: preview.txt").performScrollTo().performClick()
        compose.waitUntil(10_000) { repository.drafts.state.value[id]?.attachments == listOf(second) }
        assertEquals("Keep this prompt", repository.drafts.state.value.getValue(id).prompt)
        assertFalse(File(context.noBackupFilesDir, "task-attachments/${first.id}").exists())
        assertArrayEquals(bytes, runBlocking { repository.readAttachment(second) })
    }
}
