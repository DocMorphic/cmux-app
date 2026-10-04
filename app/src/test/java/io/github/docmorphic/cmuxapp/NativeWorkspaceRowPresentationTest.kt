package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class NativeWorkspaceRowPresentationTest {
    private val now = Instant.parse("2026-10-04T00:30:00Z").toEpochMilli()
    private fun row(last: Double? = null, preview: Double? = null) = NativeWorkspace("w", "Workspace", emptyList(),
        null, false, last, null, false, emptyList(), null, null, null, previewAt = preview)
    @Test fun currentLocalDayUsesTimeAndOlderLocalDayUsesDate() {
        val activity = Instant.parse("2026-10-03T23:30:00Z").epochSecond.toDouble()
        assertFalse(workspaceActivityStamp(row(activity), now, TimeZone.getTimeZone("UTC"))!!.today)
        assertTrue(workspaceActivityStamp(row(activity), now, TimeZone.getTimeZone("Europe/Berlin"))!!.today)
        assertTrue(workspaceActivityStamp(row(activity), now, TimeZone.getTimeZone("America/Los_Angeles"))!!.today)
    }
    @Test fun realActivityWinsAndLegacyPreviewTimestampIsRetainedByParser() {
        val legacy = parseWorkspaces(JSONObject("""{"workspaces":[{"id":"w","preview_at":1700000000}]}""")).single()
        assertEquals(1700000000.0, legacy.previewAt!!, 0.0)
        assertEquals(1700000000000L, workspaceActivityStamp(legacy, now, TimeZone.getTimeZone("UTC"))!!.milliseconds)
        assertEquals(2000L, workspaceActivityStamp(row(2.0, 1700000000.0), now, TimeZone.getTimeZone("UTC"))!!.milliseconds)
    }
    @Test fun missingSentinelAndNonfiniteTimesDoNotInventActivity() {
        val zone = TimeZone.getTimeZone("UTC")
        assertNull(workspaceActivityStamp(row(), now, zone))
        listOf(0.0, 1.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE).forEach {
            assertNull(workspaceActivityStamp(row(it, 1700000000.0), now, zone))
        }
    }
    @Test fun connectionLabelsDescribeTheLinkAndHealthyRowsHaveNoStatusLabel() {
        assertNull(workspaceConnectionLabel(NativeFeedAvailability.CONNECTED))
        assertEquals("Reconnecting", workspaceConnectionLabel(NativeFeedAvailability.CONNECTING))
        assertEquals("Disconnected", workspaceConnectionLabel(NativeFeedAvailability.OFFLINE))
    }
}
