package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NativeChiefWindowTest {
    @Test fun windowCutsMatchTheActualPinnedSwiftTrimMethods() {
        val reference = JSONObject(checkNotNull(javaClass.getResourceAsStream("/parity/chief-window.json")).bufferedReader().use { it.readText() })
        assertEquals(reference.getInt("threshold"), NativeChiefWindow.trimThreshold)
        assertEquals(reference.getInt("keep"), NativeChiefWindow.keepCount)
        val cases = reference.getJSONArray("cases")
        repeat(cases.length()) { index ->
            val vector = cases.getJSONObject(index); val input = vector.getJSONObject("input")
            val clients = input.getJSONObject("clients"); val replies = input.getJSONObject("replies")
            val items = (1..input.getInt("total")).map { seq ->
                val id = "m$seq"; val client = clients.optString(id).ifEmpty { null }
                NativeChiefTimelineItem(client?.let { "c:$it" } ?: "s:$id", message = NativeChiefMessage(id, seq.toULong(),
                    if (client == null) "chief" else "me", "", if (client == null) "chief" else "human", "", Instant.EPOCH,
                    emptyList(), emptyList(), replies.optString(id).ifEmpty { null }, client))
            }.toMutableList()
            if (!input.isNull("local")) items.add(input.getInt("local"), NativeChiefTimelineItem("c:pending",
                outgoing = NativeChiefOutgoing("pending", "Unacknowledged", Instant.EPOCH)))
            fun ids(name: String) = input.getJSONArray(name).let { rows -> (0 until rows.length()).map { rows.getString(it) } }.toSet()
            val held = ids("pinned") + ids("votes") + ids("options")
            val cut = if (input.getBoolean("large") && items.size <= NativeChiefWindow.trimThreshold) 0 else NativeChiefWindow.cut(items,
                if (input.getBoolean("large")) NativeChiefWindow.keepCount else input.getInt("keep"), input.getBoolean("loaded"),
                input.getString("paging") in setOf("loading", "retrying"), if (input.isNull("marker")) null else input.getLong("marker").toULong(),
                input.getLong("unread"), input.getLong("last").toULong(), held)
            val name = input.getString("name")
            assertEquals(name, vector.getInt("cut"), cut)
            assertEquals(name, vector.getBoolean("changed"), cut > 0)
            assertEquals(name, vector.getInt("remaining"), items.size - cut)
            assertEquals(name, if (vector.isNull("first")) null else vector.getString("first"), items.drop(cut).firstOrNull()?.key)
        }
    }
}
