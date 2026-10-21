package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeChiefBubbleGeometryTest {
    @Test fun commandsAndMetricsMatchActualPinnedCoreGraphicsExecution() {
        val reference = JSONObject(checkNotNull(javaClass.getResourceAsStream("/parity/chief-bubble.json")).bufferedReader().use { it.readText() })
        val paths = reference.getJSONArray("paths")
        repeat(paths.length()) { index ->
            val row = paths.getJSONObject(index)
            val actual = NativeChiefBubbleGeometry.outline(row.getDouble("width"), row.getDouble("height"),
                row.getDouble("radius"), row.getBoolean("outgoing"), row.getBoolean("tail"))
            val expected = row.getJSONArray("commands")
            assertEquals(row.toString(), expected.length(), actual.size)
            repeat(expected.length()) { command ->
                val e = expected.getJSONObject(command); val a = actual[command]
                assertEquals(e.getString("kind"), a.kind)
                val points = e.getJSONArray("points"); assertEquals(points.length(), a.points.size)
                repeat(points.length()) { point ->
                    val p = points.getJSONArray(point)
                    assertEquals("path $index command $command point $point x", p.getDouble(0), a.points[point].x, 1e-9)
                    assertEquals("path $index command $command point $point y", p.getDouble(1), a.points[point].y, 1e-9)
                }
            }
        }
        val widths = reference.getJSONArray("widths")
        repeat(widths.length()) { val r = widths.getJSONObject(it)
            assertEquals(r.getDouble("maxWidth"), NativeChiefBubbleGeometry.maxWidth(r.getDouble("available")), 1e-9) }
        val accessible = reference.getJSONArray("accessibilityWidths")
        repeat(accessible.length()) { val r = accessible.getJSONObject(it)
            assertEquals(r.getDouble("maxWidth"), NativeChiefBubbleGeometry.maxWidth(r.getDouble("available"), true), 1e-9) }
        val rounding = reference.getJSONArray("rounding")
        repeat(rounding.length()) { val r = rounding.getJSONObject(it)
            assertEquals(r.getDouble("rounded"), NativeChiefBubbleGeometry.ceilToPixel(r.getDouble("value"), r.getDouble("scale")), 1e-9) }
        val drops = reference.getJSONArray("drops")
        repeat(drops.length()) { val r = drops.getJSONObject(it)
            assertEquals(r.getDouble("drop"), NativeChiefBubbleGeometry.tailDrop(r.getDouble("radius")), 1e-9) }
        val spaces = reference.getJSONArray("spaces")
        repeat(spaces.length()) { val r = spaces.getJSONObject(it)
            assertEquals(r.getDouble("space"), NativeChiefBubbleGeometry.space(r.getDouble("base"), r.getDouble("captionLineSpacing"), r.getDouble("scale")), 1e-9) }
    }
}
