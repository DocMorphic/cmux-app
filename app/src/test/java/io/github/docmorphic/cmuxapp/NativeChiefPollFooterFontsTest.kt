package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeChiefPollFooterFontsTest {
    private fun references() = JSONObject(checkNotNull(javaClass.getResourceAsStream("/parity/chief-poll-fonts.json"))
        .bufferedReader().use { it.readText() }).getJSONArray("metrics")

    @Test fun allTwelveProfilesMatchIndependentlyCapturedUIKitFontDescriptors() {
        val reference = references()
        assertEquals(12, reference.length())
        repeat(reference.length()) { index ->
            val row = reference.getJSONObject(index)
            val actual = NativeChiefPollFooterFonts.forBody(row.getDouble("bodyPointSize"))
            assertEquals(row.getDouble("footerPointSize"), actual.pointSize, 1e-8)
            assertEquals(row.getDouble("footerLineHeight"), actual.lineHeight, 1e-8)
            assertEquals(row.getDouble("footerAscender"), actual.ascender, 1e-8)
            assertEquals(row.getDouble("footerDescender"), actual.descender, 1e-8)
        }
    }

    @Test fun continuousAndroidScalesInterpolateAdjacentNativeProfiles() {
        val reference = references()
        for (i in 1 until reference.length()) {
            val a = reference.getJSONObject(i - 1); val b = reference.getJSONObject(i)
            val actual = NativeChiefPollFooterFonts.forBody((a.getDouble("bodyPointSize") + b.getDouble("bodyPointSize")) / 2)
            assertEquals((a.getDouble("footerPointSize") + b.getDouble("footerPointSize")) / 2, actual.pointSize, 1e-8)
            assertEquals((a.getDouble("footerLineHeight") + b.getDouble("footerLineHeight")) / 2, actual.lineHeight, 1e-8)
        }
    }

    @Test fun scalesBeyondMeasuredRangeRetainBoundaryProfiles() {
        assertEquals(NativeChiefPollFooterFonts.forBody(14.0), NativeChiefPollFooterFonts.forBody(8.0))
        assertEquals(NativeChiefPollFooterFonts.forBody(53.0), NativeChiefPollFooterFonts.forBody(100.0))
    }
}
