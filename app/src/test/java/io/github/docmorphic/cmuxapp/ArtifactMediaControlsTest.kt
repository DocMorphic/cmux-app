package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class ArtifactMediaControlsTest {
    @Test fun skipStopsAtEitherEnd() {
        assertEquals(0, ArtifactMediaControls.seek(3000, -10_000, 20_000))
        assertEquals(20_000, ArtifactMediaControls.seek(18_000, 10_000, 20_000))
        assertEquals(14_000, ArtifactMediaControls.seek(4000, 10_000, 20_000))
    }
    @Test fun seekingCannotOverflowOrUseUnknownDuration() {
        assertEquals(Int.MAX_VALUE, ArtifactMediaControls.seek(Int.MAX_VALUE - 100, 10_000, Int.MAX_VALUE))
        assertEquals(0, ArtifactMediaControls.seek(5, Int.MIN_VALUE, 20_000))
        assertEquals(0, ArtifactMediaControls.seek(5000, 10_000, -1))
    }
    @Test fun timesShowHoursOnlyWhenNeeded() {
        assertEquals("0:00", ArtifactMediaControls.time(-1))
        assertEquals("0:59", ArtifactMediaControls.time(59_999))
        assertEquals("1:00", ArtifactMediaControls.time(60_000))
        assertEquals("59:59", ArtifactMediaControls.time(3_599_999))
        assertEquals("1:00:00", ArtifactMediaControls.time(3_600_000))
        assertEquals("2:03:04", ArtifactMediaControls.time(7_384_000))
    }
    @Test fun restoredUnsupportedSpeedsFallBackToNormal() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, 999f))
            assertEquals(1f, ArtifactMediaControls.speed(invalid), 0f)
        ArtifactMediaControls.speeds.forEach { assertEquals(it, ArtifactMediaControls.speed(it), 0f) }
    }
}
