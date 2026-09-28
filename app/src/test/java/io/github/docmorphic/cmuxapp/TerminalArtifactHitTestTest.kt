package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream

class TerminalArtifactHitTestTest {
    @Test fun letterboxMarginsCannotTargetAPathInTheNearestClampedCell() {
        val geometry = TerminalGeometry.fit(200f, 200f, 10, 5, TerminalCellMetrics(10f, 20f, 16f))!!
        assertFalse(geometry.contains(1f, 1f)); assertFalse(geometry.contains(150f, 100f))
        assertFalse(geometry.contains(Float.NaN, 60f)); assertFalse(geometry.contains(60f, 150f))
        assertTrue(geometry.contains(50f, 50f)); assertTrue(geometry.contains(149f, 149f))
    }
    @Test fun asciiTapCellsMatchUnmodifiedSwiftAcrossSoftWrapsAndPunctuation() {
        val fixture = JSONObject(GZIPInputStream(javaClass.getResourceAsStream("/artifacts/ios-taps.json.gz")!!).bufferedReader().readText()).getJSONArray("cases")
        for (index in 0 until fixture.length()) {
            val case = fixture.getJSONObject(index)
            if (!case.getBoolean("ascii")) continue
            assertEquals("Case $index", case.opt("expected") as? String,
                TerminalArtifactHitTest.path(case.getString("text"), case.getInt("column"), case.getInt("row"), case.getInt("columns"), String::length))
        }
    }
}
