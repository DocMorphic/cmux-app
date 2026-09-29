package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TerminalScrollViewportTest {
    private fun grid() = RenderGrid().apply { apply(JSONObject("""{
      "format":"cmux.render-grid.v1","surface_id":"s","render_epoch":"e","render_revision":1,
      "columns":4,"rows":2,"full":true,"anchor":"screen","active_screen":"primary",
      "history_rows":2,"row_space_revision":7,"scrollback_rows":2,
      "scrollback_spans":[{"row":0,"column":0,"text":"h0"},{"row":1,"column":0,"text":"h1"}],
      "row_spans":[{"row":0,"column":0,"text":"a"},{"row":1,"column":0,"text":"b"}]
    }""")) }

    @Test fun partialRowsIncludeBothEdgesAndHitsFollowPaintedRows() {
        val grid = grid()
        val viewport = TerminalScrollViewport.at(.25, grid.historyLineCount)
        assertEquals(1, viewport.rowOffset); assertEquals(.75f, viewport.topClipFraction)
        assertEquals("h1\na\nb", RenderGrid.plainText(viewport.lines(grid)))
        val geometry = TerminalGeometry(1f, 10f, 20f, 0f, 0f, 4, 2)
        assertEquals(0, viewport.cell(geometry, 5f, 2f).row)
        assertEquals(1, viewport.cell(geometry, 5f, 6f).row)
        assertEquals(2, viewport.cell(geometry, 5f, 39f).row)
        assertEquals("a\nb", RenderGrid.plainText(TerminalScrollViewport.at(0.0, 2).lines(grid)))
        assertEquals("h1\na", RenderGrid.plainText(TerminalScrollViewport.at(1.0, 2).lines(grid)))
    }

    @Test fun limitsInvalidNumbersAndAlternateScreenNeverExposePhantomRows() {
        assertEquals(2.0, TerminalScrollViewport.at(100.0, 2).position, 0.0)
        for (position in listOf(Double.NaN, Double.POSITIVE_INFINITY, -10.0))
            assertEquals(0.0, TerminalScrollViewport.at(position, 2).position, 0.0)
        assertEquals(0.0, TerminalScrollViewport.at(1.5, 2, "alternate").position, 0.0)
        assertEquals(0.0, TerminalScrollViewport.at(1.5, -1).position, 0.0)
    }

    @Test fun outputGrowthPreservesFractionAndTheExactContentBeingRead() {
        val grid = grid(); val before = grid.scrollAnchor
        val oldText = RenderGrid.plainText(TerminalScrollViewport.at(.25, 2).lines(grid))
        assertTrue(grid.apply(JSONObject("""{
          "format":"cmux.render-grid.v1","surface_id":"s","render_epoch":"e","render_revision":2,
          "columns":4,"rows":2,"full":false,"anchor":"screen","active_screen":"primary",
          "delta_base_render_revision":1,"delta_base_history_rows":2,"history_rows":3,"row_space_revision":7,
          "scrolled_rows":1,"row_spans":[{"row":1,"column":0,"text":"c"}]
        }""")))
        val position = grid.scrollAnchor.rebase(.25, before, grid.historyLineCount)
        assertEquals(1.25, position, 0.0)
        assertEquals(oldText, RenderGrid.plainText(TerminalScrollViewport.at(position, grid.historyLineCount).lines(grid)))
        assertEquals(0.0, grid.scrollAnchor.rebase(0.0, before, grid.historyLineCount), 0.0)
        assertEquals(position, grid.scrollAnchor.rebase(position, grid.scrollAnchor, grid.historyLineCount), 0.0)
    }

    @Test fun changedAuthorityAndRowSpaceResetWhileStableReplaysRetainThePosition() {
        val old = grid().scrollAnchor
        for (next in listOf(old.copy(surface = "other"), old.copy(epoch = "new"), old.copy(columns = 5),
            old.copy(rows = 3), old.copy(screen = "alternate"), old.copy(rowSpace = 8), old.copy(historyRows = 1)))
            assertEquals(0.0, next.rebase(1.25, old, 20), 0.0)
        assertEquals(1.25, old.rebase(1.25, old, 2), 0.0)
        assertEquals(2.0, old.copy(historyRows = 100).rebase(1.25, old, 2), 0.0)
        val legacy = old.copy(rowSpace = null)
        assertEquals(1.25, legacy.copy(historyRows = 100).rebase(1.25, legacy, 2), 0.0)
    }
}
