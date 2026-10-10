package io.github.docmorphic.cmuxapp

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class TaskComposerPromptTest {
    @Test fun initialFocusWaitsForPresentationInputAndWindowThenIsConsumed() {
        val request = TaskComposerInitialFocus()
        assertFalse(request.ready(true, true, false, false, false))
        assertFalse(request.ready(true, true, true, false, true))
        assertFalse(request.ready(true, true, true, true, false))
        assertTrue(request.ready(true, true, true, true, true))
        request.focused()
        assertFalse(request.ready(true, true, true, true, true))
    }

    @Test fun disabledOrHiddenPresentationNeverRearmsOnReturn() {
        for (hidden in listOf(false, true)) {
            val request = TaskComposerInitialFocus()
            assertFalse(request.ready(!hidden, hidden, false, false, false))
            assertFalse(request.ready(true, true, true, true, true))
            assertTrue(request.finished)
        }
    }

    @Test fun dismissalAndRestoredFinishedPresentationCannotStealFocus() {
        val dismissed = TaskComposerInitialFocus()
        dismissed.cancel()
        assertFalse(dismissed.ready(true, true, true, true, true))
        val restored = TaskComposerInitialFocus(finished = dismissed.finished)
        assertFalse(restored.ready(true, true, true, true, true))
        assertTrue(TaskComposerInitialFocus().ready(true, true, true, true, true))
    }

    @Test fun unrelatedRefreshPreservesExactSelectionAndImeComposition() {
        val value = TextFieldValue("hello 中 world", TextRange(6, 7), TextRange(6, 7))
        assertSame(value, taskComposerPromptValue(value, value.text))
    }

    @Test fun externalReplacementClampsBothEndpointsAndRetiresComposition() {
        val value = TextFieldValue("hello 中 world", TextRange(12, 3), TextRange(6, 7))
        val changed = taskComposerPromptValue(value, "hi")
        assertEquals("hi", changed.text)
        assertEquals(TextRange(2, 2), changed.selection)
        assertNull(changed.composition)
        assertEquals(TextRange.Zero, taskComposerPromptValue(value, "").selection)
    }

    @Test fun utf16SelectionAndDirectionSurviveLongerExternalText() {
        val value = TextFieldValue("👩🏽‍💻 hello", TextRange(7, 0))
        val changed = taskComposerPromptValue(value, value.text + " 中")
        assertEquals(value.selection, changed.selection)
        assertNull(changed.composition)
    }
}
