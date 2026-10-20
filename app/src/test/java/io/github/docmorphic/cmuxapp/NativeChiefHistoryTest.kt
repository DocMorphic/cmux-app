package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeChiefHistoryTest {
    @Test fun geometryAndBackoffMatchTheActualPinnedSwiftBodies() {
        val reference = JSONObject(checkNotNull(javaClass.getResourceAsStream("/parity/chief-history.json")).bufferedReader().use { it.readText() })
        val decisions = reference.getJSONArray("decisions")
        repeat(decisions.length()) { index ->
            val row = decisions.getJSONObject(index)
            assertEquals(row.toString(), row.getString("decision"),
                NativeChiefHistory.decision(row.getDouble("distance").toFloat(), row.getDouble("viewport").toFloat()).name)
        }
        val delays = reference.getJSONArray("backoff")
        repeat(delays.length()) { index ->
            val row = delays.getJSONObject(index)
            assertEquals(row.getLong("milliseconds"), NativeChiefHistory.backoff(row.getLong("attempt").coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        }
    }

    @Test fun measuredRowsUseTheirActualOffsetsAndUnknownRowsOnlyProveTheStopRegion() {
        val measured = NativeChiefHistoryGeometry(listOf(0, 48, 248, 1480, 5800), 7000, 1080, 1000)
        assertEquals(255f, measured.distance(2, 7)!!, 0f)
        assertEquals(NativeChiefHistory.Decision.NONE, NativeChiefHistory.decision(measured.distance(4, 0)!!, 1000f))
        assertEquals(NativeChiefHistory.Decision.STOP, NativeChiefHistory.decision(measured.distance(500, 0)!!, 1000f))
        assertNull(measured.copy(measuredHeight = 6000).distance(5, 0))
        assertNull(measured.distance(-1, 0))
    }
}
