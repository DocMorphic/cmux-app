package io.github.docmorphic.cmuxapp

import android.content.Context
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The production SSH/Cloud screen and IME endpoint, with a local byte sink. */
class SshDirectKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var savedToolbar: String? = null
    private val preferences get() = compose.activity.getSharedPreferences("native_display", Context.MODE_PRIVATE)

    @Before fun useDefaultToolbar() {
        savedToolbar = preferences.getString(TerminalToolbarLayout.PREFERENCE, null)
        preferences.edit().remove(TerminalToolbarLayout.PREFERENCE).commit()
    }

    @After fun restoreToolbar() {
        preferences.edit().apply {
            if (savedToolbar == null) remove(TerminalToolbarLayout.PREFERENCE)
            else putString(TerminalToolbarLayout.PREFERENCE, savedToolbar)
        }.commit()
    }

    private class Terminal : SshTerminal {
        override val id = "direct-keyboard-fixture"
        override val title = "Direct keyboard fixture"
        override val state = MutableStateFlow(SshShellState(SshShellPhase.RUNNING))
        override val display = GhosttyVtTerminal(80, 24)
        val sent = CopyOnWriteArrayList<String>()
        override fun send(text: String, paste: Boolean): Boolean { sent += text; return true }
        override fun sendBytes(bytes: ByteArray): Boolean { sent += bytes.decodeToString(); return true }
        override fun resize(columns: Int, rows: Int, cells: TerminalCellMetrics) {
            display.resize(columns, rows, cells.widthPx.toInt().coerceAtLeast(1), cells.heightPx.toInt().coerceAtLeast(1))
            state.value = state.value.copy(revision = state.value.revision + 1)
        }
        override fun close() { state.value = state.value.copy(phase = SshShellPhase.ENDED); display.close() }
    }

    private fun editor(root: View): TerminalKeyboardView? = when (root) {
        is TerminalKeyboardView -> root
        is ViewGroup -> (0 until root.childCount).firstNotNullOfOrNull { editor(root.getChildAt(it)) }
        else -> null
    }

    private fun withTerminal(check: (Terminal, TerminalKeyboardView, InputConnection) -> Unit) {
        val terminal = Terminal()
        try {
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) {
                SshShellScreen(terminal, onBack = {})
            } } }
            compose.onNodeWithTag("ssh.shell.terminal").performTouchInput { click(center) }
            compose.onNodeWithTag("ssh.shell.keyboard").assertExists()
            lateinit var view: TerminalKeyboardView
            lateinit var connection: InputConnection
            compose.runOnIdle {
                view = checkNotNull(editor(compose.activity.window.decorView))
                connection = checkNotNull(view.onCreateInputConnection(EditorInfo()))
            }
            check(terminal, view, connection)
        } finally { compose.runOnUiThread { terminal.close() } }
    }

    private fun modifier(key: TerminalToolbarButton, count: Int = 1) {
        compose.onNodeWithTag("terminal-shortcut-${key.id}").performSemanticsAction(SemanticsActions.OnClick) { click ->
            repeat(count) { assertTrue(click()) }
        }
        compose.waitForIdle()
    }

    private fun armed(key: TerminalToolbarButton, value: String) {
        compose.onNodeWithTag("terminal-shortcut-${key.id}")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
    }

    private fun writes(terminal: Terminal, vararg expected: String) {
        val bytes = expected.joinToString("")
        // The ordered input queue can combine adjacent keys into one write.
        // Assert exact stream contents rather than incidental packet boundaries.
        compose.waitUntil(10_000) { terminal.sent.sumOf { it.length } >= bytes.length }
        compose.runOnIdle { assertEquals(bytes, terminal.sent.joinToString("")) }
    }

    @Test fun softDeletionUsesOneShotAndStickyReadlineModifiers() = withTerminal { terminal, _, connection ->
        modifier(TerminalToolbarButton.COMMAND)
        compose.runOnIdle { assertTrue(connection.deleteSurroundingText(1, 0)) }
        armed(TerminalToolbarButton.COMMAND, "Off")
        compose.runOnIdle { assertTrue(connection.commitText("x", 1)) }
        writes(terminal, "\u0015", "x")

        modifier(TerminalToolbarButton.ALT)
        compose.runOnIdle { assertTrue(connection.deleteSurroundingTextInCodePoints(2, 1)) }
        armed(TerminalToolbarButton.ALT, "Off")
        compose.runOnIdle { assertTrue(connection.commitText("y", 1)) }
        writes(terminal, "\u0015", "x", "\u001b\u007f\u001b\u007f\u001b\u001b[3~", "y")

        modifier(TerminalToolbarButton.COMMAND, 2)
        armed(TerminalToolbarButton.COMMAND, "Locked")
        compose.runOnIdle {
            assertTrue(connection.deleteSurroundingText(1, 0))
            assertTrue(connection.deleteSurroundingText(1, 0))
        }
        armed(TerminalToolbarButton.COMMAND, "Locked")
        writes(terminal, "\u0015", "x", "\u001b\u007f\u001b\u007f\u001b\u001b[3~", "y", "\u0015\u0015")
    }

    @Test fun editorReturnConsumesOneShotAndRetainsStickyModifiers() = withTerminal { terminal, _, connection ->
        modifier(TerminalToolbarButton.ALT)
        compose.runOnIdle { assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE)) }
        armed(TerminalToolbarButton.ALT, "Off")
        compose.runOnIdle { assertTrue(connection.commitText("a", 1)) }
        writes(terminal, "\u001b\r", "a")

        modifier(TerminalToolbarButton.COMMAND)
        compose.runOnIdle { assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE)) }
        armed(TerminalToolbarButton.COMMAND, "Off")
        compose.runOnIdle { assertTrue(connection.commitText("a", 1)) }
        writes(terminal, "\u001b\r", "a", "\ra")

        modifier(TerminalToolbarButton.ALT, 2)
        compose.runOnIdle {
            assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE))
            assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE))
        }
        armed(TerminalToolbarButton.ALT, "Locked")
        writes(terminal, "\u001b\r", "a", "\ra", "\u001b\r\u001b\r")
    }

    @Test fun editingCompositionDoesNotSendOrConsumeThePendingModifier() = withTerminal { terminal, view, connection ->
        modifier(TerminalToolbarButton.COMMAND)
        compose.runOnIdle {
            assertTrue(connection.setComposingText("ab", 1))
            assertTrue(connection.deleteSurroundingText(1, 0))
            assertEquals("a", view.text.toString())
            assertTrue(terminal.sent.isEmpty())
        }
        armed(TerminalToolbarButton.COMMAND, "Armed")
        compose.runOnIdle { assertTrue(connection.finishComposingText()) }
        armed(TerminalToolbarButton.COMMAND, "Off")
        compose.runOnIdle { assertTrue(connection.commitText("b", 1)) }
        writes(terminal, "\u0001", "b")
    }

    @Test fun deadAccentKeepsShiftUntilItsActualCharacterCommits() = withTerminal { terminal, _, connection ->
        val rightAlt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        fun key(meta: Int = 0) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        modifier(TerminalToolbarButton.SHIFT)
        compose.runOnIdle {
            assertTrue(key(rightAlt).getUnicodeChar(rightAlt) and KeyCharacterMap.COMBINING_ACCENT != 0)
            assertTrue(connection.sendKeyEvent(key(rightAlt)))
            assertTrue(terminal.sent.isEmpty())
        }
        armed(TerminalToolbarButton.SHIFT, "Armed")
        compose.runOnIdle { assertTrue(connection.sendKeyEvent(key())) }
        armed(TerminalToolbarButton.SHIFT, "Off")
        compose.runOnIdle { assertTrue(connection.sendKeyEvent(key())) }
        writes(terminal, "É", "e")
    }
}
