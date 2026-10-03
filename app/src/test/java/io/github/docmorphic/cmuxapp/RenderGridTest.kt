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

    private fun frame(revision: Int, full: Boolean = true): JSONObject = JSONObject()
        .put("format", "cmux.render-grid.v1").put("surface_id", "surface").put("render_epoch", "epoch")
        .put("render_revision", revision).put("columns", 8).put("rows", 2).put("full", full)
        .put("anchor", "screen").put("active_screen", "primary").put("row_space_revision", 1)
        .put("row_spans", org.json.JSONArray().put(JSONObject().put("row", 0).put("column", 0).put("text", "row")))

    @Test fun alternateScreenAndChangedRowSpaceRequireFullFramesWithoutMutatingState() {
        val grid = RenderGrid()
        grid.apply(frame(1))
        assertFalse(grid.apply(frame(2, false).put("delta_base_render_revision", 1).put("active_screen", "alternate")))
        assertFalse(grid.apply(frame(2, false).put("delta_base_render_revision", 1).put("row_space_revision", 2)))
        assertEquals(1L, grid.revision)
        assertEquals("primary", grid.activeScreen)
        assertTrue(grid.apply(frame(3).put("active_screen", "alternate")))
        assertEquals("alternate", grid.activeScreen)
        assertEquals(0, grid.historyLineCount)
        assertTrue(grid.apply(frame(4).put("active_screen", "primary")))
    }

    @Test fun duplicateFullFrameDoesNotRollBackButLegacyFramesCanUpdate() {
        val grid = RenderGrid()
        grid.apply(frame(2))
        val old = frame(2).put("row_spans", org.json.JSONArray())
        assertTrue(grid.apply(old))
        assertEquals("row", grid.lines[0].single().text)
        assertTrue(grid.apply(frame(0).put("render_epoch", "")))
        assertTrue(grid.apply(frame(0).put("render_epoch", "").put("row_spans", org.json.JSONArray())))
        assertTrue(grid.lines[0].isEmpty())
    }

    @Test fun burstScrollCarriesHistoryAndResizeDropsIncompatibleRows() {
        val grid = RenderGrid()
        grid.apply(frame(1).put("history_rows", 0))
        val carried = org.json.JSONArray()
        listOf("a", "b", "c", "d").forEachIndexed { index, text ->
            carried.put(JSONObject().put("row", index).put("column", 0).put("text", text))
        }
        assertTrue(grid.apply(frame(2, false).put("delta_base_render_revision", 1)
            .put("delta_base_history_rows", 0).put("history_rows", 4).put("scrolled_rows", 4)
            .put("scrollback_rows", 4).put("scrollback_spans", carried)))
        assertEquals(listOf("a", "b", "c", "d"), grid.scrollbackLines.map { it.single().text })
        assertEquals("c\nd", RenderGrid.plainText(grid.visibleLines(2)))
        grid.apply(frame(3).put("columns", 7))
        assertEquals(0, grid.historyLineCount)
    }

    @Test fun semanticColorsFollowThemeAndCursorColorResetsWithFullSnapshot() {
        val grid = RenderGrid()
        val colors = org.json.JSONArray(List(16) { if (it == 2) "#00ff00" else "#000000" })
        val styled = frame(1).put("terminal_foreground", "#ffffff").put("terminal_background", "#000000")
            .put("terminal_cursor_color", "#ff0000").put("terminal_theme", JSONObject().put("palette", colors))
            .put("styles", org.json.JSONArray().put(JSONObject().put("id", 0)
                .put("foreground", "#123456").put("foreground_source", "palette").put("foreground_palette_index", 2)
                .put("background", "#123456").put("background_source", "default")))
        grid.apply(styled)
        val style = grid.lines[0].single().style
        assertEquals("#00ff00", grid.foreground(style))
        assertEquals("#000000", grid.background(style))
        assertEquals("#ff0000", grid.cursorColor)
        val next = frame(2).put("terminal_foreground", "#eeeeee").put("terminal_background", "#111111")
            .put("modes", org.json.JSONArray().put(JSONObject().put("code", 5).put("on", true).put("ansi", false)))
        grid.apply(next)
        assertNull(grid.cursorColor)
        assertEquals("#eeeeee", grid.background(style))
        grid.apply(frame(3).put("terminal_theme", JSONObject().put("background", "#010203")
            .put("foreground", "#aabbcc").put("cursor", "#abcdef")))
        assertEquals("#010203", grid.background)
        assertEquals("#aabbcc", grid.foreground)
        assertEquals("#abcdef", grid.cursorColor)
    }

    @Test fun accessibleTextPreservesColumnGapsAndNeverIncludesHiddenGlyphs() {
        val grid = RenderGrid()
        val spans = org.json.JSONArray().put(JSONObject().put("row", 0).put("column", 1).put("text", "A"))
            .put(JSONObject().put("row", 0).put("column", 5).put("text", "B"))
            .put(JSONObject().put("row", 1).put("column", 0).put("text", "secret").put("style_id", 2))
        grid.apply(frame(1).put("row_spans", spans).put("styles", org.json.JSONArray()
            .put(JSONObject().put("id", 2).put("invisible", true))))
        assertEquals(" A   B", RenderGrid.plainText(grid.visibleLines()))
    }
}
