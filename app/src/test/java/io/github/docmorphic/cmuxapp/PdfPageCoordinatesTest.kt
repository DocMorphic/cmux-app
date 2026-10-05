package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class PdfPageCoordinatesTest {
    @Test fun croppedAndScaledPointsUseTopLeftRendererCoordinates() {
        val page = PdfPageCoordinates(10f, 20f, 300f, 400f, 0, 600, 800)
        assertEquals(0f to 0f, page.point(10f, 420f))
        assertEquals(600f to 800f, page.point(310f, 20f))
        assertEquals(60f to 100f, page.point(40f, 370f))
    }
    @Test fun allRightAngleRotationsAndClippedBounds() {
        val page = PdfPageCoordinates(0f, 0f, 300f, 400f, 90, 400, 300)
        assertEquals(200f to 30f, page.point(30f, 200f))
        assertEquals(270f to 200f, page.copy(rotation = 180, renderedWidth = 300, renderedHeight = 400).point(30f, 200f))
        assertEquals(200f to 270f, page.copy(rotation = 270).point(30f, 200f))
        assertEquals(PdfTextBounds(0f, 0f, 200f, 30f), page.bounds(listOf(-10f to -10f, 30f to 200f)))
    }
    @Test fun invalidAndOutOfPageGeometryDoesNotCreateClickTargets() {
        val page = PdfPageCoordinates(0f, 0f, 300f, 400f, 0, 300, 400)
        assertNull(page.point(Float.NaN, 1f))
        assertNull(page.copy(width = 0f).point(1f, 1f))
        assertNull(page.copy(rotation = 45).point(1f, 1f))
        assertNull(page.bounds(listOf(400f to 400f, 500f to 500f)))
    }
}
