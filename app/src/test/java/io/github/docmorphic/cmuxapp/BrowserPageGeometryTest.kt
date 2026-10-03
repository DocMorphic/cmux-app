package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class BrowserPageGeometryTest {
    @Test fun widthFitPreservesAspectAndRejectsLetterboxClicks() {
        val transform = BrowserPageTransform(400.0, 800.0, 800.0, 400.0)
        assertEquals(BrowserRect(0.0, 300.0, 400.0, 200.0), transform.rect)
        assertNull(transform.pagePoint(200.0, 100.0))
        assertNull(transform.pagePoint(400.0, 400.0))
        assertEquals(BrowserPoint(400.0, 200.0), transform.pagePoint(200.0, 400.0))
        assertEquals(BrowserPoint(400.0, 200.0), transform.scrollAnchor(200.0, 100.0))
    }
    @Test fun localZoomAndPanMapVisiblePixelsBackToMacPage() {
        val transform = BrowserPageTransform(400.0, 800.0, 800.0, 1200.0, 2.0, 40.0, 75.0)
        assertEquals(BrowserRect(-240.0, -275.0, 800.0, 1200.0), transform.rect)
        assertEquals(BrowserPoint(315.0, 480.0), transform.pagePoint(75.0, 205.0))
        assertNull(transform.pagePoint(561.0, 205.0))
    }
    @Test fun tallPageIsCroppedAroundCenterWithoutVerticalDistortion() {
        val transform = BrowserPageTransform(400.0, 600.0, 400.0, 1200.0)
        assertEquals(BrowserRect(0.0, -300.0, 400.0, 1200.0), transform.rect)
        assertEquals(BrowserPoint(200.0, 300.0), transform.pagePoint(200.0, 0.0))
        assertEquals(BrowserPoint(200.0, 600.0), transform.pagePoint(200.0, 300.0))
    }
    @Test fun wheelAxesUseSameWidthFitScaleRegardlessOfLocalZoom() {
        val transform = BrowserPageTransform(400.0, 700.0, 800.0, 1000.0, 3.0)
        assertEquals(BrowserPoint(10.0, 40.0), transform.pageDelta(5.0, 20.0))
        assertEquals(BrowserPoint(-10.0, -40.0), transform.pageDelta(-5.0, -20.0))
    }
    @Test fun invalidGeometryDoesNotProduceClicks() {
        assertNull(BrowserPageTransform(0.0, 100.0, 400.0, 200.0).pagePoint(0.0, 0.0))
        assertNull(BrowserPageTransform(400.0, 100.0, Double.NaN, 200.0).rect)
        assertNull(BrowserPageTransform(400.0, 100.0, 400.0, 200.0).pagePoint(Double.NaN, 0.0))
    }
    @Test fun immediateTapCountsChainAtIosTimeAndDistanceThresholds() {
        val counter = BrowserTapCounter()
        assertEquals(1, counter.register(BrowserPoint(10.0, 20.0), 1_000))
        assertEquals(2, counter.register(BrowserPoint(38.0, 20.0), 1_450))
        assertEquals(3, counter.register(BrowserPoint(38.0, 20.0), 1_500))
        assertEquals(1, counter.register(BrowserPoint(38.0, 20.0), 1_951))
        assertEquals(1, counter.register(BrowserPoint(67.0, 20.0), 2_000))
        assertEquals(1, counter.register(BrowserPoint(67.0, 20.0), 1_999))
    }
    @Test fun clickCountTravelsOnTheNativePointerRpc() {
        val input = BrowserInput.Click(12.0, 34.0, 3)
        assertEquals("mobile.browser.input.pointer", input.method)
        val params = input.parameters("browser-two")
        assertEquals(3, params.getInt("click_count")); assertEquals("browser-two", params.getString("panel_id"))
        assertEquals("click", params.getString("kind")); assertEquals("left", params.getString("button"))
    }
}
