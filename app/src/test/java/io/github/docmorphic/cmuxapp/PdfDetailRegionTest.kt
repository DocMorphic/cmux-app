package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class PdfDetailRegionTest {
    private fun planned(left: Float = -3000f, top: Float = -6000f) = checkNotNull(
        pdfDetailRegion(300, 400, 1080f, 1800f, left, top, 28.8f, 7.2f))
    @Test fun magnifiedRegionCoversTheViewportWithoutAllocatingTheWholePage() {
        val value = planned()
        assertTrue(value.scale >= 28.8f)
        assertTrue(value.left / value.scale <= 3000f / 28.8f)
        assertTrue(value.top / value.scale <= 6000f / 28.8f)
        assertTrue((value.left + value.width) / value.scale >= 4080f / 28.8f)
        assertTrue((value.top + value.height) / value.scale >= 7800f / 28.8f)
        assertTrue(value.width.toLong() * value.height <= PdfDetailRegion.MAX_PIXELS)
        assertTrue(value.width < 300 * value.scale); assertTrue(value.height < 400 * value.scale)
    }
    @Test fun smallPanWithinOverscanReusesTheSameRegion() {
        assertEquals(planned(), planned(-3001f, -6001f))
        assertNotEquals(planned(), planned(-3200f, -6200f))
    }
    @Test fun pageEdgesClipOverscanWithoutLosingVisiblePixels() {
        val value = checkNotNull(pdfDetailRegion(300, 400, 1080f, 1800f, 100f, 100f, 10f, 2f))
        assertEquals(0, value.left); assertEquals(0, value.top)
        assertTrue((value.left + value.width) / value.scale >= 98f)
        assertTrue((value.top + value.height) / value.scale >= 170f)
        val end = checkNotNull(pdfDetailRegion(300, 400, 1080f, 1800f, -2500f, -3500f, 10f, 2f))
        assertTrue(end.left + end.width <= kotlin.math.ceil(300 * end.scale).toInt() + 1)
        assertTrue(end.top + end.height <= kotlin.math.ceil(400 * end.scale).toInt() + 1)
    }
    @Test fun longDocumentsAndLargeDisplaysStayWithinAllocationAndTextureBounds() {
        for (value in listOf(checkNotNull(pdfDetailRegion(500, 100_000, 1080f, 1800f, -2000f, -500_000f, 10f, 2f)),
            checkNotNull(pdfDetailRegion(2000, 3000, 16_000f, 9000f, -3000f, -6000f, 20f, .5f)))) {
            assertTrue(value.width <= PdfDetailRegion.MAX_EDGE); assertTrue(value.height <= PdfDetailRegion.MAX_EDGE)
            assertTrue(value.width.toLong() * value.height <= PdfDetailRegion.MAX_PIXELS)
        }
    }
    @Test fun previewAlreadySharpOrPageOutsideViewportNeedsNoDetail() {
        assertNull(pdfDetailRegion(300, 400, 1080f, 1800f, 0f, 0f, 3.6f, 7.2f))
        assertNull(pdfDetailRegion(300, 400, 1080f, 1800f, 0f, 2000f, 10f, 2f))
        assertNull(pdfDetailRegion(300, 400, 1080f, 1800f, -4000f, 0f, 10f, 2f))
    }
    @Test fun invalidOrOverflowingGeometryCannotBecomeAnAllocation() {
        assertNull(pdfDetailRegion(0, 400, 1080f, 1800f, 0f, 0f, 10f, 2f))
        assertNull(pdfDetailRegion(300, 400, Float.NaN, 1800f, 0f, 0f, 10f, 2f))
        assertNull(pdfDetailRegion(300, 400, 1080f, 1800f, Float.POSITIVE_INFINITY, 0f, 10f, 2f))
        assertNull(pdfDetailRegion(Int.MAX_VALUE, Int.MAX_VALUE, 1080f, 1800f, 0f, 0f, 10f, 2f))
    }
}
