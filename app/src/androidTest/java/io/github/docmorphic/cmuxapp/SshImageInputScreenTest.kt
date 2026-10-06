package io.github.docmorphic.cmuxapp

import android.content.ClipDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalComposeUiApi::class)
class SshImageInputScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val pool = SshComposerPool()
    private lateinit var terminal: Terminal
    private lateinit var photo: File
    private class Terminal(pool: SshComposerPool) : SshTerminal {
        override val id = "ui-image-test"; override val title = "SSH image fixture"
        override val composer = pool.open(id)
        override val state = MutableStateFlow(SshShellState(SshShellPhase.RUNNING))
        override val display = GhosttyVtTerminal(80, 24)
        val writes = mutableListOf<ByteArray>()
        val images = mutableListOf<ByteArray>()
        var release: CompletableDeferred<Unit>? = null
        override val imageUpload: SshImageUpload = { bytes, _ -> images += bytes.copyOf(); release?.await(); "/fixture/image.png" }
        override fun send(text: String, paste: Boolean) = sendBytes((if (paste) TerminalKeyEncoding.paste(text, display.bracketedPaste) else text).toByteArray())
        override fun sendBytes(bytes: ByteArray): Boolean { writes += bytes.copyOf(); return true }
        override fun resize(columns: Int, rows: Int, cells: TerminalCellMetrics) {
            display.resize(columns, rows, cells.widthPx.toInt().coerceAtLeast(1), cells.heightPx.toInt().coerceAtLeast(1))
            state.value = state.value.copy(revision = state.value.revision + 1)
        }
        override fun close() { state.value = state.value.copy(phase = SshShellPhase.ENDED); display.close() }
    }
    @Before fun setup() {
        terminal = Terminal(pool)
        val root = File(compose.activity.cacheDir, "task-previews").apply { mkdirs() }
        photo = File(root, "ssh-image-${UUID.randomUUID()}.png")
        Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.CYAN)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @After fun cleanup() {
        compose.activityRule.scenario.close(); terminal.close(); pool.close(); photo.delete()
    }
    private fun image(): InputContentInfo {
        val context = compose.activity
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.task-previews", photo)
        return InputContentInfo(uri, ClipDescription("Image", arrayOf("image/png")), null)
    }
    private fun capture(name: String) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.waitForIdle(100, 3000)
        val bitmap = ui.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun keyboard(view: View): TerminalKeyboardView? = if (view is TerminalKeyboardView) view else
        (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { keyboard(group.getChildAt(it)) } }

    private fun mixedClipboard() {
        compose.runOnIdle {
            compose.activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData("Image with captions", arrayOf("image/png", "text/plain"),
                    ClipData.Item("same-item caption\n", null, null, image().contentUri)).apply {
                    addItem(ClipData.Item("separate caption\n"))
                })
        }
    }

    @Test fun imageChipPreviewsExactStagedBytesAndDismissalKeepsTheUnsentDraft() {
        val bytes = photo.readBytes()
        val attachment = ComposerAttachment(name = "preview.png", size = bytes.size, imageFormat = "png")
        terminal.composer.attach(attachment, bytes); terminal.composer.edit("Unsent SSH prompt")
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshShellScreen(terminal, onBack = {})
        } } }
        compose.onNodeWithContentDescription(attachment.name).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Image preview preview.png").fetchSemanticsNodes().isNotEmpty() }
        lateinit var file: File
        compose.runOnIdle {
            val model = androidx.lifecycle.ViewModelProvider(compose.activity)[ComposerAttachmentPreviewModel::class.java]
            file = checkNotNull(model.controller.state.value.artifact).file
            assertArrayEquals(bytes, file.readBytes())
        }
        compose.waitUntil(10_000) {
            val bounds = compose.onNodeWithContentDescription("Image preview preview.png").fetchSemanticsNode().boundsInWindow
            val screen = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            try { screen.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt()) == android.graphics.Color.CYAN }
            finally { screen.recycle() }
        }
        capture("ssh-composer-preview")
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(10_000) { !file.exists() }
        compose.onNodeWithTag("ssh.shell.composer").assertTextContains("Unsent SSH prompt")
        assertEquals(listOf(attachment), terminal.composer.current.attachments)
        assertTrue(terminal.images.isEmpty()); assertTrue(terminal.writes.isEmpty())
        compose.onNodeWithContentDescription("Remove ${attachment.name}").performClick()
        compose.waitUntil(5_000) { terminal.composer.current.attachments.isEmpty() }
        assertEquals("Unsent SSH prompt", terminal.composer.current.text)
    }

    @Test fun toolbarImagePasteDisarmsControlAndNeverSendsCaptionsOrEnter() {
        compose.runOnUiThread { terminal.release = CompletableDeferred() }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshShellScreen(terminal, onBack = {})
        } } }
        compose.onNodeWithText("Keyboard").performClick()
        mixedClipboard()
        compose.onNodeWithContentDescription("Ctrl").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Paste").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Ctrl").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Off"))
        compose.waitUntil(10_000) { terminal.images.size == 1 }
        compose.runOnIdle {
            val view = checkNotNull(keyboard(compose.activity.window.decorView))
            assertTrue(checkNotNull(view.onCreateInputConnection(EditorInfo())).commitText("cλ", 1))
            assertTrue(terminal.writes.isEmpty())
            terminal.release!!.complete(Unit)
        }
        compose.waitUntil(10_000) { terminal.writes.size >= 2 }
        compose.runOnIdle {
            assertEquals(listOf("'/fixture/image.png'", "cλ"), terminal.writes.map { it.decodeToString() })
            assertEquals(1, terminal.images.size)
        }
    }

    @Test fun toolbarComposerImagePastePreservesPromptAndWaitsForSend() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshShellScreen(terminal, onBack = {})
        } } }
        compose.onNodeWithTag("ssh.shell.composer").performTextInput("Explain λ")
        mixedClipboard()
        compose.onNodeWithContentDescription("Paste").performScrollTo().performClick()
        compose.waitUntil(10_000) { terminal.composer.current.attachments.size == 1 }
        compose.runOnIdle {
            assertEquals("Explain λ", terminal.composer.current.text)
            assertTrue(terminal.images.isEmpty()); assertTrue(terminal.writes.isEmpty())
        }
        compose.onNodeWithTag("ssh.shell.send").performClick()
        compose.waitUntil(10_000) { terminal.writes.size >= 2 }
        compose.runOnIdle {
            assertEquals(listOf("'/fixture/image.png' ", "Explain λ\r"), terminal.writes.map { it.decodeToString() })
        }
    }

    @Test fun zoomFilesAndComposerClearArmedModifiersWithoutSendingInput() {
        var filesOpened = 0
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshShellScreen(terminal, onFiles = { filesOpened++ }, onBack = {})
        } } }
        compose.onNodeWithText("Keyboard").performClick()
        for (action in listOf("Zoom In", "Zoom Out")) {
            compose.onNodeWithContentDescription("Alt").performScrollTo().performClick()
            compose.onNodeWithContentDescription(action).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Alt").assert(SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Off"))
        }
        compose.onNodeWithContentDescription("Alt").performScrollTo().performClick()
        compose.onNodeWithTag("ssh.shell.files").performClick()
        compose.onNodeWithContentDescription("Alt").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Off"))
        compose.onNodeWithText("Keyboard").performClick()
        compose.onNodeWithContentDescription("Ctrl").performScrollTo().performClick()
        compose.onNodeWithText("Compose").performClick()
        compose.onNodeWithContentDescription("Ctrl").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Off"))
        compose.runOnIdle { assertEquals(1, filesOpened); assertTrue(terminal.writes.isEmpty()) }
    }

    @Test fun composerImeImageRetainsDraftAcrossNavigationAndSendsImageBeforeText() {
        val request = AtomicReference<PlatformTextInputMethodRequest?>()
        val visible = mutableStateOf(true)
        compose.setContent { CaptureComposerInput({ request.set(it) }) { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            if (visible.value) SshShellScreen(terminal, onBack = { visible.value = false })
            else TextButton(onClick = { visible.value = true }) { Text("Return to terminal") }
        } } } }
        compose.onNodeWithTag("ssh.shell.composer").performTextInput("Explain this picture")
        compose.waitUntil(10_000) { request.get() != null }
        lateinit var old: android.view.inputmethod.InputConnection
        compose.runOnIdle {
            val attributes = EditorInfo(); old = request.get()!!.createInputConnection(attributes)
            assertArrayEquals(arrayOf("image/*"), attributes.contentMimeTypes)
            assertTrue(old.commitContent(image(), 0, null))
        }
        compose.waitUntil(10_000) { terminal.composer.current.attachments.size == 1 }
        compose.onNodeWithTag("ssh.shell.composer").assertTextContains("Explain this picture")
        assertTrue(terminal.images.isEmpty()); assertTrue(terminal.writes.isEmpty())
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Return to terminal").assertIsDisplayed()
        compose.runOnIdle { assertFalse(old.commitContent(image(), 0, null)) }
        compose.onNodeWithText("Return to terminal").performClick()
        compose.onNodeWithTag("ssh.shell.composer").assertTextContains("Explain this picture")
        compose.onNodeWithTag("composer.attachment.${terminal.composer.current.attachments.single().id}").assertIsDisplayed()
        capture("ssh-image-composer")
        compose.onNodeWithTag("ssh.shell.send").performClick()
        compose.waitUntil(10_000) { terminal.writes.size == 2 }
        compose.runOnIdle {
            assertEquals(listOf("'/fixture/image.png' ", "Explain this picture\r"), terminal.writes.map { it.decodeToString() })
            assertTrue(terminal.composer.current.attachments.isEmpty()); assertEquals("", terminal.composer.current.text)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(terminal.images.single(), 0, terminal.images.single().size)
            assertEquals(32, bitmap.width); assertEquals(16, bitmap.height)
            assertEquals(android.graphics.Color.CYAN, bitmap.getPixel(0, 0)); bitmap.recycle()
        }
    }

    @Test fun systemPhotoPickerCancelsThenStagesARealImageForAnImagesOnlySend() {
        val context = compose.activity
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = checkNotNull(resolver.insert(collection, android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "cmux-picker-${UUID.randomUUID()}.png")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            put(android.provider.MediaStore.Images.ImageColumns.DATE_TAKEN, System.currentTimeMillis())
        }))
        try {
            resolver.openOutputStream(uri)!!.use { it.write(photo.readBytes()) }
            resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) { SshShellScreen(terminal, onBack = {}) } } }
            val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            compose.onNodeWithTag("ssh.shell.attach").performClick()
            checkNotNull(device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.descContains("Photo taken")), 10_000))
            device.pressBack()
            compose.onNodeWithTag("ssh.shell.attach").assertIsDisplayed()
            compose.runOnIdle { assertTrue(terminal.composer.current.attachments.isEmpty()); assertTrue(terminal.images.isEmpty()) }
            compose.onNodeWithTag("ssh.shell.attach").performClick()
            checkNotNull(device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.descContains("Photo taken")), 10_000)).click()
            checkNotNull(device.wait(androidx.test.uiautomator.Until.findObject(
                androidx.test.uiautomator.By.pkg(java.util.regex.Pattern.compile("com\\.(google\\.)?android\\.(photopicker|providers\\.media\\.module)"))
                    .text(java.util.regex.Pattern.compile("(?i)add.*|done"))), 5000)).click()
            compose.waitUntil(10_000) { terminal.composer.current.attachments.size == 1 }
            assertTrue(terminal.images.isEmpty())
            compose.onNodeWithTag("composer.attachment.${terminal.composer.current.attachments.single().id}").assertIsDisplayed()
            compose.onNodeWithTag("ssh.shell.send").assertIsEnabled()
            compose.waitForIdle()
            capture("ssh-image-photo-picker")
            compose.onNodeWithTag("ssh.shell.send").performClick()
            compose.waitUntil(10_000) { terminal.writes.size == 1 }
            compose.runOnIdle {
                assertEquals("'/fixture/image.png' ", terminal.writes.single().decodeToString())
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(terminal.images.single(), 0, terminal.images.single().size)
                assertEquals(android.graphics.Color.CYAN, bitmap.getPixel(0, 0)); bitmap.recycle()
                assertTrue(terminal.composer.current.attachments.isEmpty())
            }
        } finally { resolver.delete(uri, null, null) }
    }

    @Test fun directImeWaitsForUploadBeforeTypingAndRetiresOldKeyboardOnModeChange() {
        compose.runOnUiThread { terminal.release = CompletableDeferred() }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) { SshShellScreen(terminal, onBack = {}) } } }
        compose.onNodeWithText("Keyboard").performClick()
        lateinit var connection: android.view.inputmethod.InputConnection
        compose.runOnIdle {
            val view = checkNotNull(keyboard(compose.activity.window.decorView))
            val attributes = EditorInfo(); connection = checkNotNull(view.onCreateInputConnection(attributes))
            assertArrayEquals(arrayOf("image/*"), attributes.contentMimeTypes)
            assertTrue(connection.commitContent(image(), 0, null))
            assertTrue(connection.commitText("after λ", 1))
        }
        compose.waitUntil(10_000) { terminal.images.size == 1 }
        compose.runOnIdle { assertTrue(terminal.writes.isEmpty()); terminal.release!!.complete(Unit) }
        compose.waitUntil(10_000) { terminal.writes.size == 2 }
        compose.runOnIdle { assertEquals(listOf("'/fixture/image.png'", "after λ"), terminal.writes.map { it.decodeToString() }) }
        compose.onNodeWithText("Compose").performClick()
        compose.onNodeWithTag("ssh.shell.composer").assertIsDisplayed()
        compose.runOnIdle { assertFalse(connection.commitContent(image(), 0, null)); assertEquals(1, terminal.images.size) }
    }
}
