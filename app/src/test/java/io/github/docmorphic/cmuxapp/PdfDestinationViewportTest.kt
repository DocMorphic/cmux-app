package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class PdfDestinationViewportTest {
    @Test fun fitPageUsesBothViewportDimensionsAndCentersShortPages() {
        val result = PdfDestinationViewport.resolve(PdfLinkTarget.Page(0, 0f, fit = PdfDestinationFit.PAGE),
            300, 200, 900f, 3f, 4f, viewportHeight = 1200f)
        assertEquals(1f, result.transform.scale, 0f)
        assertEquals(0f, result.scrollFraction, 0f)
        assertEquals(.5f, result.topInsetFraction, 0f) // 300 px before the 600 px page.
        val tall = PdfDestinationViewport.resolve(PdfLinkTarget.Page(0, 0f, fit = PdfDestinationFit.PAGE),
            300, 600, 900f, 3f, 4f, viewportHeight = 900f)
        assertEquals(.5f, tall.transform.scale, 0f)
    }
    @Test fun fitWidthAndHeightUseTheWholeDocumentScaleForMixedPages() {
        val width = PdfDestinationViewport.resolve(PdfLinkTarget.Page(0, 50f, fit = PdfDestinationFit.WIDTH),
            200, 600, 900f, 3f, 1f, viewportHeight = 1200f, documentWidth = 400)
        assertEquals(2f, width.transform.scale, 0f)
        assertEquals(100f / 600, width.scrollFraction, .0001f)
        val height = PdfDestinationViewport.resolve(PdfLinkTarget.Page(0, 0f, 100f, fit = PdfDestinationFit.HEIGHT),
            400, 100, 900f, 3f, 1f, viewportHeight = 900f)
        assertEquals(4f, height.transform.scale, 0f)
        assertEquals(0f, height.scrollFraction, 0f)
        assertEquals(.5f, height.transform.x, 0f)
    }
    @Test fun fitRectangleCentersTheOtherDimensionWithoutClippingMagnifiedPageHeight() {
        val rect = PdfTextBounds(200f, 20f, 250f, 80f)
        val result = PdfDestinationViewport.resolve(PdfLinkTarget.Page(0, 0f, fit = PdfDestinationFit.RECTANGLE, rectangle = rect),
            300, 100, 900f, 3f, 1f, viewportHeight = 900f)
        assertEquals(5f, result.transform.scale, .0001f)
        assertEquals(-1.25f, result.transform.x, .0001f)
        assertEquals(1f, result.scrollFraction, .0001f)
        // At 5x the full page is 1500px high: rect [20,80] spans exactly the viewport.
        assertEquals(900f, (rect.bottom - rect.top) * 3 * result.transform.scale, .001f)
    }
    @Test fun xyzZoomUsesDocumentPointsAndPlacesDestinationAtLeadingEdge() {
        val target = PdfLinkTarget.Page(1, 200f, 150f, 4f)
        val result = PdfDestinationViewport.resolve(target, 300, 600, 900f, 3f, 1f)
        assertEquals(4f, result.transform.scale, .0001f)
        assertEquals(0f, (.5f - .5f) * result.transform.scale + result.transform.x + .5f, .0001f)
        assertEquals(result.scrollFraction, 200f / 600 * result.transform.scale, .0001f)
    }
    @Test fun destinationWithoutZoomRetainsReadingMagnification() {
        val result = PdfDestinationViewport.resolve(PdfLinkTarget.Page(0, 50f), 300, 600, 900f, 3f, 3f)
        assertEquals(3f, result.transform.scale, 0f)
        assertEquals(1f, result.transform.x, .0001f)
        assertEquals(50f / 600 * 3f, result.scrollFraction, .0001f)
    }
    @Test fun documentZoomOutStaysCenteredAndKeepsPagingAvailable() {
        val result = PdfDestinationViewport.resolve(PdfLinkTarget.Page(0, 0f, 0f, .5f), 300, 600, 900f, 3f, 1f)
        assertEquals(.5f, result.transform.scale, 0f)
        assertEquals(0f, result.transform.x, 0f)
        assertEquals(0f, result.transform.y, 0f)
        assertEquals(0f, result.scrollFraction, 0f)
        assertTrue(result.transform.atMinimum)
        assertEquals(.25f, result.transform.transform(.5f, 0f, 0f, minimumScale = .125f).scale, 0f)
    }
    @Test fun edgesAndMalformedCoordinatesCannotMoveThePageOutOfItsBounds() {
        for (point in listOf(-100f, 0f, 300f, 10000f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val result = PdfDestinationViewport.resolve(PdfLinkTarget.Page(0, point, point, 100f), 300, 600, 900f, 3f, 1f)
            assertEquals(8f, result.transform.scale, 0f)
            assertTrue(result.transform.x in -3.5f..3.5f)
            assertTrue(result.transform.y in -3.5f..3.5f)
            assertTrue(result.scrollFraction in 0f..8f)
        }
    }
    @Test fun densityAndFittedWidthSetAbsolutePdfMagnification() {
        val target = PdfLinkTarget.Page(0, 200f, 150f, 2f)
        val portrait = PdfDestinationViewport.resolve(target, 300, 600, 900f, 3f, 1f)
        val landscape = PdfDestinationViewport.resolve(target, 300, 600, 1800f, 3f, 1f)
        assertEquals(portrait.transform.scale * 900 / 300, landscape.transform.scale * 1800 / 300, .0001f)
    }
}
