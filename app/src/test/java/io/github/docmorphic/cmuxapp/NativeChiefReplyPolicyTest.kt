package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NativeChiefReplyPolicyTest {
    @Test fun loadedRootSelectionMatchesTheExecutedSourceProjection() {
        val vectors = JSONObject(checkNotNull(javaClass.getResourceAsStream("/parity/chief-reply.json")).bufferedReader().use { it.readText() }).getJSONArray("cases")
        repeat(vectors.length()) { case ->
            val row = vectors.getJSONObject(case); val input = row.getJSONArray("input")
            val messages = (0 until input.length()).map { index ->
                val item = input.getJSONObject(index); val outgoing = item.getBoolean("outgoing")
                NativeChiefMessage(item.getString("id"), (index + 1).toULong(), if (outgoing) "me" else "chief",
                    if (outgoing) "You" else "Chief", if (outgoing) "human" else "chief", "Body", Instant.EPOCH,
                    emptyList(), emptyList(), item.optString("replyTo").takeUnless { it == "null" || it.isEmpty() }, null)
            }
            val actual = NativeChiefReplyPolicy.open(NativeChiefTranscriptState(messages = messages), row.getString("root"), row.getString("root"), 1)
            assertEquals(row.getBoolean("opened"), actual != null)
            val expected = row.getJSONArray("rows")
            assertEquals((0 until expected.length()).map { expected.getJSONObject(it).getString("id") }, actual?.rows?.map { it.messageID } ?: emptyList<String>())
        }
    }
}
