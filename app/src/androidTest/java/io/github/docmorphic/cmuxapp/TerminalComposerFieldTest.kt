package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TerminalComposerFieldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun attachmentMenuClosesWhenTerminalOwnerChangesOrActionsBecomeUnavailable() {
        var owner by mutableStateOf(Any())
        var available by mutableStateOf(true)
        var photos = 0
        var pastes = 0
        compose.setContent { CmuxTheme { Surface {
            ComposerAttachmentMenu(owner, available, { photos++ }, { pastes++ })
        } } }
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithText("Photos").assertExists()
        compose.runOnIdle { owner = Any() }
        compose.onNodeWithText("Photos").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithText("Photos").performClick()
        compose.runOnIdle { assertEquals(1, photos); assertEquals(0, pastes) }
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.runOnIdle { available = false }
        compose.onNodeWithText("Paste attachment").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add attachment").assertIsNotEnabled()
    }

    @Test fun multilineFieldGrowsToFourteenLinesAndSendStaysInsideAtBottom() {
        var text by mutableStateOf("One line")
        compose.setContent { CmuxTheme { Surface {
            TerminalComposerField(text, { text = it }, {}, true, false, false,
                modifier = Modifier.width(280.dp).testTag("field"),
                editorModifier = Modifier.testTag("editor"), sendModifier = Modifier.testTag("send"))
        } } }
        val single = compose.onNodeWithTag("field").fetchSemanticsNode().boundsInRoot
        fun checkSendBounds() {
            val field = compose.onNodeWithTag("field").fetchSemanticsNode().boundsInRoot
            val send = compose.onNodeWithTag("send").fetchSemanticsNode().boundsInRoot
            assertTrue(send.left >= field.left && send.right <= field.right)
            assertEquals(field.bottom, send.bottom, 1f)
            assertTrue(send.width >= 48 * compose.density.density)
        }
        checkSendBounds()
        compose.runOnIdle { text = (1..4).joinToString("\n") { "Line $it" } }
        val four = compose.onNodeWithTag("field").fetchSemanticsNode().boundsInRoot
        assertTrue(four.height > single.height * 1.5f)
        checkSendBounds()
        compose.runOnIdle { text = (1..14).joinToString("\n") { "Line $it" } }
        val fourteen = compose.onNodeWithTag("field").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { text = (1..30).joinToString("\n") { "Line $it" } }
        assertEquals(fourteen.height, compose.onNodeWithTag("field").fetchSemanticsNode().boundsInRoot.height, 1f)
        checkSendBounds()
        compose.onNodeWithTag("editor").assertTextEquals(text)
    }

    @Test fun dictationAndSendStatusKeepEditorAndButtonSemanticsIndependent() {
        var text by mutableStateOf("Recognized words")
        var sending by mutableStateOf(false)
        var failed by mutableStateOf(false)
        var locked by mutableStateOf(true)
        var count = 0
        compose.setContent { CmuxTheme { Surface {
            TerminalComposerField(text, { text = it }, { count++ }, text.isNotEmpty(), sending, failed,
                modifier = Modifier.width(300.dp), editorModifier = Modifier.testTag("editor"),
                sendModifier = Modifier.testTag("send"), readOnly = locked)
        } } }
        compose.onNodeWithTag("editor").assertTextEquals("Recognized words")
        compose.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, count); sending = true }
        compose.onNodeWithContentDescription("Sending").assertIsNotEnabled()
        compose.runOnIdle { sending = false; failed = true }
        compose.onNodeWithContentDescription("Send failed").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, count); failed = false; locked = false; text = "" }
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithText("Message").assertExists()
        compose.onNodeWithTag("editor").performTextInput("Typed next")
        compose.onNodeWithTag("editor").assertTextContains("Typed next")
        compose.onNodeWithContentDescription("Send").assertIsEnabled()
    }
}
