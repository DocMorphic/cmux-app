package io.github.docmorphic.cmuxapp

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class PdfSelectionToolbarTest {
    private fun range(a: Int = 0, b: Int = 0) = PdfTextSelection(PdfTextPosition(a, 1), PdfTextPosition(b, 8))
    private fun page(index: Int, top: Float = 0f, bottom: Float = 400f) =
        PdfSelectionViewportPage(index, PdfTextBounds(0f, top, 300f, bottom))
    @Test fun sameLineAndRtlEndpointsAnchorOnlyAroundTheSelectedText() {
        val expected = PdfTextBounds(45f, 110f, 155f, 130f)
        assertEquals(expected, pdfSelectionToolbarBounds(range(), Offset(50f, 120f), Offset(150f, 120f), listOf(page(0)), 300f, 500f, 10f))
        assertEquals(expected, pdfSelectionToolbarBounds(range(), Offset(150f, 120f), Offset(50f, 120f), listOf(page(0)), 300f, 500f, 10f))
    }
    @Test fun multilineAndRotatedBaselinesUseTheVisiblePageWidth() {
        assertEquals(PdfTextBounds(0f, 90f, 300f, 260f), pdfSelectionToolbarBounds(range(),
            Offset(50f, 250f), Offset(50f, 100f), listOf(page(0)), 300f, 500f, 10f))
    }
    @Test fun hiddenEndpointsStillAnchorOnSelectedIntermediatePages() {
        assertEquals(PdfTextBounds(0f, 20f, 300f, 420f), pdfSelectionToolbarBounds(range(0, 2),
            null, null, listOf(page(1, 20f, 420f)), 300f, 500f, 10f))
    }
    @Test fun scrollClippingAndReversedRangeKeepToolbarInsideTheViewport() {
        val selection = range(0, 1)
        val pages = listOf(page(0, -100f, 200f), page(1, 208f, 608f))
        val expected = PdfTextBounds(0f, 0f, 300f, 350f)
        assertEquals(expected, pdfSelectionToolbarBounds(selection, Offset(10f, -50f), Offset(90f, 340f), pages, 300f, 350f, 10f))
        assertEquals(expected, pdfSelectionToolbarBounds(PdfTextSelection(selection.focus, selection.anchor),
            Offset(10f, -50f), Offset(90f, 340f), pages, 300f, 350f, 10f))
    }
    @Test fun offscreenOrCollapsedSelectionHasNoToolbarAnchor() {
        assertNull(pdfSelectionToolbarBounds(range(), null, null, listOf(page(1)), 300f, 500f, 10f))
        assertNull(pdfSelectionToolbarBounds(range(), Offset(500f, 20f), Offset(600f, 20f), listOf(page(0)), 300f, 500f, 10f))
        assertNull(pdfSelectionToolbarBounds(PdfTextSelection(PdfTextPosition(0, 1), PdfTextPosition(0, 1)),
            Offset(10f, 20f), Offset(10f, 20f), listOf(page(0)), 300f, 500f, 10f))
        assertNull(pdfSelectionToolbarBounds(range(), null, null, listOf(page(0)), Float.NaN, 500f, 10f))
    }
    @Test fun exclusiveEndAtNextPageStartDoesNotSelectThatPage() {
        val selection = PdfTextSelection(PdfTextPosition(0, 1), PdfTextPosition(1, 0))
        assertNull(pdfSelectionToolbarBounds(selection, null, Offset(0f, 20f), listOf(page(1)), 300f, 500f, 10f))
    }
}
