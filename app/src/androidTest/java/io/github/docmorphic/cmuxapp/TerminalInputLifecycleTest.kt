package io.github.docmorphic.cmuxapp

import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses an empty activity; never reads or changes account credentials. */
class TerminalInputLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun stoppedEditorRejectsLateInputAndCannotReviveItsOldConnection() {
        lateinit var view: TerminalKeyboardView
        lateinit var old: InputConnection
        val sent = mutableListOf<String>()
        compose.setContent { AndroidView(factory = { TerminalKeyboardView(it).also { view = it; it.onText = sent::add } }) }
        compose.runOnIdle {
            old = view.onCreateInputConnection(EditorInfo())!!
            assertTrue(old.setComposingText("unfinished 界", 1))
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity {
            assertFalse("A stopped editor must reject a late IME commit", old.commitText("late", 1))
            assertFalse(old.finishComposingText())
            assertFalse(old.deleteSurroundingText(1, 0))
            assertFalse(old.performEditorAction(EditorInfo.IME_ACTION_DONE))
            assertFalse(old.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
            assertNull(view.onCreateInputConnection(EditorInfo()))
            assertFalse(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "late accessibility")
            }))
            view.showKeyboard()
            assertTrue(sent.isEmpty())
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle {
            assertFalse(old.commitText("still stale", 1))
            assertEquals("Direct typing · tap here for keyboard", view.text.toString())
            val fresh = view.onCreateInputConnection(EditorInfo())!!
            assertTrue(fresh.commitText("fresh 界", 1))
            assertEquals(listOf("fresh 界"), sent)
        }
    }

    @Test fun temporaryPausePreservesUncommittedComposition() {
        lateinit var view: TerminalKeyboardView
        lateinit var connection: InputConnection
        val sent = mutableListOf<String>()
        compose.setContent { AndroidView(factory = { TerminalKeyboardView(it).also { view = it; it.onText = sent::add } }) }
        compose.runOnIdle {
            connection = view.onCreateInputConnection(EditorInfo())!!
            assertTrue(connection.setComposingText("draft 界", 1))
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle {
            assertTrue(sent.isEmpty())
            assertTrue(connection.finishComposingText())
            assertEquals(listOf("draft 界"), sent)
        }
    }

    @Test fun backgroundClearsDirectFocusUntilAnExplicitTap() {
        lateinit var view: TerminalKeyboardView
        compose.setContent {
            var editor by remember { mutableStateOf<TerminalKeyboardView?>(null) }
            RetireTerminalInputOnBackground(editor)
            AndroidView(factory = { TerminalKeyboardView(it).also { view = it; editor = it } })
        }
        compose.runOnIdle { view.showKeyboard() }
        compose.runOnIdle { assertTrue(view.hasFocus()) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertTrue("Temporary interruption preserves focus", view.hasFocus()) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertFalse("Background ends direct focus", view.hasFocus()); view.performClick() }
        compose.runOnIdle { assertTrue("A new tap can focus", view.hasFocus()) }
    }

    @Test fun backgroundClearsComposerFocusWithoutChangingItsDraft() {
        compose.setContent {
            var draft by remember { mutableStateOf("unsent 界") }
            RetireTerminalInputOnBackground(null)
            BasicTextField(draft, { draft = it }, Modifier.testTag("composer"))
        }
        compose.onNodeWithTag("composer").performClick().assertIsFocused()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("composer").assertIsFocused().assertTextEquals("unsent 界")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("composer").assertIsNotFocused().assertTextEquals("unsent 界")
        compose.onNodeWithTag("composer").performClick().assertIsFocused().assertTextEquals("unsent 界")
    }
}
