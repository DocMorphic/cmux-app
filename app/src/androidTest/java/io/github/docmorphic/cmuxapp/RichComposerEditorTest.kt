package io.github.docmorphic.cmuxapp

import android.content.ClipDescription
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
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

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

    private fun show() {
        compose.setContent {
            val capture = remember { PlatformTextInputInterceptor { incoming, next ->
                request = incoming
                next.startInputMethod(incoming)
            } }
            InterceptPlatformTextInput(capture) {
                RichContentEditor(owner, accepting, { received += it; true }, { error(it) }) {
                    OutlinedTextField(value, { value = it })
                }
            }
        }
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitUntil(10_000) { request != null }
    }

    private fun image() = InputContentInfo(Uri.parse("content://fixture/image.png"),
        ClipDescription("Image", arrayOf("image/png")), null)

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
