package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class PdfPageCoordinatesTest {
    @Test fun inverseCoordinatesRetainPdfUserSpaceAcrossCropScaleAndRotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val quarter = rotation % 180 == 90
            val page = PdfPageCoordinates(10f, 20f, 300f, 400f, rotation, if (quarter) 800 else 600, if (quarter) 600 else 800)
            for (point in listOf(40f to 370f, -10f to 900f, 310f to 20f)) {
                val rendered = checkNotNull(page.point(point.first, point.second))
                assertEquals(point, page.userPoint(rendered.first, rendered.second))
            }
            assertNull(page.userPoint(Float.NaN, 1f))
        }
    }
    @Test fun nullCoordinatesRetainUnrotatedValuesWhenTheTargetRotationDiffers() {
        val source = PdfPageCoordinates(10f, 20f, 300f, 400f, 90, 400, 300)
        val target = PdfPageCoordinates(20f, 30f, 300f, 400f, 270, 400, 300)
        val current = checkNotNull(source.userPoint(350f, 30f))
        assertEquals(target.point(40f, 370f), PdfRetainedCoordinates(target, null, null).resolve(current))
        assertEquals(target.point(100f, 370f), PdfRetainedCoordinates(target, 100f, null).resolve(current))
        assertEquals(target.point(40f, 200f), PdfRetainedCoordinates(target, null, 200f).resolve(current))
    }
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
