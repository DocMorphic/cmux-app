package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeChiefTranscriptGeometryTest {
    private val reference = JSONObject(checkNotNull(javaClass.getResourceAsStream("/parity/chief-header.json")).bufferedReader().use { it.readText() })
    @Test fun topWashMatchesTheExecutedUnmodifiedSwiftRamp() {
        val cases = reference.getJSONArray("wash")
        repeat(cases.length()) { index ->
            val row = cases.getJSONObject(index)
            val actual = NativeChiefTranscriptGeometry.wash(row.getDouble("height"), row.getDouble("header"), row.getBoolean("dark"))
            val expected = row.getJSONArray("stops")
            assertEquals(expected.length(), actual.size)
            actual.forEachIndexed { i, stop ->
                assertEquals(expected.getJSONArray(i).getDouble(0), stop.location, 1e-9)
                assertEquals(expected.getJSONArray(i).getDouble(1), stop.alpha, 1e-9)
            }
        }
    }
    @Test fun fullViewportInsetsMatchTheActualControllerExpressionsAndTailOverhang() {
        val cases = reference.getJSONArray("transcriptInsets")
        repeat(cases.length()) { index ->
            val row = cases.getJSONObject(index)
            assertEquals(row.getDouble("top"), NativeChiefTranscriptGeometry.topInset(row.getDouble("header"), row.getBoolean("compact")), 1e-9)
            assertEquals(row.getDouble("paddingBottom"), NativeChiefTranscriptGeometry.bottomPadding(row.getDouble("footer"), row.getDouble("overhang")), 1e-9)
        }
    }
}
