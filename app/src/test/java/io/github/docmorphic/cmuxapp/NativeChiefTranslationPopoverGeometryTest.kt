package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeChiefTranslationPopoverGeometryTest {
    @Test fun actualUIKitPopupRectanglesMatchAcrossSidesRtlAndAccessibility() {
        val data=javaClass.getResourceAsStream("/parity/chief-translation-popover.json")!!.bufferedReader().use { JSONObject(it.readText()) }
        val cases=data.getJSONArray("cases")
        assertEquals(8,cases.length())
        for(index in 0 until cases.length()) {
            val row=cases.getJSONObject(index)
            fun rect(name: String): NativeChiefTranslationPopoverGeometry.Rect {
                val a=row.getJSONArray(name)
                return NativeChiefTranslationPopoverGeometry.Rect(a.getDouble(0),a.getDouble(1),a.getDouble(2),a.getDouble(3))
            }
            val expected=rect("body")
            val result=NativeChiefTranslationPopoverGeometry.place(rect("bounds"),rect("source"),expected.width,expected.height)
            assertEquals(row.getString("scene"),row.getString("side"),result.side.name)
            assertEquals(row.getString("scene"),expected.x,result.body.x,1.0/3)
            assertEquals(row.getString("scene"),expected.y,result.body.y,1.0/3)
        }
    }
    @Test fun offscreenAndOversizedAnchorsKeepTheVisibleContentWithinItsViewport() {
        val bounds=NativeChiefTranslationPopoverGeometry.Rect(0.0,24.0,402.0,826.0)
        for(y in listOf(-2000.0,0.0,150.0,780.0,2000.0)) {
            val source=NativeChiefTranslationPopoverGeometry.Rect(16.0,y,300.0,120.0)
            val pose=NativeChiefTranslationPopoverGeometry.place(bounds,source,240.0,246.333)
            assertTrue(pose.body.x>=10);assertTrue(pose.body.right<=392)
            assertTrue(pose.body.y>=34);assertTrue(pose.body.bottom<=840)
            assertTrue(pose.arrow.isFinite())
        }
    }
}
