package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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
    @Volatile private var keyboardRequest: androidx.compose.ui.platform.PlatformTextInputMethodRequest? = null

    @Before fun start() {
        compose.activity.runOnUiThread {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        TaskDraftRepository.clearMemory(); TaskDraftRepository.clearAttachments(context)
        store = NativeCredentialStore(context).also { it.clear(); it.update { state -> state.put("refresh_token", "task-attachment-fixture") } }
        repository = runBlocking { TaskDraftRepository.get(context, store.taskSession()!!) }
        peer = NativeFixturePeer()
        client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture" })
        // Discover the host's authenticated workspace-mutation capability, as the
        // production connection does before offering task creation.
        runBlocking { client.connect(); client.hostStatus() }
    }
    @After fun close() {
        compose.activity.finish(); client.close(); peer.close()
        TaskDraftRepository.clearMemory(); TaskDraftRepository.clearAttachments(context); store.clear()
    }
    private fun show(create: suspend (JSONObject) -> JSONObject = { client.request("workspace.create", it) }) {
        compose.setContent { CaptureComposerInput({ keyboardRequest = it }) { CmuxTheme { Surface(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0B0C0E)).statusBarsPadding().navigationBarsPadding().imePadding()) {
            NativeTaskComposerView(client, listOf("/repo"), "attachment-mac", remember { TaskModelRepository() },
                onCreated = { completed = true }, onBack = {}, catalog = { awaitCancellation() }, createTask = create,
                savedDrafts = repository.drafts, draftId = id, persistDrafts = repository::persistNow,
                flushDrafts = repository::flush, attachmentRepository = repository, supportsAttachments = true)
        } } } }
    }

    @Test fun keyboardImageStagesWithPromptAndUploadsWhenTaskIsCreated() = imageStagesAndUploads(false)
    @Test fun systemPasteImageStagesWithPromptAndUploadsWhenTaskIsCreated() = imageStagesAndUploads(true)
    @Test fun systemPasteKeepsReadableFilesAroundBrokenProviders() = mixedProviderPaste(true)
    @Test fun attachmentMenuKeepsReadableFilesAroundBrokenProviders() = mixedProviderPaste(false)

    @Test fun overflowingClipboardUsesRemainingSlotsAndFullDraftStillAllowsTextPaste() {
        seedAttachments(8)
        show()
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Keep this prompt")
        compose.waitUntil(10_000) { keyboardRequest != null }
        val directory = File(context.cacheDir, "task-previews").apply { mkdirs() }
        val files = List(12) { index -> File(directory, "selection-$id-$index.txt").apply { writeText("File $index") } }
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        val previous = clipboard.primaryClip
        try {
            val uris = files.map { androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.task-previews", it) }
            val clip = android.content.ClipData.newUri(context.contentResolver, "Task files", uris.first())
            uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
            clip.addItem(android.content.ClipData.Item("This caption must not enter the prompt"))
            compose.runOnIdle { clipboard.setPrimaryClip(clip) }
            compose.onNodeWithContentDescription("Add task attachment").performClick()
            compose.onNodeWithText("Paste attachment").performClick()
            compose.waitUntil(15_000) { repository.drafts.state.value[id]?.attachments?.size == 10 }
            val selected = repository.drafts.state.value.getValue(id)
            assertEquals("Keep this prompt", selected.prompt)
            assertEquals(files.take(2).map { it.name }, selected.attachments.takeLast(2).map { it.name })
            selected.attachments.takeLast(2).zip(files).forEach { (attachment, file) ->
                assertArrayEquals(file.readBytes(), runBlocking { repository.readAttachment(attachment) })
            }
            compose.onNodeWithContentDescription("Task prompt").performClick()
            compose.waitUntil(10_000) { keyboardRequest != null }
            compose.runOnIdle {
                val connection = keyboardRequest!!.createInputConnection(android.view.inputmethod.EditorInfo())
                assertTrue(connection.performContextMenuAction(android.R.id.paste))
            }
            compose.onNodeWithText(TaskAttachments.COUNT_MESSAGE).assertExists()
            assertEquals(selected.attachments, repository.drafts.state.value.getValue(id).attachments)
            assertEquals("Keep this prompt", repository.drafts.state.value.getValue(id).prompt)
            compose.runOnIdle {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Text", " plus ordinary text"))
                val connection = keyboardRequest!!.createInputConnection(android.view.inputmethod.EditorInfo())
                assertTrue(connection.performContextMenuAction(android.R.id.paste))
            }
            compose.waitUntil(10_000) { repository.drafts.state.value[id]?.prompt == "Keep this prompt plus ordinary text" }
            assertEquals(selected.attachments, repository.drafts.state.value.getValue(id).attachments)
            assertTrue(peer.requests.none { it.optString("method") in setOf("mobile.task.attachment.upload", "workspace.create") })
        } finally {
            compose.runOnIdle { previous?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() }
            files.forEach(File::delete)
        }
    }

    @Test fun fullDraftReportsLimitBeforeLaunchingEitherPicker() {
        seedAttachments(10)
        show()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (label in listOf("Photos", "Files")) {
            val monitor = instrumentation.addMonitor(composerPickerFilter(context, label == "Photos"),
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
            try {
                compose.onNodeWithContentDescription("Add task attachment").performClick()
                compose.onNodeWithText(label).performClick()
                compose.onNodeWithText(TaskAttachments.COUNT_MESSAGE).assertExists()
                compose.waitForIdle()
                assertEquals("A full draft launched $label", 0, monitor.hits)
            } finally { instrumentation.removeMonitor(monitor) }
        }
        assertEquals(10, repository.drafts.state.value.getValue(id).attachments.size)
        assertTrue(peer.requests.none { it.optString("method") == "mobile.task.attachment.upload" })
    }

    private fun seedAttachments(count: Int) = runBlocking {
        val editor = repository.drafts.begin(id, "attachment-mac", "Mac", "/repo")
        try { repeat(count) { index ->
            val bytes = "Existing $index".toByteArray()
            repository.attach(editor, AttachmentFiles.Prepared(ComposerAttachment(name = "existing-$index.txt", size = bytes.size), bytes))
        } } finally { repository.drafts.end(editor) }
    }

    private fun mixedProviderPaste(systemPaste: Boolean) {
        show()
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Keep my prompt")
        val directory = File(context.cacheDir, "task-previews").apply { mkdirs() }
        val first = File(directory, "first-$id.txt").apply { writeText("First readable file") }
        val last = File(directory, "last-$id.txt").apply { writeText("Last readable file") }
        val missing = File(directory, "missing-$id.txt")
        fun uri(file: File) = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.task-previews", file)
        val badMetadata = Uri.parse("content://${context.packageName}.task-previews/unregistered-root/item.txt")
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        val previous = clipboard.primaryClip
        try {
            // ContentResolver normalizes this FileProvider metadata failure to null.
            // Its unreadable URI must still not prevent later files from staging.
            assertNull(context.contentResolver.getType(badMetadata))
            assertEquals("text/plain", context.contentResolver.getType(uri(missing)))
            val clip = android.content.ClipData("Files", arrayOf("text/uri-list"), android.content.ClipData.Item(uri(first)))
            listOf(badMetadata, uri(missing), uri(last)).forEach { clip.addItem(android.content.ClipData.Item(it)) }
            compose.runOnIdle { clipboard.setPrimaryClip(clip) }
            if (systemPaste) {
                compose.onNodeWithContentDescription("Task prompt").performTouchInput { longClick(center) }
                clickSystemPaste { compose.waitForIdle() }
            } else {
                compose.onNodeWithContentDescription("Add task attachment").performClick()
                compose.onNodeWithText("Paste attachment").performClick()
            }
            compose.waitUntil(15_000) { repository.drafts.state.value[id]?.attachments?.size == 2 }
            val draft = repository.drafts.state.value.getValue(id)
            assertEquals("Keep my prompt", draft.prompt)
            val feedback = "2 attachments couldn't be read. Try adding the missing files again."
            // Durable attachment publication precedes completion of the staging
            // batch. Wait for its final user feedback, not only the byte count.
            compose.waitUntil(10_000) { compose.onAllNodesWithText(feedback).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(feedback).assertExists()
            assertEquals(listOf(first.name, last.name), draft.attachments.map { it.name })
            draft.attachments.zip(listOf(first, last)).forEach { (attachment, file) ->
                assertArrayEquals(file.readBytes(), runBlocking { repository.readAttachment(attachment) })
            }
            assertTrue(peer.requests.none { it.optString("method") == "mobile.task.attachment.upload" })
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Create Task").fetchSemanticsNodes().any {
                !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            } }
            compose.onNodeWithContentDescription("Create Task").performClick()
            compose.waitUntil(15_000) { completed }
            val uploads = peer.requests.filter { it.optString("method") == "mobile.task.attachment.upload" }.map { it.getJSONObject("params") }
            assertEquals(draft.attachments.map { it.id }, uploads.map { it.getString("upload_id") })
            uploads.zip(listOf(first, last)).forEach { (upload, file) ->
                assertArrayEquals(file.readBytes(), java.util.Base64.getDecoder().decode(upload.getString("data_b64")))
            }
        } finally {
            compose.runOnIdle { previous?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() }
            first.delete(); last.delete()
        }
    }

    private fun imageStagesAndUploads(systemPaste: Boolean) {
        show()
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Explain this image")
        compose.waitUntil(10_000) { keyboardRequest != null }
        val directory = File(context.cacheDir, "task-previews").apply { mkdirs() }
        val photo = File(directory, "task-keyboard-fixture.png")
        Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.task-previews", photo)
            if (systemPaste) {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                val previous = clipboard.primaryClip
                try {
                    compose.runOnIdle { clipboard.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, "Photo", uri)) }
                    compose.onNodeWithContentDescription("Task prompt").performTouchInput { longClick(center) }
                    clickSystemPaste { compose.waitForIdle() }
                    compose.waitUntil(15_000) { repository.drafts.state.value[id]?.attachments?.size == 1 }
                } finally { compose.runOnIdle { previous?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() } }
            } else compose.runOnIdle {
                val attributes = android.view.inputmethod.EditorInfo()
                val connection = keyboardRequest!!.createInputConnection(attributes)
                assertArrayEquals(arrayOf("image/*"), attributes.contentMimeTypes)
                // A final native edit and image admission in one UI turn must
                // persist the text before staging disables the editor.
                assertTrue(connection.commitText(" 中", 1))
                assertTrue(connection.commitContent(android.view.inputmethod.InputContentInfo(uri,
                    android.content.ClipDescription("Photo", arrayOf("image/png")), null), 0, null))
            }
            compose.waitUntil(15_000) { repository.drafts.state.value[id]?.attachments?.size == 1 }
            val draft = repository.drafts.state.value.getValue(id)
            val expectedPrompt = "Explain this image" + if (systemPaste) "" else " 中"
            assertEquals(expectedPrompt, draft.prompt)
            if (systemPaste) {
                compose.waitForIdle()
                val root = File(context.getExternalFilesDir(null), "composer-system-paste").apply { mkdirs() }
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                    File(root, "task-attachment.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            val bytes = runBlocking { repository.readAttachment(draft.attachments.single()) }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size).also { bitmap ->
                assertEquals(android.graphics.Color.MAGENTA, bitmap.getPixel(3, 3)); bitmap.recycle()
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Create Task").fetchSemanticsNodes().any {
                !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            } }
            compose.onNodeWithContentDescription("Create Task").performClick()
            compose.waitUntil(15_000) { completed }
            val created = peer.requests.single { it.optString("method") == "workspace.create" }.getJSONObject("params")
            assertEquals("$expectedPrompt\n\nAttached files (absolute paths on this machine):\n- /tmp/cmux fixture.txt",
                created.getJSONObject("initial_env").getString("CMUX_TASK_PROMPT"))
            val upload = peer.requests.single { it.optString("method") == "mobile.task.attachment.upload" }.getJSONObject("params")
            assertEquals(draft.attachments.single().id, upload.getString("upload_id"))
            assertArrayEquals(bytes, java.util.Base64.getDecoder().decode(upload.getString("data_b64")))
        } finally { photo.delete() }
    }
    private fun choose(file: File, label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(composerPickerFilter(context, label == "Photos"),
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(
                androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.task-previews", file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)), true)
        try {
            compose.onNodeWithContentDescription("Add task attachment").performClick()
            compose.onNodeWithText(label).performClick()
            compose.waitUntil(10_000) { monitor.hits > 0 }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun photoLibraryKeepsVideoBytesAndLaterImageWhenOneSelectionIsUnreadable() {
        show()
        compose.onNodeWithContentDescription("Task prompt").performTextInput("Review these media")
        ComposerMediaFixture(context).use { media ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val monitor = instrumentation.addMonitor(composerPickerFilter(context, true),
                Instrumentation.ActivityResult(Activity.RESULT_OK, media.result()), true)
            try {
                compose.onNodeWithContentDescription("Add task attachment").performClick()
                compose.onNodeWithText("Photos").performClick()
                compose.waitUntil(10_000) { monitor.hits > 0 }
                compose.waitUntil(15_000) { repository.drafts.state.value[id]?.attachments?.size == 2 }
                val draft = repository.drafts.state.value.getValue(id)
                assertEquals("Review these media", draft.prompt)
                assertEquals(listOf("clip.mp4", "photo.png"), draft.attachments.map { it.name })
                val video = draft.attachments.first(); assertNull(video.imageFormat)
                assertArrayEquals(media.video.readBytes(), runBlocking { repository.readAttachment(video) })
                assertFalse(File(context.noBackupFilesDir, "task-attachments/${video.id}").readBytes().contentEquals(media.video.readBytes()))
                assertEquals("png", draft.attachments.last().imageFormat)
                compose.onNodeWithText("1 attachment couldn't be read. Try adding the missing files again.").assertExists()
                compose.onNodeWithContentDescription("Task prompt").assertIsNotFocused()
                assertTrue(peer.requests.none { it.optString("method") == "mobile.task.attachment.upload" })
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    @Test fun pickerStagesPhotosAndEmptyFilesThenRetriesWithoutChangingTaskIdentity() {
        var rejected = false
        val sent = mutableListOf<JSONObject>()
        fun stableCreateTap(name: String) {
            var previous: androidx.compose.ui.geometry.Rect? = null
            var stableSince = android.os.SystemClock.uptimeMillis()
            compose.waitUntil(10_000) {
                val node = compose.onNodeWithContentDescription("Create Task").fetchSemanticsNode()
                val bounds = node.boundsInWindow
                val now = android.os.SystemClock.uptimeMillis()
                if (bounds != previous) { previous = bounds; stableSince = now }
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) && now - stableSince >= 300
            }
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            compose.onNodeWithContentDescription("Create Task").performClick()
        }
        show { params ->
            sent += JSONObject(params.toString())
            if (!rejected) { rejected = true; throw IOException("Fixture rejection") }
            client.request("workspace.create", params)
        }
        val directory = File(context.cacheDir, "task-previews").apply { mkdirs() }
        val photo = File(directory, "task-photo-fixture.png")
        Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.BLUE)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val empty = File(directory, "task-empty-fixture.txt").apply { writeBytes(byteArrayOf()) }
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
            compose.onNodeWithContentDescription("Task prompt").performTextInput("Explain these files")
            stableCreateTap("task-picker-before-send")
            try {
                compose.waitUntil(5_000) { sent.size == 1 }
                compose.waitUntil(15_000) { compose.onAllNodesWithText("That Mac is not connected. Check your workspace list before retrying.").fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: Throwable) {
                println("Task first-send diagnostic: sends=${sent.size}, uploads=${peer.requests.count { it.optString("method") == "mobile.task.attachment.upload" }}")
                compose.onRoot().printToLog("TaskRetryFixture"); throw failure
            }
            assertEquals(staged, repository.drafts.state.value.getValue(id).attachments)
            stableCreateTap("task-picker-before-retry")
            try {
                compose.waitUntil(5_000) { sent.size == 2 }
                compose.waitUntil(15_000) { completed }
            } catch (failure: Throwable) {
                println("Task retry diagnostic: sends=${sent.size}, rpcCreates=${peer.requests.count { it.optString("method") == "workspace.create" }}")
                compose.onRoot().printToLog("TaskRetryFixture")
                throw failure
            }
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

    @Test fun promptCanvasAndDockResizeWithKeyboardAndOptionsOwnDirectory() {
        show()
        compose.waitUntil(10_000) {
            compose.onNodeWithContentDescription("Task prompt").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Focused] &&
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(10_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false
        }
        compose.onNodeWithContentDescription("Task prompt").assertIsDisplayed()
        compose.onNodeWithText("Directory on Mac").assertDoesNotExist()
        compose.onNodeWithContentDescription("Task title").assertTextEquals("repo")
        val initial = compose.onNodeWithContentDescription("Task prompt").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Prompt should occupy most of the canvas", initial.height > root.height * 0.6f)
        val before = compose.onNodeWithContentDescription("Create Task").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("Task prompt").performClick().performTextInput("Make the tests pass")
        compose.waitUntil(10_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true &&
                compose.onNodeWithContentDescription("Create Task").fetchSemanticsNode().boundsInRoot.bottom < before.bottom - 100
        }
        compose.onNodeWithContentDescription("Create Task").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Task Options").assertIsDisplayed()
        val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val output = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(output, "task-composer-keyboard.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }; screen.recycle()
        compose.onNodeWithContentDescription("Task Options").performClick()
        compose.onNodeWithContentDescription("Browse folders").assertIsDisplayed()
        compose.onNodeWithText("Workspace name (optional)").performTextInput("Named task")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Task title").assertTextEquals("Named task")
        compose.onNodeWithContentDescription("Task prompt").assertTextContains("Make the tests pass")
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
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Viewer actions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("${bytes.size} bytes").assertExists()
        val opened = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
        val exportedUri = java.util.concurrent.atomic.AtomicReference<Uri?>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val viewer = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                val target = if (intent.action == Intent.ACTION_CHOOSER)
                    androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return null
                    else intent
                if (target.action != Intent.ACTION_VIEW) return null
                assertTrue(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                val uri = checkNotNull(target.data)
                assertEquals("${context.packageName}.task-previews", uri.authority)
                opened.set(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                exportedUri.set(uri)
                return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
            }
        }
        instrumentation.addMonitor(viewer)
        try {
            compose.onNodeWithContentDescription("Viewer actions").performClick()
            compose.onNodeWithText("Open").performClick()
            compose.waitUntil(10_000) { opened.get() != null }
            assertArrayEquals(bytes, opened.get())
            // Open owns an independent export: returning from another app must not delete it
            // while that app may still read it. Preview dismissal only removes the local preview.
            assertArrayEquals(bytes, context.contentResolver.openInputStream(exportedUri.get()!!)!!.use { it.readBytes() })
        } finally { instrumentation.removeMonitor(viewer) }
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(10_000) { File(context.cacheDir, "task-previews").listFiles().orEmpty().none { it.isDirectory } }
        assertArrayEquals(bytes, context.contentResolver.openInputStream(exportedUri.get()!!)!!.use { it.readBytes() })
        compose.onNodeWithContentDescription("Remove task attachment: preview.txt").performScrollTo().performClick()
        compose.waitUntil(10_000) { repository.drafts.state.value[id]?.attachments == listOf(second) }
        assertEquals("Keep this prompt", repository.drafts.state.value.getValue(id).prompt)
        assertFalse(File(context.noBackupFilesDir, "task-attachments/${first.id}").exists())
        assertArrayEquals(bytes, runBlocking { repository.readAttachment(second) })
    }
}
