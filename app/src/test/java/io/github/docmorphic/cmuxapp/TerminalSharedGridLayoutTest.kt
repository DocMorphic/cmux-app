package io.github.docmorphic.cmuxapp

import androidx.compose.ui.geometry.Offset
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream

class TerminalSharedGridLayoutTest {
    @Test fun matchesUnmodifiedIosLayoutPanAndZoomAcross189Cases() {
        val fixture = JSONObject(GZIPInputStream(javaClass.getResourceAsStream("/terminal-layout-0fc35d6.json.gz")!!)
            .bufferedReader().readText())
        assertEquals("0fc35d6247c63ff0e2c4555c8aac2cc88fe111cc", fixture.getString("revision"))
        val cases = fixture.getJSONArray("cases")
        assertEquals(189, cases.length())
        for (index in 0 until cases.length()) {
            val item = cases.getJSONObject(index)
            val size = item.getJSONArray("size"); val pan = item.getJSONArray("pan")
            var layout = TerminalSharedGridLayout.resolve(size.getDouble(0).toFloat(), size.getDouble(1).toFloat(),
                size.getInt(2), size.getInt(3), TerminalCellMetrics(size.getDouble(4).toFloat(), size.getDouble(5).toFloat(), 14f),
                1f, TerminalGridTransform(item.getDouble("mag").toFloat(), Offset(pan.getDouble(0).toFloat(), pan.getDouble(1).toFloat())))!!
            layout = when (item.getString("operation")) {
                "pan" -> layout.panned(Offset(43f, 57f))
                "zoom" -> layout.zoomed(1.7f, Offset(170f, 190f))
                else -> layout
            }
            val expected = item.getJSONObject("expected"); val rect = expected.getJSONArray("rect")
            val offsets = expected.getJSONArray("pan")
            assertEquals("Case $index", item.getBoolean("scaled"), layout.scaledMode)
            fun close(expected: Double, actual: Float) = assertEquals("Case $index: ${item.getString("operation")}", expected, actual.toDouble(), .001)
            close(expected.getDouble("scale"), layout.scale)
            close(expected.getDouble("mag"), layout.transform.magnification)
            close(offsets.getDouble(0), layout.transform.offset.x); close(offsets.getDouble(1), layout.transform.offset.y)
            close(rect.getDouble(0), layout.geometry.originX); close(rect.getDouble(1), layout.geometry.originY)
            close(rect.getDouble(2), layout.geometry.cellWidth * layout.columns)
            close(rect.getDouble(3), layout.geometry.cellHeight * layout.rows)
        }
    }

    @Test fun zoomKeepsFocusedCellAndPanReachesEverySharedGridCorner() {
        val layout = TerminalSharedGridLayout.resolve(400f, 600f, 100, 80, TerminalCellMetrics(8f, 20f, 14f), 1f)!!
        val focus = Offset(200f, 300f)
        val cell = layout.geometry.cell(focus.x, focus.y)
        val zoomed = layout.zoomed(2f, focus)
        assertEquals(cell, zoomed.geometry.cell(focus.x, focus.y))
        val topLeft = zoomed.panned(Offset(10000f, 10000f))
        assertEquals(TerminalGeometry.Cell(0, 0), topLeft.geometry.cell(1f, 1f))
        val bottomRight = zoomed.panned(Offset(-10000f, -10000f))
        assertEquals(TerminalGeometry.Cell(99, 79), bottomRight.geometry.cell(399f, 599f))
        assertEquals(100, bottomRight.columns); assertEquals(80, bottomRight.rows)
    }

    @Test fun invalidGesturesAndResizedViewportCannotLeaveStaleOverscroll() {
        val cells = TerminalCellMetrics(8f, 20f, 14f)
        val layout = TerminalSharedGridLayout.resolve(400f, 600f, 100, 80, cells, 1f,
            TerminalGridTransform(Float.NaN, Offset(Float.POSITIVE_INFINITY, Float.NaN)))!!
        assertEquals(TerminalGridTransform(), layout.transform)
        assertSame(layout, layout.panned(Offset(Float.NaN, 1f)))
        assertSame(layout, layout.zoomed(2f, Offset(Float.NaN, 1f)))
        val natural = TerminalSharedGridLayout.resolve(900f, 1800f, 100, 80, cells, 1f,
            TerminalGridTransform(100f, Offset(1000f, 1000f)))!!
        assertFalse(natural.scaledMode)
        assertEquals(TerminalGridTransform(), natural.transform)
        assertEquals(0f, natural.geometry.originX); assertEquals(0f, natural.geometry.originY)
        assertNull(TerminalSharedGridLayout.resolve(Float.NaN, 100f, 10, 10, cells, 1f))
        assertNull(TerminalSharedGridLayout.resolve(100f, 100f, 10, 10, cells.copy(heightPx = 0f), 1f))
    }
}
