package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NativeChiefReadingTest {
    @Test fun readingAndCatchUpMatchTheActualPinnedSwiftStoreBodies() {
        val vectors = JSONArray(checkNotNull(javaClass.getResourceAsStream("/parity/chief-reading.json")).bufferedReader().use { it.readText() })
        repeat(vectors.length()) { index ->
            val vector = vectors.getJSONObject(index); val input = vector.getJSONObject("input"); val expected = vector.getJSONObject("expected")
            val human = input.getJSONArray("human").let { rows -> (0 until rows.length()).map { rows.getInt(it) }.toSet() }
            val messages = input.getJSONArray("seqs").let { rows -> (0 until rows.length()).map { rows.getInt(it) } }.map { seq ->
                NativeChiefMessage("m$seq", seq.toULong(), if (seq in human) "me" else "chief", "", if (seq in human) "human" else "chief",
                    "", Instant.EPOCH, emptyList(), emptyList(), null, null)
            }
            val server = if (input.getBoolean("server")) NativeChiefReading.Server(input.getLong("read").toULong(),
                input.getLong("unread"), input.getLong("head").toULong()) else null
            var state = NativeChiefReading(lastReadSeq = input.getLong("last").toULong(), unread = input.getLong("unread"),
                marker = if (input.isNull("marker")) null else input.getLong("marker").toULong(), catchUpCount = input.getLong("count"),
                viewing = input.getBoolean("viewing"), captured = input.getBoolean("captured"), pending = input.getLong("pending").toULong(), server = server)
            val loaded = input.getBoolean("loaded"); val exhausted = input.getBoolean("exhausted")
            fun refresh() { state = state.refresh(messages, loaded, exhausted).state }
            when (input.getString("action")) {
                "accept" -> { state = state.accept(checkNotNull(server)); refresh() }
                "viewOn" -> { if (!state.viewing) { state = state.viewing(true); refresh() } }
                "viewOff" -> if (state.viewing) state = state.viewing(false)
                "endVisit" -> state = state.endVisit()
                "dismiss" -> state = state.dismiss()
                "mark" -> { val seq = input.getLong("mark").toULong(); if (seq > state.lastReadSeq) {
                    state = state.copy(lastReadSeq = seq, pending = maxOf(state.pending, seq)); refresh()
                } }
                else -> refresh()
            }
            val name = input.getString("name")
            assertEquals(name, expected.getLong("last").toULong(), state.lastReadSeq)
            assertEquals(name, expected.getLong("unread"), state.unread)
            assertEquals(name, if (expected.isNull("marker")) null else expected.getLong("marker").toULong(), state.marker)
            assertEquals(name, expected.getLong("count"), state.catchUpCount)
            assertEquals(name, expected.getBoolean("viewing"), state.viewing)
            assertEquals(name, expected.getBoolean("captured"), state.captured)
            assertEquals(name, expected.getLong("pending").toULong(), state.pending)
            assertEquals(name, if (expected.isNull("target")) null else expected.getString("target"), state.target(messages, exhausted)?.id)
        }
    }

    @Test fun maximumWireMarkerCannotOverflowIntoAFalseTarget() {
        val state = NativeChiefReading(marker = ULong.MAX_VALUE)
        val message = NativeChiefMessage("last", ULong.MAX_VALUE, "chief", "Chief", "chief", "", Instant.EPOCH,
            emptyList(), emptyList(), null, null)
        assertNull(state.target(listOf(message), true))
    }
}
