package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RenderGridTest {
    @Test fun fullThenOrderedDeltaPreservesOtherRows() {
        val grid = RenderGrid()
        assertTrue(grid.apply(JSONObject("""{
          "format":"cmux.render-grid.v1","surface_id":"s","render_epoch":"e",
          "render_revision":1,"columns":8,"rows":2,"full":true,
          "styles":[{"id":0,"foreground":"#ffffff"}],
          "row_spans":[{"row":0,"column":0,"style_id":0,"text":"first"},
                       {"row":1,"column":0,"style_id":0,"text":"second"}]
        }""")))
        assertTrue(grid.apply(JSONObject("""{
          "format":"cmux.render-grid.v1","surface_id":"s","render_epoch":"e",
          "render_revision":2,"delta_base_render_revision":1,"columns":8,"rows":2,
          "full":false,"cleared_rows":[1],"row_spans":[{"row":1,"column":0,"style_id":0,"text":"changed"}]
        }""")))
        assertEquals("first", grid.lines[0].single().text)
        assertEquals("changed", grid.lines[1].single().text)
        assertEquals(2L, grid.revision)
    }

    @Test fun missingDeltaRequestsReplay() {
        val grid = RenderGrid()
        assertFalse(grid.apply(JSONObject("""{
          "format":"cmux.render-grid.v1","surface_id":"s","render_epoch":"e",
          "render_revision":3,"delta_base_render_revision":2,"columns":8,"rows":2,
          "full":false,"row_spans":[]
        }""")))
    }

    @Test fun screenAnchoredScrollRetainsHistoryAndRejectsMissingHistoryBase() {
        val grid = RenderGrid()
        assertTrue(grid.apply(JSONObject("""{
          "format":"cmux.render-grid.v1","surface_id":"s","render_epoch":"e",
          "render_revision":1,"columns":8,"rows":2,"full":true,"anchor":"screen",
          "history_rows":4,"row_spans":[{"row":0,"column":0,"text":"old"},
                       {"row":1,"column":0,"text":"bottom"}]
        }""")))
        assertTrue(grid.apply(JSONObject("""{
          "format":"cmux.render-grid.v1","surface_id":"s","render_epoch":"e",
          "render_revision":2,"delta_base_render_revision":1,"delta_base_history_rows":4,
          "history_rows":5,"columns":8,"rows":2,"full":false,"anchor":"screen",
          "scrolled_rows":1,"row_spans":[{"row":1,"column":0,"text":"new"}]
        }""")))
        assertEquals("old", grid.scrollbackLines.last().single().text)
        assertEquals("bottom", grid.lines[0].single().text)
        assertEquals("new", grid.lines[1].single().text)
        assertFalse(grid.apply(JSONObject("""{
          "format":"cmux.render-grid.v1","surface_id":"s","render_epoch":"e",
          "render_revision":3,"delta_base_render_revision":2,"delta_base_history_rows":4,
          "columns":8,"rows":2,"full":false,"row_spans":[]
        }""")))
    }

    @Test fun deltaWithoutCursorRetainsCursorAndExtendedStyle() {
        val grid = RenderGrid()
        assertTrue(grid.apply(JSONObject("""{
          "format":"cmux.render-grid.v1","surface_id":"s","render_epoch":"e",
          "render_revision":1,"columns":4,"rows":1,"full":true,
          "cursor":{"row":0,"column":1,"visible":true,"style":"bar","blinking":true},
          "styles":[{"id":2,"faint":true,"strikethrough":true,"overline":true,"blink":true}],
          "row_spans":[{"row":0,"column":0,"style_id":2,"text":"one"}]
        }""")))
        assertTrue(grid.lines[0].single().style.faint)
        assertTrue(grid.lines[0].single().style.strikethrough)
        assertTrue(grid.cursor!!.blinking)
        assertTrue(grid.apply(JSONObject("""{
          "format":"cmux.render-grid.v1","surface_id":"s","render_epoch":"e",
          "render_revision":2,"delta_base_render_revision":1,"columns":4,"rows":1,
          "full":false,"row_spans":[]
        }""")))
        assertEquals(1, grid.cursor!!.column)
    }

    @Test fun fullModesControlInputButPartialModesDoNotEraseThem() {
        val grid = RenderGrid()
        assertTrue(grid.apply(JSONObject("""{
          "format":"cmux.render-grid.v1","surface_id":"s","render_epoch":"e",
          "render_revision":1,"columns":4,"rows":1,"full":true,
          "modes":[{"code":1,"ansi":false,"on":true},
                   {"code":2004,"ansi":false,"on":true}],"row_spans":[]
        }""")))
        assertTrue(grid.applicationCursorKeys)
        assertTrue(grid.bracketedPaste)
        assertTrue(grid.apply(JSONObject("""{
          "format":"cmux.render-grid.v1","surface_id":"s","render_epoch":"e",
          "render_revision":2,"delta_base_render_revision":1,"columns":4,"rows":1,
          "full":false,"modes":[{"code":7,"ansi":false,"on":true}],"row_spans":[]
        }""")))
        assertTrue(grid.applicationCursorKeys)
        assertTrue(grid.bracketedPaste)
    }
}
