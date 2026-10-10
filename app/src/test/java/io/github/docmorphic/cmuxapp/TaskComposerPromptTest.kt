package io.github.docmorphic.cmuxapp

import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.input.TextFieldState
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

    @Test fun unrelatedRefreshPreservesExactEditorTextAndSelection() {
        val value = TextFieldState("hello 中 world", TextRange(6, 7))
        val binding = TaskComposerPromptBinding(value, value.text.toString())
        val before = value.text
        binding.sync(value.text.toString()) { fail("Unchanged text is not a draft edit"); false }
        assertSame(before, value.text)
        assertEquals(TextRange(6, 7), value.selection)
    }

    @Test fun externalReplacementClampsBothEndpointsAndRetiresComposition() {
        val value = TextFieldState("hello 中 world", TextRange(12, 3))
        val binding = TaskComposerPromptBinding(value, value.text.toString())
        binding.sync("hi") { fail("External changes must not publish back"); false }
        assertEquals("hi", value.text.toString())
        assertEquals(TextRange(2, 2), value.selection)
        assertNull(value.composition)
        binding.sync("") { false }
        assertEquals(TextRange.Zero, value.selection)
    }

    @Test fun utf16SelectionAndDirectionSurviveLongerExternalText() {
        val value = TextFieldState("👩🏽‍💻 hello", TextRange(7, 0))
        val binding = TaskComposerPromptBinding(value, value.text.toString())
        binding.sync(value.text.toString() + " 中") { false }
        assertEquals(TextRange(7, 0), value.selection)
        assertNull(value.composition)
    }

    @Test fun lastEditPublishesBeforeAnActionAndRepeatedObservationDoesNotReplayIt() {
        val value = TextFieldState("before")
        val binding = TaskComposerPromptBinding(value, "before")
        value.edit { append(" 中") }
        var durable = "before"; var writes = 0
        binding.sync(durable) { durable = it; writes++; true }
        assertEquals("before 中", durable)
        binding.sync(durable) { writes++; true }
        assertEquals(1, writes)
    }

    @Test fun retiredOwnerCannotPublishPendingEdits() {
        val value = TextFieldState("before")
        val binding = TaskComposerPromptBinding(value, "before")
        value.edit { append(" pending") }
        binding.sync(null) { fail("Retired owner must not write"); false }
        assertEquals("before pending", value.text.toString())
    }

    @Test fun rejectedEditRestoresDurableTextAndDoesNotReappearOnNextSync() {
        val value = TextFieldState("before")
        val binding = TaskComposerPromptBinding(value, "before")
        value.edit { append(" pending") }
        binding.sync("before") { false }
        assertEquals("before", value.text.toString())
        binding.sync("before") { fail("Rejected text must not replay"); false }
    }
}
