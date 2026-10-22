package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeChiefPollTableMetricsTest {
    private fun references() = JSONObject(checkNotNull(javaClass.getResourceAsStream("/parity/chief-poll-table-metrics.json"))
        .bufferedReader().use { it.readText() }).getJSONArray("metrics")

    @Test fun rowHeaderFooterInsetsAndNavigationTitleMatchAllTwelveNativeMeasurements() {
        val rows = references(); assertEquals(12, rows.length())
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val actual = NativeChiefPollTableMetrics.forBody(row.getDouble("bodyPoints"))
            assertEquals(row.getDouble("rowHeight"), actual.rowHeight, .0001)
            assertEquals(row.getDouble("firstHeaderHeight"), actual.firstHeaderHeight, .0001)
            assertEquals(row.getDouble("firstHeaderLabelTop"), actual.firstHeaderLabelTop, .0001)
            assertEquals(row.getDouble("continuationHeaderHeight"), actual.continuationHeaderHeight, .0001)
            assertEquals(row.getDouble("continuationHeaderLabelTop"), actual.continuationHeaderLabelTop, .0001)
            assertEquals(row.getDouble("footerHeight"), actual.footerHeight, .0001)
            assertEquals(row.getDouble("footerLabelTop"), actual.footerLabelTop, .0001)
            assertEquals(row.getDouble("titlePointSize"), actual.titlePointSize, .0001)
            assertEquals(row.getDouble("nonVoterHeaderHeight"), actual.nonVoterHeaderHeight, .0001)
            assertEquals(row.getDouble("nonVoterHeaderLabelTop"), actual.nonVoterHeaderLabelTop, .0001)
            assertEquals(row.getDouble("emptyFooterHeight"), actual.emptyFooterHeight, .0001)
            assertEquals(row.getDouble("tableBottomPadding"), actual.tableBottomPadding, .0001)
        }
    }

    @Test fun continuousAndroidScalesUseTheAdjacentMeasuredGeometry() {
        val rows = references()
        for (i in 1 until rows.length()) {
            val a = rows.getJSONObject(i - 1); val b = rows.getJSONObject(i)
            val actual = NativeChiefPollTableMetrics.forBody((a.getDouble("bodyPoints") + b.getDouble("bodyPoints")) / 2)
            assertEquals((a.getDouble("rowHeight") + b.getDouble("rowHeight")) / 2, actual.rowHeight, .0001)
            assertEquals((a.getDouble("firstHeaderHeight") + b.getDouble("firstHeaderHeight")) / 2, actual.firstHeaderHeight, .0001)
            assertEquals((a.getDouble("footerHeight") + b.getDouble("footerHeight")) / 2, actual.footerHeight, .0001)
        }
    }

    @Test fun outOfRangeScalesKeepMeasuredBoundaryMetrics() {
        assertEquals(NativeChiefPollTableMetrics.forBody(14.0), NativeChiefPollTableMetrics.forBody(8.0))
        assertEquals(NativeChiefPollTableMetrics.forBody(53.0), NativeChiefPollTableMetrics.forBody(100.0))
    }
}
