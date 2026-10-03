package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class TerminalAccessibilityScrollTest {
    private fun local(position: Double = 0.0, reveal: Float = 0f, budget: Float = 0f) =
        TerminalAccessibilityScroll(true, 30, position, reveal, budget, 20f, 200)

    @Test fun primaryRangeUsesAndroidTopOriginAndOverlappingPages() {
        val latest = local()
        assertEquals(600f, latest.maximum); assertEquals(600f, latest.value)
        assertTrue(latest.older); assertFalse(latest.newer)
        assertEquals(9.0, latest.pageRows, 0.0)
        val oldest = local(30.0)
        assertEquals(0f, oldest.value); assertFalse(oldest.older); assertTrue(oldest.newer)
        assertEquals(-30.0, oldest.latestRows, 0.0)
    }

    @Test fun keyboardTopRevealExtendsTheSameAccessibilityAxis() {
        val scroll = local(30.0, 100f, 200f)
        assertEquals(800f, scroll.maximum); assertEquals(100f, scroll.value)
        assertTrue(scroll.older); assertTrue(scroll.newer)
        assertEquals(-35.0, scroll.latestRows, 0.0)
        assertEquals(2.0, scroll.rowsForPixels(-40f), 0.0)
        assertEquals(-2.0, scroll.rowsForPixels(40f), 0.0)
    }

    @Test fun alternateScreenOffersWheelDirectionsWithoutInventingHistory() {
        val scroll = TerminalAccessibilityScroll(false, 0, 0.0, 0f, 0f, 20f, 200)
        assertTrue(scroll.older); assertTrue(scroll.newer)
        assertEquals(0f, scroll.maximum); assertEquals(0.0, scroll.latestRows, 0.0)
    }

    @Test fun invalidCoordinatesCannotCreateScrollInput() {
        val invalid = local().copy(cellHeight = Float.NaN)
        assertFalse(invalid.older); assertFalse(invalid.newer)
        assertEquals(0.0, invalid.rowsForPixels(10f), 0.0)
        assertEquals(0.0, local().rowsForPixels(Float.POSITIVE_INFINITY), 0.0)
        assertEquals(0f, local(Double.NaN).value)
        assertEquals(1.0, local().copy(visibleHeight = 1).pageRows, 0.0)
    }
}
