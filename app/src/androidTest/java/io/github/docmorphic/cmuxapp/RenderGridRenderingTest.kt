package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.test.StandardTestDispatcher
import androidx.compose.ui.test.ExperimentalTestApi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercises the device's actual ICU grapheme tables, font shaping and production painter. */
@OptIn(ExperimentalTestApi::class)
class RenderGridRenderingTest {
    @Test fun unicodeClustersStayInAuthoritativeCells() {
        val text = "A中e\u0301👩🏽‍💻B"
        val glyphs = TerminalGlyphLayout.layout(text, 0, 7)
        assertEquals(listOf("A", "中", "e\u0301", "👩🏽‍💻", "B"), glyphs.map { it.text })
        assertEquals(listOf(0, 1, 3, 4, 6), glyphs.map { it.column })
        assertEquals(listOf(1, 2, 1, 2, 1), glyphs.map { it.width })
        assertEquals(7, TerminalGlyphLayout.estimatedWidth(text))
        assertEquals(listOf(2, 1), TerminalGlyphLayout.layout("🇩🇪X", 0, 3).map { it.width })
        assertEquals(listOf(2, 1), TerminalGlyphLayout.layout("·X", 0, 3).map { it.width })
        assertEquals(listOf(1, 1), TerminalGlyphLayout.layout("中X", 0, 2).map { it.width })
        assertEquals(listOf(1, 1), TerminalGlyphLayout.layout("AB", 0, 4).map { it.width })
        val noWidth = base().put("row_spans", JSONArray().put(span(0, 0, text, 7).also { it.remove("cell_width") }))
        val grid = RenderGrid(); assertTrue(grid.apply(noWidth))
        assertEquals(7, grid.lines[0].single().width)
    }

    @Test fun mixedSpanMatchesSeparateCellsAndColorsStayInsideTheGrid() {
        val text = "A中e\u0301👩🏽‍💻B"
        val spans = JSONArray().put(span(0, 0, text, 7))
        listOf(Triple("A", 0, 1), Triple("中", 1, 2), Triple("e\u0301", 3, 1),
            Triple("👩🏽‍💻", 4, 2), Triple("B", 6, 1)).forEach { (glyph, column, width) ->
            spans.put(span(1, column, glyph, width))
        }
        spans.put(span(2, 0, "Bold / italic", 13, 1))
        spans.put(span(3, 0, "Underline / strike", 18, 2))
        spans.put(span(4, 0, "invisible secret", 16, 3))
        spans.put(span(5, 0, "    ", 4, 4))
        spans.put(span(6, 0, "中", 2))
        spans.put(span(7, 2, "A", 1)); spans.put(span(7, 8, "B", 1))
        val styles = JSONArray().put(JSONObject().put("id", 0))
            .put(JSONObject().put("id", 1).put("bold", true).put("italic", true).put("foreground", "#00ffff"))
            .put(JSONObject().put("id", 2).put("underline", true).put("strikethrough", true))
            .put(JSONObject().put("id", 3).put("invisible", true))
            .put(JSONObject().put("id", 4).put("background_source", "palette").put("background_palette_index", 2))
        val frame = base().put("row_spans", spans).put("styles", styles)
            .put("terminal_cursor_color", "#ff0000")
            .put("cursor", JSONObject().put("row", 6).put("column", 0).put("visible", true).put("style", "underline"))
            .put("terminal_theme", JSONObject().put("palette", JSONArray(List(16) { if (it == 2) "#00ff00" else "#000000" })))
        val grid = RenderGrid(); grid.apply(frame)
        val cells = TerminalCellMetrics(20f, 40f, 28f)
        val bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
        TerminalGridPainter().draw(Canvas(bitmap), 480f, 320f, grid,
            TerminalGridPainter.plan(grid.visibleLines()), cells, 0, true)
        for (x in 0 until 480) for (y in 0 until 40) {
            assertEquals("Mixed span pixel $x,$y differs from separately positioned cells", bitmap.getPixel(x, y), bitmap.getPixel(x, y + 40))
        }
        assertEquals(Color.BLACK, bitmap.getPixel(10, 180)) // Invisible glyph row.
        assertEquals(Color.GREEN, bitmap.getPixel(10, 210)) // Indexed background.
        assertEquals(Color.BLACK, bitmap.getPixel(90, 210)) // Next cell stays unpainted.
        assertTrue(Color.red(bitmap.getPixel(5, 279)) > 100) // Cursor covers both cells of the wide glyph.
        assertTrue(Color.red(bitmap.getPixel(35, 279)) > 100)
        assertEquals(Color.BLACK, bitmap.getPixel(45, 279))
        assertFalse(RenderGrid.plainText(grid.visibleLines()).contains("secret"))
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "terminal-unicode-rendering.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // During an IME resize the old frame must fit with one scale for both axes.
        val short = Bitmap.createBitmap(480, 160, Bitmap.Config.ARGB_8888)
        TerminalGridPainter().draw(Canvas(short), 480f, 160f, grid,
            TerminalGridPainter.plan(grid.visibleLines()), cells, 0, true)
        assertEquals(Color.GREEN, short.getPixel(125, 105))
        assertEquals(Color.BLACK, short.getPixel(165, 105))
        assertEquals(Color.BLACK, short.getPixel(110, 105))
        File(directory, "terminal-resize-fitting.png").outputStream().use { short.compress(Bitmap.CompressFormat.PNG, 100, it) }
        short.recycle()
    }

    private fun base() = JSONObject().put("format", "cmux.render-grid.v1").put("surface_id", "fixture")
        .put("render_epoch", "fixture").put("render_revision", 1).put("full", true)
        .put("columns", 24).put("rows", 8).put("terminal_background", "#000000").put("terminal_foreground", "#ffffff")
    private fun span(row: Int, column: Int, text: String, width: Int, style: Int = 0) = JSONObject()
        .put("row", row).put("column", column).put("text", text).put("cell_width", width).put("style_id", style)
}
