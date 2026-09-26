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
        assertEquals(2, grid.revision)
    }

    @Test fun missingDeltaRequestsReplay() {
        val grid = RenderGrid()
        assertFalse(grid.apply(JSONObject("""{
          "format":"cmux.render-grid.v1","surface_id":"s","render_epoch":"e",
          "render_revision":3,"delta_base_render_revision":2,"columns":8,"rows":2,
          "full":false,"row_spans":[]
        }""")))
    }
}
