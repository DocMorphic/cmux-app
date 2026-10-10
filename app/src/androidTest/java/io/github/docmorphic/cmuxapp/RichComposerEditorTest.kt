package io.github.docmorphic.cmuxapp

import android.content.ClipDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.input.key.Key
import androidx.core.content.FileProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CaptureComposerInput(onRequest: (PlatformTextInputMethodRequest) -> Unit, content: @Composable () -> Unit) {
    val receive by rememberUpdatedState(onRequest)
    val capture = remember { PlatformTextInputInterceptor { request, next ->
        receive(request)
        next.startInputMethod(request)
    } }
    InterceptPlatformTextInput(capture, content)
}

/** Exercises the installed Compose editor and platform interceptor without account credentials. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class RichComposerEditorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    @Volatile private var request: PlatformTextInputMethodRequest? = null
    private var value by mutableStateOf(TextFieldValue("left RIGHT", TextRange(5, 10)))
    private var owner by mutableStateOf("first")
    private var accepting by mutableStateOf(true)
    private val received = mutableListOf<TerminalPasteContent>()
    private val errors = mutableListOf<String>()
    private var previousClip: ClipData? = null
    private var clipboardTouched = false
    private val files = mutableListOf<File>()
    private val clipboard get() = compose.activity.getSystemService(ClipboardManager::class.java)
    @After fun cleanup() {
        compose.runOnIdle {
            received.forEach { it.close() }
            if (clipboardTouched) previousClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
        }
        files.forEach { it.delete() }
    }
    private fun copied(clip: ClipData) = compose.runOnIdle {
        if (!clipboardTouched) { previousClip = clipboard.primaryClip; clipboardTouched = true }
        clipboard.setPrimaryClip(clip)
    }
    private fun file(name: String): Uri {
        val context = compose.activity
        val directory = File(context.cacheDir, "task-previews").apply { mkdirs() }
        val file = File(directory, name).apply { writeText("fixture"); files += this }
        return FileProvider.getUriForFile(context, "${context.packageName}.task-previews", file)
    }

    private fun show() {
        compose.setContent {
            val capture = remember { PlatformTextInputInterceptor { incoming, next ->
                request = incoming
                next.startInputMethod(incoming)
            } }
            InterceptPlatformTextInput(capture) {
                RichContentEditor(owner, accepting, { received += it; true }, { errors += it }) { pasteModifier ->
                    OutlinedTextField(value, { value = it }, pasteModifier)
                }
            }
        }
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitUntil(10_000) { request != null }
    }

    private fun image() = InputContentInfo(Uri.parse("content://fixture/image.png"),
        ClipDescription("Image", arrayOf("image/png")), null)

    @Test fun systemMenuStagesImagesAndFilesWithoutInsertingCaptionsOrProviderUris() {
        show()
        val image = file("system-paste.png"); val document = file("system-paste.txt")
        copied(ClipData("Mixed", arrayOf("image/png", "text/plain", "text/uri-list"),
            ClipData.Item("unsafe fallback", null, null, image)).apply {
            addItem(ClipData.Item(document)); addItem(ClipData.Item("do not insert this caption"))
        })
        compose.onNode(hasSetTextAction()).performTouchInput { longClick(center) }
        clickSystemPaste { compose.waitForIdle() }
        compose.waitUntil(5_000) { received.size == 1 }
        compose.runOnIdle {
            assertEquals("left RIGHT", value.text)
            assertEquals(listOf(TerminalPasteContent.Item.Attachment(image, true),
                TerminalPasteContent.Item.Attachment(document, false)), received.single().items)
            assertTrue(errors.isEmpty())
        }
    }

    @Test fun hardwareAndImePasteStageAttachmentsWhilePlainTextReplacesSelection() {
        show()
        val image = file("hardware-paste.png")
        copied(ClipData.newUri(compose.activity.contentResolver, "Image", image))
        compose.onNode(hasSetTextAction()).performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.V); keyUp(Key.CtrlLeft)
        }
        compose.waitUntil(5_000) { received.size == 1 }
        copied(ClipData.newPlainText("Text", "こんにちは"))
        compose.runOnIdle { request!!.createInputConnection(EditorInfo()).setSelection(5, 10) }
        compose.onNode(hasSetTextAction()).performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.V); keyUp(Key.CtrlLeft)
        }
        compose.onNode(hasSetTextAction()).assertTextEquals("left こんにちは")
        val document = file("ime-paste.txt")
        copied(ClipData.newUri(compose.activity.contentResolver, "File", document))
        compose.runOnIdle {
            val connection = request!!.createInputConnection(EditorInfo())
            assertTrue(connection.performContextMenuAction(android.R.id.paste))
            assertEquals(2, received.size)
            assertEquals(listOf(TerminalPasteContent.Item.Attachment(document, false)), received.last().items)
            assertEquals("left こんにちは", value.text)
        }
        copied(ClipData.newPlainText("Text", "世界"))
        compose.runOnIdle {
            val connection = request!!.createInputConnection(EditorInfo())
            assertTrue(connection.setSelection(5, 10))
            assertTrue(connection.performContextMenuAction(android.R.id.paste))
        }
        compose.onNode(hasSetTextAction()).assertTextEquals("left 世界")
        copied(ClipData.newPlainText("Text", "!"))
        compose.runOnIdle {
            val connection = request!!.createInputConnection(EditorInfo())
            assertTrue(connection.setComposingText("てすと", 1))
            assertTrue(connection.performContextMenuAction(android.R.id.pasteAsPlainText))
        }
        compose.onNode(hasSetTextAction()).assertTextEquals("left 世界てすと!")
        compose.runOnIdle { assertNull(value.composition) }
    }

    @Test fun disabledOversizedAndRetiredPasteNeverInsertUrisOrAttachToNewOwner() {
        show()
        val uri = file("blocked-paste.png")
        copied(ClipData.newUri(compose.activity.contentResolver, "Image", uri))
        lateinit var old: InputConnection
        compose.runOnIdle { old = request!!.createInputConnection(EditorInfo()); accepting = false }
        compose.onNode(hasSetTextAction()).performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.V); keyUp(Key.CtrlLeft)
        }
        compose.runOnIdle {
            assertTrue(received.isEmpty()); assertEquals(1, errors.size)
            owner = "replacement"; accepting = true
        }
        compose.runOnIdle {
            assertTrue(old.performContextMenuAction(android.R.id.paste))
            assertTrue(received.isEmpty()); assertEquals("left RIGHT", value.text)
        }
        copied(ClipData.newUri(compose.activity.contentResolver, "Too many", uri).apply {
            repeat(10) { addItem(ClipData.Item(uri)) }
        })
        compose.onNode(hasSetTextAction()).performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.V); keyUp(Key.CtrlLeft)
        }
        compose.runOnIdle {
            assertTrue(received.isEmpty()); assertEquals("left RIGHT", value.text)
            assertTrue(errors.last().contains("10 items"))
        }
    }

    @Test fun composeImagesPreserveTextSelectionAndFollowingImeEdits() {
        show()
        compose.runOnIdle {
            val attributes = EditorInfo()
            val connection = request!!.createInputConnection(attributes)
            assertArrayEquals(arrayOf("image/*"), attributes.contentMimeTypes)
            connection.setSelection(5, 10)
            assertTrue(connection.commitContent(image(), 0, null))
            assertEquals("left RIGHT", value.text)
            assertEquals(TextRange(5, 10), value.selection)
            assertEquals(1, received.size)
            connection.commitText("after", 1)
        }
        compose.onNode(hasSetTextAction()).assertTextEquals("left after")
        compose.runOnIdle { received.forEach { it.close() } }
    }

    @Test fun oldOwnerDisabledAndClosedConnectionsCannotAttachToANewDraft() {
        show()
        lateinit var old: InputConnection
        lateinit var oldRequest: PlatformTextInputMethodRequest
        compose.runOnIdle {
            oldRequest = request!!
            old = oldRequest.createInputConnection(EditorInfo())
            accepting = false
        }
        compose.runOnIdle {
            assertFalse(old.commitContent(image(), 0, null))
            val attributes = EditorInfo()
            request!!.createInputConnection(attributes)
            assertNull(attributes.contentMimeTypes)
            owner = "second"; accepting = true
        }
        compose.waitUntil(10_000) { request !== oldRequest }
        compose.runOnIdle {
            assertFalse(old.commitContent(image(), 0, null))
            val current = request!!.createInputConnection(EditorInfo())
            assertTrue(current.commitContent(image(), 0, null))
            current.closeConnection()
            assertFalse(current.commitContent(image(), 0, null))
            assertEquals(1, received.size)
            received.forEach { it.close() }
        }
    }
}
