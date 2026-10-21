package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeChiefMenuGeometryTest {
    private fun reference() = JSONObject(checkNotNull(javaClass.getResourceAsStream("/parity/chief-menu.json"))
        .bufferedReader().use { it.readText() })
    private fun JSONArray.rows() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.rect() = NativeChiefMenuGeometry.Rect(getDouble(0), getDouble(1), getDouble(2), getDouble(3))
    private fun assertRect(expected: JSONArray, actual: NativeChiefMenuGeometry.Rect) {
        listOf(actual.x, actual.y, actual.width, actual.height).forEachIndexed { i, value -> assertEquals(expected.getDouble(i), value, 1e-8) }
    }
    @Test fun pressClocksReleaseTapAndRecognizerArbitrationMatchWholePinnedSwift() {
        val r = reference()
        val constants = r.getJSONObject("constants")
        assertEquals(constants.getDouble("liftBegins"), NativeChiefMessagePressTiming.liftBegins, 0.0)
        assertEquals(constants.getDouble("clickDown"), NativeChiefMessagePressTiming.clickDown, 0.0)
        assertEquals(constants.getDouble("clickTimeout"), NativeChiefMessagePressTiming.clickTimeout, 0.0)
        NativeChiefMessagePressTiming.pressCurve.forEachIndexed { i, value -> assertEquals(constants.getJSONArray("pressCurve").getDouble(i), value, 0.0) }
        r.getJSONArray("releases").rows().forEach {
            assertEquals(it.getBoolean("opens"), NativeChiefMessagePressTiming.opensOnRelease(it.getDouble("elapsed")))
            assertEquals(it.getBoolean("tap"), NativeChiefMessagePressTiming.tapOpensItem(it.getDouble("elapsed")))
        }
        r.getJSONArray("gestures").rows().forEach {
            assertEquals(it.getBoolean("together"), NativeChiefMessagePressTiming.recognizeTogether(
                NativeChiefMessagePressTiming.Gesture.valueOf(it.getString("a").uppercase()),
                NativeChiefMessagePressTiming.Gesture.valueOf(it.getString("b").uppercase()), it.getBoolean("growing")))
        }
        r.getJSONArray("scales").rows().forEach {
            val size = it.getJSONArray("size")
            assertEquals(it.getDouble("press"), NativeChiefMessagePressTiming.pressScale(size.getDouble(0), size.getDouble(1)), 1e-8)
            assertEquals(it.getDouble("lift"), NativeChiefMessagePressTiming.liftScale(size.getDouble(0), size.getDouble(1)), 1e-8)
        }
    }
    @Test fun menuMetricsAndHandoffMatchActualUIKitDeclarations() {
        val m = reference().getJSONObject("metrics")
        mapOf("row" to NativeChiefMenuGeometry.rowHeight, "width" to NativeChiefMenuGeometry.menuWidth,
            "gap" to NativeChiefMenuGeometry.menuGap, "verticalInset" to NativeChiefMenuGeometry.verticalInset,
            "iconCenter" to NativeChiefMenuGeometry.iconCenter, "labelLeading" to NativeChiefMenuGeometry.labelLeading,
            "labelTrailing" to NativeChiefMenuGeometry.labelTrailing, "handoff" to NativeChiefMessagePressTiming.replyHandoffDelay)
            .forEach { (key, value) -> assertEquals(key, m.getDouble(key), value, 1e-8) }
    }
    @Test fun everyPreviewClipAndMenuPlacementMatchesActualSourceLayoutBodies() {
        val cases = reference().getJSONArray("placements").rows()
        assertEquals(1152, cases.size)
        cases.forEachIndexed { index, r ->
            val actual = NativeChiefMenuGeometry.layout(r.getDouble("width"), r.getDouble("height"), r.getDouble("safeTop"),
                r.getDouble("safeBottom"), r.getDouble("header"), r.getDouble("inset"), r.getJSONArray("source").rect(),
                r.getBoolean("outgoing"), r.getInt("rows"))
            assertRect(r.getJSONArray("preview"), actual.preview); assertRect(r.getJSONArray("menu"), actual.menu)
            assertEquals("scale $index", r.getDouble("scale"), actual.scale, 1e-8)
            assertEquals("clipping $index", r.getBoolean("clipped"), actual.clipped)
        }
    }
}
