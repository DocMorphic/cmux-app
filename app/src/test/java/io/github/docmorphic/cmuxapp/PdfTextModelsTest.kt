package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class PdfTextModelsTest {
    @Test fun inverseCoordinatesFollowZoomAndPan() {
        val transform = PreviewZoomTransform(3f, .25f, -.3f)
        val expectedX = .42f; val expectedY = .63f
        val screenX = ((expectedX - .5f) * transform.scale + transform.x + .5f) * 900
        val screenY = ((expectedY - .5f) * transform.scale + transform.y + .5f) * 1200
        val point = checkNotNull(transform.contentPoint(screenX, screenY, 900, 1200))
        assertEquals(expectedX, point.first, .00001f); assertEquals(expectedY, point.second, .00001f)
    }
    @Test fun invalidAndOutsideCoordinatesCannotSelectPdfContent() {
        val transform = PreviewZoomTransform()
        assertNull(transform.contentPoint(10f, 10f, 0, 100))
        assertNull(transform.contentPoint(Float.NaN, 10f, 100, 100))
        assertNull(transform.contentPoint(-1f, 10f, 100, 100))
        assertNull(transform.contentPoint(101f, 10f, 100, 100))
    }
    @Test fun pdfBoundsRejectReversedOrNonfiniteRectangles() {
        assertTrue(PdfTextBounds(5f, 10f, 15f, 20f).contains(8f, 12f))
        assertFalse(PdfTextBounds(5f, 10f, 15f, 20f).contains(8f, 22f))
        assertFalse(PdfTextBounds(15f, 10f, 5f, 20f).contains(8f, 12f))
        assertFalse(PdfTextBounds(5f, 10f, Float.POSITIVE_INFINITY, 20f).contains(8f, 12f))
    }
    @Test fun onlyExplicitUserLinkSchemesCanLeaveThePdf() {
        listOf("https://cmux.com/docs", "http://localhost:8080/a", "mailto:a@example.com", "tel:+123456").forEach {
            assertEquals(PdfLinkTarget.External(it), PdfLinkTarget.external(it))
        }
        listOf("file:///private/key", "content://provider/document", "intent://launch", "javascript:alert(1)",
            "cmux://pair?secret=x", "//example.com", "https://", "https://user:password@example.com", "https://example.com\n").forEach {
            assertNull(it, PdfLinkTarget.external(it))
        }
    }
    @Test fun nextPreviousMatchesWrapWithoutIntegerOverflow() {
        assertEquals(0, pdfMatchStep(0, 1, 0)); assertEquals(2, pdfMatchStep(0, -1, 3))
        assertEquals(0, pdfMatchStep(2, 1, 3)); assertEquals(0, pdfMatchStep(Int.MAX_VALUE, 1, 2))
    }
}
