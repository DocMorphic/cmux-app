package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeChiefCaptionGeometryTest {
    private fun cases() = JSONObject(checkNotNull(javaClass.getResourceAsStream("/parity/chief-captions.json"))
        .bufferedReader().use { it.readText() }).getJSONArray("cases")

    @Test fun timestampRowLabelAndBaselineMatchActualSwiftMembersForMeasuredFontInputs() {
        val vectors = cases()
        repeat(vectors.length()) { index ->
            val row = vectors.getJSONObject(index)
            val spacing = NativeChiefCaptionGeometry.lineSpacing(row.getDouble("lineHeight"), row.getDouble("leading"), row.getDouble("neutralLineHeight"))
            val actual = NativeChiefCaptionGeometry.timestamp(row.getDouble("neutralLineHeight"), row.getDouble("ascender"), spacing,
                row.getDouble("pointSize"), row.getDouble("scale"))
            val expected = row.getJSONArray("timestamp")
            assertEquals(expected.getDouble(0), actual.lineTop, 1e-8)
            assertEquals(expected.getDouble(1), actual.rowHeight, 1e-8)
            assertEquals(expected.getDouble(2), actual.baselineOffset, 1e-8)
            assertEquals(row.getJSONArray("labelFrame").getDouble(3), actual.labelHeight, 1e-8)
        }
    }

    @Test fun insetsGapsAndSenderGeometryMatchWholeSwiftTranscriptMetricsIncludingTallScriptAndScaleFallback() {
        val vectors = cases()
        repeat(vectors.length()) { index ->
            val row = vectors.getJSONObject(index)
            val point = row.getDouble("pointSize"); val scale = row.getDouble("scale")
            val spacing = NativeChiefCaptionGeometry.lineSpacing(row.getDouble("lineHeight"), row.getDouble("leading"), row.getDouble("neutralLineHeight"))
            assertEquals(row.getDouble("spacing"), spacing, 1e-8)
            val actual = NativeChiefCaptionGeometry.alignmentInsets(point); val insets = row.getJSONArray("insets")
            assertEquals(insets.getDouble(0), actual.top, 1e-8); assertEquals(insets.getDouble(1), actual.bottom, 1e-8)
            assertEquals(row.getDouble("lineGap"), NativeChiefCaptionGeometry.lineGap(spacing, point, scale), 1e-8)
            assertEquals(row.getDouble("followingGap"), NativeChiefCaptionGeometry.followingGap(spacing, point, scale), 1e-8)
            assertEquals(row.getDouble("headerGap"), NativeChiefCaptionGeometry.headerGap(spacing, point, scale), 1e-8)
            val sender = NativeChiefCaptionGeometry.sender(row.getDouble("lineHeight"), spacing, scale)
            assertEquals(row.getJSONArray("sender").getDouble(0), sender.height, 1e-8)
            assertEquals(row.getJSONArray("sender").getDouble(1), sender.bubbleOffset, 1e-8)
        }
    }
}
