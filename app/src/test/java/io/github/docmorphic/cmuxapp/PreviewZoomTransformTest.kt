package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class PreviewZoomTransformTest {
    @Test fun doubleTapCentersTappedPointAtIosThreeTimesScale() {
        val value = PreviewZoomTransform().doubleTap(-.2f, .1f)
        assertEquals(3f, value.scale, .0001f)
        assertEquals(0f, -.2f * value.scale + value.x, .0001f)
        assertEquals(0f, .1f * value.scale + value.y, .0001f)
        assertEquals(PreviewZoomTransform(), value.doubleTap(.4f, .4f))
    }
    @Test fun pinchKeepsPointUnderMovingFingers() {
        val before = PreviewZoomTransform(2f, .1f, -.1f)
        val imageX = (.2f - before.x) / before.scale
        val imageY = (-.15f - before.y) / before.scale
        val after = before.transform(1.5f, .05f, -.03f, .2f, -.15f)
        assertEquals(.25f, imageX * after.scale + after.x, .0001f)
        assertEquals(-.18f, imageY * after.scale + after.y, .0001f)
    }
    @Test fun scaleClampingUsesActualRatioForFocalPoint() {
        val before = PreviewZoomTransform(4f)
        val after = before.transform(10f, 0f, 0f, .1f, .1f)
        assertEquals(8f, after.scale, .0001f)
        assertEquals(.1f, (.1f / 4f) * after.scale + after.x, .0001f)
        assertEquals(PreviewZoomTransform(), after.transform(.01f, 0f, 0f))
    }
    @Test fun panAndEdgeTapNeverLeaveScaledViewOutsideViewport() {
        val panned = PreviewZoomTransform(3f).transform(1f, 50f, -50f)
        assertEquals(1f, panned.x, .0001f); assertEquals(-1f, panned.y, .0001f)
        val tapped = PreviewZoomTransform().doubleTap(.5f, -.5f)
        assertEquals(-1f, tapped.x, .0001f); assertEquals(1f, tapped.y, .0001f)
    }
    @Test fun minimumToleranceReturnsPagingAndDoubleTapZoom() {
        val value = PreviewZoomTransform(1.005f)
        assertTrue(value.atMinimum)
        assertEquals(3f, value.doubleTap(0f, 0f).scale, .0001f)
        assertFalse(PreviewZoomTransform(1.02f).atMinimum)
    }
    @Test fun invalidPointerTransformDoesNotPoisonSavedState() {
        val value = PreviewZoomTransform(2f, .1f, .2f)
        assertEquals(value, value.transform(Float.NaN, 0f, 0f))
        assertEquals(value, value.transform(1f, Float.POSITIVE_INFINITY, 0f))
        assertEquals(value, value.transform(0f, 0f, 0f))
    }

    @Test fun imageFocalPointSurvivesPortraitLandscapeAndReturn() {
        val portrait = PreviewZoomTransform().doubleTap(-.3f, 0f)
        val landscape = portrait.reframeImage(1000, 2000, 2000, 1000, 1.5f)
        // The image fills 75% of the landscape width, versus all of portrait width.
        assertEquals(.9f * .75f, landscape.x, .0001f)
        assertEquals(.2f, .5f - landscape.x / (landscape.scale * .75f), .0001f)
        assertEquals(portrait.x, landscape.reframeImage(2000, 1000, 1000, 2000, 1.5f).x, .0001f)
        assertEquals(portrait.scale, landscape.scale, .0001f)
    }

    @Test fun tallImageVerticalFocalPointAndBoundariesSurviveResizing() {
        val landscape = PreviewZoomTransform(3f, 0f, -.6f)
        val portrait = landscape.reframeImage(2000, 1000, 1000, 2000, .75f)
        assertEquals(-.4f, portrait.y, .0001f)
        assertEquals(.7f, .5f - portrait.y / (portrait.scale * (2f / 3f)), .0001f)
        val bounded = PreviewZoomTransform(3f, 1f, 0f).reframeImage(2000, 1000, 1000, 2000, .75f)
        assertEquals(1f, bounded.x, .0001f)
        assertEquals(PreviewZoomTransform(), PreviewZoomTransform().reframeImage(1000, 2000, 2000, 1000, 1.5f))
    }

    @Test fun unmeasuredOrInvalidImageGeometryKeepsSavedTransform() {
        val value = PreviewZoomTransform(3f, .6f, -.2f)
        assertEquals(value, value.reframeImage(0, 1000, 2000, 1000, 1.5f))
        assertEquals(value, value.reframeImage(1000, 2000, 0, 1000, 1.5f))
        assertEquals(value, value.reframeImage(1000, 2000, 2000, 1000, Float.NaN))
        assertEquals(value, value.reframeImage(1000, 2000, 2000, 1000, 0f))
    }
}
