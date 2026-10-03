package io.github.docmorphic.cmuxapp

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class TerminalSizingChromeTest {
    private val local = TerminalViewport(80, 40)
    private val grid = SharedTerminalGrid(80, 24)
    private val state = TerminalSizeState(1, grid, "smallest", listOf("mac"), TerminalSizePolicy(TerminalSizeMode.SMALLEST),
        listOf(TerminalSizeParticipant("phone", "fixture", "Fixture", "unknown", "Pixel", null,
            SharedTerminalGrid(80, 40), null, true, "phone"),
            TerminalSizeParticipant("mac", "fixture", "Fixture", "mac", "Mac", null, grid, null, true, "mac")))

    @Test fun chromeRequiresAcknowledgementSelfViewportRenderedGridAndConnection() {
        assertTrue(TerminalSizingChrome.settled(state, "phone", local, true, grid, true))
        assertFalse(TerminalSizingChrome.settled(state, "phone", local, false, grid, true))
        assertFalse(TerminalSizingChrome.settled(state, "phone", local, true, grid, false))
        assertFalse(TerminalSizingChrome.settled(state, "phone", TerminalViewport(80, 20), true, grid, true))
        assertFalse(TerminalSizingChrome.settled(state, "replaced-phone", local, true, grid, true))
        assertFalse(TerminalSizingChrome.settled(state, "phone", local, true, SharedTerminalGrid(80, 23), true))
        assertFalse(TerminalSizingChrome.settled(state, "mac", TerminalViewport(80, 24), true, grid, true))
        assertFalse(TerminalSizingChrome.settled(null, "phone", local, true, grid, true))
    }

    @Test fun letterboxHasOnlyInteriorBordersAndNoHatchOverContent() {
        val viewport = Rect(0f, 0f, 400f, 800f)
        val render = Rect(20f, 100f, 380f, 600f)
        val bounds = TerminalSizingChrome.bounds(viewport, render, 2f)
        assertEquals(TerminalSizingChrome.Edge.entries.toSet(), bounds.edges)
        assertEquals(4, bounds.hatch.size)
        assertTrue(bounds.hatch.all { it.intersect(render).isEmpty })
        assertEquals(viewport.width * viewport.height - render.width * render.height,
            bounds.hatch.sumOf { (it.width * it.height).toDouble() }.toFloat(), .01f)
        assertTrue(bounds.cuts.isEmpty())
        val pinned = TerminalSizingChrome.bounds(viewport, Rect(0f, 0f, 400f, 600f), 2f)
        assertEquals(setOf(TerminalSizingChrome.Edge.BOTTOM), pinned.edges)
        assertEquals(listOf(Rect(0f, 600f, 400f, 800f)), pinned.hatch)
    }

    @Test fun clippedGridHasCutFadesInsteadOfFlushBorders() {
        val viewport = Rect(0f, 0f, 400f, 800f)
        val bounds = TerminalSizingChrome.bounds(viewport, Rect(-10f, -20f, 410f, 850f), 2f)
        assertEquals(viewport, bounds.grid)
        assertTrue(bounds.hatch.isEmpty()); assertTrue(bounds.edges.isEmpty())
        assertEquals(TerminalSizingChrome.Edge.entries.toSet(), bounds.cuts)
        val tolerance = TerminalSizingChrome.bounds(viewport, Rect(1f, 0f, 400.5f, 800f), 2f)
        assertTrue(tolerance.edges.isEmpty()); assertTrue(tolerance.cuts.isEmpty())
    }

    @Test fun chipPrefersBelowThenBesideThenAboveAndDoesNotCoverLastRow() {
        val viewport = Rect(0f, 0f, 400f, 800f)
        val full = Size(180f, 30f); val compact = Size(65f, 30f)
        val cases = listOf(
            Rect(0f, 0f, 400f, 600f) to TerminalSizingChrome.Anchor.BELOW,
            Rect(0f, 0f, 200f, 800f) to TerminalSizingChrome.Anchor.BESIDE,
            Rect(0f, 100f, 400f, 800f) to TerminalSizingChrome.Anchor.ABOVE,
            viewport to TerminalSizingChrome.Anchor.COMPACT)
        cases.forEach { (grid, expected) ->
            val chip = TerminalSizingChrome.chip(viewport, grid, full, compact, 6f)
            assertEquals(expected, chip.anchor)
            assertEquals(chip.frame, chip.frame.intersect(viewport))
            if (expected != TerminalSizingChrome.Anchor.COMPACT) assertTrue(chip.frame.intersect(grid).isEmpty)
            else assertTrue(chip.frame.bottom < grid.bottom - 20f)
        }
        val narrow = TerminalSizingChrome.chip(Rect(0f, 0f, 80f, 100f), Rect(0f, 0f, 80f, 100f), full, compact, 6f)
        assertTrue(narrow.frame.width <= 68f)
    }

    @Test fun themeControlsMeetContrastOnLightDarkAndLowContrastThemes() {
        listOf(Color.White, Color.Black, Color(0xFF777777), Color(0xFF241A47)).forEach { background ->
            listOf(3f, 4.5f).forEach { minimum ->
                val result = sizingContrast(background, background, minimum)
                val a = result.luminance(); val b = background.luminance()
                assertTrue((maxOf(a, b) + .05f) / (minOf(a, b) + .05f) >= minimum)
            }
        }
    }
}
