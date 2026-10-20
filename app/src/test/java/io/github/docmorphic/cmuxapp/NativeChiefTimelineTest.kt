package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class NativeChiefTimelineTest {
    @Test fun orderingStableIdentityAndRunFlagsMatchTheActualPinnedSwiftAlgorithms() {
        val cases = javaClass.getResourceAsStream("/parity/chief-timeline.json")!!.bufferedReader().use { org.json.JSONArray(it.readText()) }
        for (caseIndex in 0 until cases.length()) {
            val case = cases.getJSONObject(caseIndex); val input = case.getJSONArray("input")
            val messages = mutableListOf<NativeChiefMessage>(); val outgoing = mutableListOf<NativeChiefOutgoing>()
            for (index in 0 until input.length()) {
                val row = input.getJSONObject(index)
                val seconds = BigDecimal(row.get("seconds").toString())
                val nanos = seconds.movePointRight(9).toLong()
                val at = Instant.ofEpochSecond(nanos / 1_000_000_000, nanos % 1_000_000_000)
                if (row.isNull("seq")) {
                    val clientId = row.getString("client")
                    val anchors = case.getJSONObject("anchors")
                    outgoing += NativeChiefOutgoing(clientId, "message", at,
                        delivery = if (row.getBoolean("failed")) NativeChiefDelivery.REJECTED else NativeChiefDelivery.SENDING,
                        failedAnchorSeq = if (anchors.has(row.getString("id"))) anchors.getLong(row.getString("id")).toULong() else null)
                } else messages += NativeChiefMessage(row.getString("id"), row.getLong("seq").toULong(), row.getString("sender"), "Name",
                    if (row.getString("sender") == "me") "human" else "chief", "message", at, emptyList(), emptyList(),
                    if (row.isNull("reply")) null else row.getString("reply"), if (row.isNull("client")) null else row.getString("client"))
            }
            val rows = NativeChiefTimeline.rows(NativeChiefTranscriptState(messages = messages, outgoing = outgoing,
                working = case.getJSONArray("typing").length() > 0))
            val expected = case.getJSONArray("rows")
            assertEquals(case.getString("name"), expected.length(), rows.size)
            rows.forEachIndexed { index, row ->
                val ref = expected.getJSONObject(index)
                assertEquals(case.getString("name"), ref.getString("key"), row.item.key)
                assertEquals(case.getString("name"), ref.getBoolean("timestamp"), row.timestamp)
                assertEquals(case.getString("name"), ref.getBoolean("first"), row.firstInRun)
                assertEquals(case.getString("name"), ref.getBoolean("last"), row.lastInRun)
            }
        }
    }

    @Test fun aLegacyEchoRetainsTheLocalRowIdentityAndAReplyUsesTheAuthoritativeParent() {
        val parent = NativeChiefMessage("server", 1uL, "me", "You", "human", "Hello", Instant.EPOCH, emptyList(), emptyList(), null, null)
        val reply = parent.copy(id = "reply", seq = 2uL, authorId = "chief", authorKind = "chief", threadRoot = "server")
        val state = NativeChiefTranscriptState(messages = listOf(parent, reply), localClientIds = mapOf("server" to "local-client"))
        val rows = NativeChiefTimeline.rows(state)
        assertEquals("c:local-client", rows.first().item.key)
        assertEquals(parent, rows.last().quote)
        assertTrue(rows.last().firstInRun)
        assertEquals("s:reply", rows.last().item.key)
    }
}
