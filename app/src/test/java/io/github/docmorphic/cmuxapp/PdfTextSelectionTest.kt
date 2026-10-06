package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class PdfTextSelectionTest {
    private fun page(text: String) = PdfCompatibilityTextPage(text, text.indices.filter { !text[it].isWhitespace() }
        .map { PdfTextRun(it, it + 1, listOf(PdfTextBounds(it * 10f, 0f, it * 10f + 9f, 10f))) })
    @Test fun crossingHandlesPreservesReadingOrderAndIncludesIntermediatePages() {
        val range = PdfTextSelection(PdfTextPosition(3, 4), PdfTextPosition(1, 6))
        assertEquals(6 until 10, range.offsets(1, 10)); assertEquals(0 until 15, range.offsets(2, 15))
        assertEquals(0 until 4, range.offsets(3, 20)); assertNull(range.offsets(0, 100)); assertNull(range.offsets(4, 100))
        assertNull(range.offsets(2, 0))
        assertNull(PdfTextSelection(PdfTextPosition(0, 2), PdfTextPosition(0, 2)).offsets(0, 10))
    }
    @Test fun longPressAndDraggingUseWholeWordsThenCompleteGlyphRuns() {
        val text = page("hello world")
        assertEquals(0 until 5, text.wordOffsets(22f, 5f))
        assertEquals(6, text.nearestOffset(61f, 5f)); assertEquals(11, text.nearestOffset(200f, 5f))
        val selection = PdfTextSelection(PdfTextPosition(0, 2), PdfTextPosition(0, 8))
        assertEquals(5, text.selectionBounds(selection, 0).size)
        assertEquals(PdfTextCaret(0, 20f, 10f), text.caret(selection.start, true))
        assertEquals(PdfTextCaret(0, 79f, 10f), text.caret(selection.end, false))
    }
    @Test fun ligatureAndSurrogateRunsCannotYieldAnInventedInnerEndpoint() {
        val ligature = PdfCompatibilityTextPage("ffi", listOf(PdfTextRun(0, 3, listOf(PdfTextBounds(0f, 0f, 30f, 10f)))))
        assertEquals(0, ligature.nearestOffset(14f, 5f)); assertEquals(3, ligature.nearestOffset(16f, 5f))
        val emoji = page("😀 next")
        assertEquals(2, emoji.nearestOffset(9f, 5f)); assertEquals(0, emoji.nearestOffset(11f, 5f))
        assertNull(emoji.nearestOffset(Float.NaN, 0f)); assertNull(page("").nearestOffset(0f, 0f))
    }
    @Test fun rotatedAndSkewedGlyphBaselinesPlaceEndpointsOnTheActualAdvanceAxis() {
        val value = PdfCompatibilityTextPage("A", listOf(PdfTextRun(0, 1, listOf(PdfTextBounds(10f, 10f, 30f, 40f)),
            PdfTextBaseline(10f, 10f, 20f, 40f))))
        assertEquals(0, value.nearestOffset(15f, 12f)); assertEquals(1, value.nearestOffset(15f, 38f))
        assertEquals(PdfTextCaret(2, 10f, 10f), value.caret(PdfTextPosition(2, 0), true))
        assertEquals(PdfTextCaret(2, 20f, 40f), value.caret(PdfTextPosition(2, 1), false))
    }
    @Test fun rtlRunEdgesReverseWithoutReversingCopiedLogicalOffsets() {
        val value = PdfCompatibilityTextPage("שלום", listOf(PdfTextRun(0, 4, listOf(PdfTextBounds(10f, 10f, 50f, 20f)))))
        assertEquals(0, value.nearestOffset(49f, 15f)); assertEquals(4, value.nearestOffset(11f, 15f))
        assertEquals(PdfTextCaret(0, 50f, 20f), value.caret(PdfTextPosition(0, 0), true))
        assertEquals(PdfTextCaret(0, 10f, 20f), value.caret(PdfTextPosition(0, 4), false))
    }
}
