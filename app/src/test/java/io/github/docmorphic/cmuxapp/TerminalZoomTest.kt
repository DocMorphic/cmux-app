package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TerminalZoomTest {
    @Test fun liveSizeIsViewOwnedAndHostChangesDoNotOpenControls() {
        val first = TerminalZoomState()
        assertEquals(10f, first.size)
        assertTrue(first.apply(19f)); assertFalse(first.overlayVisible)
        assertEquals(10f, TerminalZoomState().size)
        assertTrue(first.step(1)); assertTrue(first.overlayVisible)
        first.hide(); assertEquals(20f, first.size)
    }

    @Test fun boundedStepsAndAbsoluteChangesRejectNonFiniteValues() {
        val zoom = TerminalZoomState()
        zoom.apply(100f); assertEquals(28f, zoom.size)
        assertFalse(zoom.step(1)); assertFalse(zoom.overlayVisible)
        zoom.apply(-1f); assertEquals(8f, zoom.size); assertFalse(zoom.step(-1))
        assertFalse(zoom.apply(Float.NaN)); assertFalse(zoom.apply(Float.POSITIVE_INFINITY))
        assertEquals(8f, zoom.size)
    }

    @Test fun resetOnlyUsesExplicitBaselineAndRestoringBuiltInReturnsTen() {
        val zoom = TerminalZoomState()
        zoom.apply(23f); zoom.reset(16f)
        assertEquals(16f, zoom.size); assertTrue(zoom.overlayVisible)
        zoom.step(1); zoom.reset(16f); assertEquals(16f, zoom.size)
        zoom.reset(null); assertEquals(10f, zoom.size)
    }

    @Test fun pinchAccumulatesSmallChangesReversesAndNeverJumpsMultiplePoints() {
        val zoom = TerminalZoomState()
        val pinch = TerminalPinchSteps()
        pinch.update(1.1f, zoom::step); assertEquals(10f, zoom.size)
        pinch.update(1.1f, zoom::step); assertEquals(11f, zoom.size)
        pinch.update(2f, zoom::step); assertEquals(12f, zoom.size)
        pinch.update(.9f, zoom::step); assertEquals(11f, zoom.size)
        pinch.update(Float.NaN, zoom::step); pinch.update(-2f, zoom::step)
        assertEquals(11f, zoom.size)
        TerminalPinchSteps().update(1.01f, zoom::step); assertEquals(11f, zoom.size)
    }

    @Test fun surfaceScopeOverridesWorkspaceAndUnscopedReachesMountedView() {
        fun event(json: String) = requireNotNull(TerminalSetFont.decode(JSONObject(json)))
        val targeted = event("""{"font_size":15,"surface_id":"s","workspace_id":"other"}""")
        assertTrue(targeted.matches("w", "s")); assertFalse(targeted.matches("other", "else"))
        val workspace = event("""{"font_size":15,"workspace_id":"w"}""")
        assertTrue(workspace.matches("w", "any")); assertFalse(workspace.matches("else", "s"))
        assertTrue(event("""{"font_size":12,"surface_id":null}""").matches("w", "s"))
    }

    @Test fun malformedPayloadCannotBecomeAnUnscopedResize() {
        for (json in listOf("{}", """{"font_size":"18"}""", """{"font_size":true}""",
            """{"font_size":15,"surface_id":1}""", """{"font_size":15,"workspace_id":false}""")) {
            assertNull(TerminalSetFont.decode(JSONObject(json)))
        }
        assertEquals(28f, TerminalSetFont.decode(JSONObject("""{"font_size":100}"""))!!.size)
        assertEquals(8f, TerminalSetFont.decode(JSONObject("""{"font_size":-1}"""))!!.size)
    }

    @Test fun EveryOutputModeSubscribesToFontEvents() {
        TerminalOutputMode.entries.forEach { assertTrue("terminal.set_font" in TerminalTransport(it, false).topics) }
    }
}
