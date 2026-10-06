package io.github.docmorphic.cmuxapp

import android.text.Selection
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.*
import org.junit.Test

/** Uses the actual Editable/IME endpoint, without account or terminal fixtures. */
class TerminalCompositionDeletionTest {
    private fun editor(check: (TerminalKeyboardView, InputConnection, MutableList<String>, MutableList<Pair<Int, Int>>) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sent = mutableListOf<String>(); val deleted = mutableListOf<Pair<Int, Int>>()
                val view = TerminalKeyboardView(activity).apply {
                    onText = sent::add; onDelete = { before, after -> deleted += before to after }
                }
                activity.setContentView(view)
                try { check(view, checkNotNull(view.onCreateInputConnection(EditorInfo())), sent, deleted) }
                finally { view.dispose() }
            }
        }
    }
    private fun buffer(connection: InputConnection) = (connection as BaseInputConnection).editable!!

    @Test fun surroundingDeletionPreservesSelectedCompositionAndItsDirection() = editor { view, connection, sent, deleted ->
        for (codePoints in listOf(false, true)) for (reverse in listOf(false, true)) {
            assertTrue(connection.setComposingText("ab中文cd", 1))
            // Buffer offset zero is the terminal delete-repeat anchor.
            assertTrue(connection.setSelection(if (reverse) 5 else 3, if (reverse) 3 else 5))
            val delete: (Int, Int) -> Boolean = if (codePoints) connection::deleteSurroundingTextInCodePoints else connection::deleteSurroundingText
            assertTrue(delete(0, 0))
            assertEquals("ab中文cd", view.text.toString())
            assertEquals("中文", connection.getSelectedText(0).toString())
            assertTrue(delete(1, 1))
            assertEquals("a中文d", view.text.toString())
            assertEquals("中文", connection.getSelectedText(0).toString())
            assertEquals(if (reverse) 4 else 2, Selection.getSelectionStart(buffer(connection)))
            assertEquals(if (reverse) 2 else 4, Selection.getSelectionEnd(buffer(connection)))
            assertTrue(deleted.isEmpty())
            val previous = sent.size
            assertTrue(connection.finishComposingText())
            assertEquals(previous + 1, sent.size); assertEquals("a中文d", sent.last())
            assertTrue(connection.finishComposingText()); assertEquals(previous + 1, sent.size)
        }
    }

    @Test fun emojiSurroundingSelectionStaysWholeAndOversizedDeletionKeepsTheAnchor() = editor { view, connection, sent, deleted ->
        for (codePoints in listOf(false, true)) for (reverse in listOf(false, true)) {
            connection.setComposingText("A🚀中😀Z", 1)
            connection.setSelection(if (reverse) 5 else 4, if (reverse) 4 else 5)
            val delete: (Int, Int) -> Boolean = if (codePoints) connection::deleteSurroundingTextInCodePoints else connection::deleteSurroundingText
            assertTrue(delete(1, 1))
            assertEquals("A中Z", view.text.toString())
            assertEquals("中", connection.getSelectedText(0).toString())
            assertTrue(delete(4096, 4096))
            assertEquals("中", view.text.toString()); assertEquals("\u200b中", buffer(connection).toString())
            assertEquals(if (reverse) 2 else 1, Selection.getSelectionStart(buffer(connection)))
            assertEquals(if (reverse) 1 else 2, Selection.getSelectionEnd(buffer(connection)))
            assertTrue(deleted.isEmpty())
            connection.finishComposingText(); assertEquals("中", sent.last())
        }
        val before = sent.toList()
        assertTrue(connection.deleteSurroundingText(2, 1))
        assertEquals(listOf(2 to 1), deleted); assertEquals(before, sent)
    }

    @Test fun invalidSurrogateSelectionCannotDamageOrLeakThePendingComposition() = editor { view, connection, sent, deleted ->
        connection.setComposingText("A🚀中", 1)
        connection.setSelection(3, 4) // Starts at the low surrogate.
        assertFalse(connection.deleteSurroundingText(1, 1))
        assertFalse(connection.deleteSurroundingTextInCodePoints(1, 1))
        assertEquals("A🚀中", view.text.toString()); assertTrue(sent.isEmpty()); assertTrue(deleted.isEmpty())
        connection.setSelection(4, 4)
        assertTrue(connection.deleteSurroundingTextInCodePoints(1, 0))
        assertEquals("A中", view.text.toString())
        connection.finishComposingText(); assertEquals(listOf("A中"), sent)
    }
    @Test fun hardwareBackspaceDeletesSelectionOrWholeGraphemeWithoutCommitting() = editor { view, connection, sent, deleted ->
        for (reverse in listOf(false, true)) {
            connection.setComposingText("ab中文cd", 1)
            connection.setSelection(if (reverse) 5 else 3, if (reverse) 3 else 5)
            assertTrue(connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL)))
            assertEquals("abcd", view.text.toString())
            assertEquals(3, Selection.getSelectionStart(buffer(connection)))
            connection.finishComposingText(); assertEquals("abcd", sent.last())
        }
        for (cluster in listOf("e\u0301", "👩‍💻", "🇮🇳", "👍🏽")) {
            connection.setComposingText("A$cluster", 1)
            val count = sent.size
            assertTrue(connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL)))
            assertEquals("A", view.text.toString()); assertEquals(count, sent.size); assertTrue(deleted.isEmpty())
            connection.finishComposingText(); assertEquals("A", sent.last())
        }
        connection.setComposingText("🚀", 1)
        assertTrue(connection.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL)))
        assertEquals("\u200b", buffer(connection).toString()); assertTrue(deleted.isEmpty())
    }

}
