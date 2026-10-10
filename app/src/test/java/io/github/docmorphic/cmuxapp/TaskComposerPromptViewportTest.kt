package io.github.docmorphic.cmuxapp

import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Test

class TaskComposerPromptViewportTest {
    @Test fun manualDragAndFlingKeepTheirFinalOffset() {
        val viewport = TaskComposerPromptViewport()
        viewport.editor("long prompt", TextRange(11))
        viewport.beginDrag(400)
        viewport.scroll(600, true)
        viewport.endDrag(600, true)
        viewport.scroll(750, true)
        assertNull(viewport.target(1000))
        viewport.scroll(800, false)
        assertEquals(800, viewport.target(1000))
    }

    @Test fun layoutClampingDoesNotDiscardTheUserOwnedOffset() {
        val viewport = TaskComposerPromptViewport(800)
        viewport.editor("prompt", TextRange(6))
        assertEquals(500, viewport.target(500))
        assertEquals(800, viewport.target(1200))
        assertEquals(0, viewport.target(-1))
        assertEquals(800, viewport.manualOffset)
    }

    @Test fun unrelatedRefreshAndAutomaticCaretScrollCannotReplaceManualOffset() {
        val viewport = TaskComposerPromptViewport(400)
        viewport.editor("prompt", TextRange(6))
        viewport.editor("prompt", TextRange(6))
        viewport.scroll(900, true)
        viewport.scroll(900, false)
        assertEquals(400, viewport.target(1000))
    }

    @Test fun typingReleasesManualOwnershipBeforeCaretLayout() {
        val viewport = TaskComposerPromptViewport(400)
        viewport.editor("prompt", TextRange(6))
        viewport.editor("prompt 中", TextRange(8))
        assertNull(viewport.target(1000))
    }

    @Test fun movingEitherSelectionEndpointReleasesManualOwnership() {
        for (range in listOf(TextRange(2, 6), TextRange(6, 2))) {
            val viewport = TaskComposerPromptViewport(400)
            viewport.editor("prompt", TextRange(6))
            viewport.editor("prompt", range)
            assertNull(viewport.target(1000))
        }
    }

    @Test fun cancelledOrNonScrollingDragRetainsItsLastPosition() {
        val viewport = TaskComposerPromptViewport()
        viewport.beginDrag(123)
        viewport.endDrag(123, false)
        assertEquals(123, viewport.target(1000))
        assertFalse(viewport.tracking)
    }

    @Test fun schedulingGapBetweenDragAndFlingCannotRestoreAnOlderOffset() {
        val viewport = TaskComposerPromptViewport()
        viewport.beginDrag(123)
        viewport.scroll(200, true)
        viewport.scroll(200, false)
        assertNull(viewport.target(1000))
        viewport.endDrag(300, true)
        viewport.scroll(400, false)
        assertEquals(400, viewport.target(1000))
    }
}
