package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeAgentFeedProjectionTest {
    private fun entry(id: String = "one", build: String = "default", reason: String = "Done", time: Double = 200.0) =
        NativeAgentFeedEntry(NativeFeedSource(NativeCredentialStore.PairedMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "mac", "Mac", build)),
            NativeAgentFeedItem(id, "turn", "claude", AgentFeedKind.STOP, AgentFeedStatus.TELEMETRY, time, time, reason = reason))
    private fun aggregate(vararg entries: NativeAgentFeedEntry) = aggregateNativeAgentFeed(entries.groupBy { it.source.mac }
        .map { (_, rows) -> rows.first().source.copy(agentFeed = NativeAgentFeedState(NativeAgentFeedSnapshot(1, rows.map { it.item }))) })

    @Test fun duplicateStopsPreferFullerMessageAndPreserveReplyButNeverMergeAcrossBuilds() {
        val first = entry(reason = "Fixed the…").let { it.copy(item = it.item.copy(replyText = "Thanks")) }
        val fuller = entry("two", reason = "Fixed the issue", time = 199.0)
        val other = entry("two", "nightly", "Fixed the issue", 199.0)
        val rows = aggregate(first, fuller, other)
        assertEquals(2, rows.size)
        assertEquals("Fixed the issue", rows.first { it.source.mac.instanceTag == "default" }.item.reason)
        assertEquals("Thanks", rows.first { it.source.mac.instanceTag == "default" }.item.replyText)
        assertNull(rows.first { it.source.mac.instanceTag == "nightly" }.item.replyText)
    }
    @Test fun completedNontruncatedReasonsAndDistantStopsRemainDistinct() {
        assertEquals(3, aggregate(entry(reason = "Done"), entry("two", reason = "Done with something else", time = 199.0),
            entry("three", reason = "Done", time = 50.0)).size)
    }
    @Test fun unicodeStopPreviewsMergeWithFullTextAndRetainReplyWithinExactMac() {
        val preview = entry(reason = "Fixed\u00a0the\u2003issue…").let { it.copy(item = it.item.copy(replyText = "Thanks")) }
        val fuller = entry("two", reason = "Fixed the issue completely", time = 199.0)
        val nightly = entry("two", "nightly", "Fixed the issue completely", 199.0)
        val rows = aggregate(preview, fuller, nightly)
        assertEquals(2, rows.size)
        val stable = rows.single { it.source.mac.instanceTag == "default" }
        assertEquals(fuller.item.reason, stable.item.reason)
        assertEquals("Thanks", stable.item.replyText)
        assertNull(rows.single { it.source.mac.instanceTag == "nightly" }.item.replyText)
    }

    @Test fun projectionReusesOnlyCurrentStopReasonsAcrossSnapshotReplacement() {
        val normalized = mutableListOf<String>()
        val cache = NativeAgentFeedStopReasonCache { normalized += it; normalizeAgentFeedStopReason(it) }
        val preview = entry(reason = "Fixed the…")
        val full = entry("two", reason = "Fixed the issue", time = 199.0)
        fun project(vararg rows: NativeAgentFeedEntry) = aggregateNativeAgentFeed(listOf(preview.source.copy(
            agentFeed = NativeAgentFeedState(NativeAgentFeedSnapshot(1, rows.map { it.item })))), cache)
        repeat(100) { assertEquals(1, project(preview, full).size) }
        assertEquals(listOf(preview.item.reason, full.item.reason), normalized)
        assertTrue(project().isEmpty())
        assertEquals(1, project(preview, full).size)
        assertEquals(4, normalized.size)
    }
    @Test fun triageDoesNotResolvePendingPermissionAndReadSurvivesSerialization() {
        val base = entry().let { it.copy(item = it.item.copy(kind = AgentFeedKind.PERMISSION, status = AgentFeedStatus.PENDING, requestId = "request")) }
        val state = NativeAgentFeedReadState(100.0)
        assertTrue(state.needsInput(base))
        val done = state.triage(base, false)
        assertFalse(done.needsInput(base)); assertTrue(base.item.needsInput)
        val restored = NativeAgentFeedReadState.decode(done.encode(), 999.0)
        assertEquals(100.0, restored.baseline, 0.0)
        assertTrue(restored.needsInput(base)) // local triage is transient; the pending request is still actionable
        assertFalse(restored.needsInput(base.copy(item = base.item.copy(status = AgentFeedStatus.RESOLVED))))
    }
    @Test fun stopReadTracksTurnAcrossPreviewIdentityReplacement() {
        val first = entry(); val alternate = entry("other", time = 199.0)
        val state = NativeAgentFeedReadState(100.0).interacted(first)
        assertFalse(state.needsInput(alternate))
        assertTrue(state.needsInput(alternate.copy(source = entry(build = "nightly").source)))
    }
    @Test fun persistedReadHistoryIsBounded() {
        var state = NativeAgentFeedReadState(0.0)
        repeat(1800) { state = state.interacted(entry(id = it.toString())) }
        assertEquals(1500, state.read.size)
        assertEquals(state, NativeAgentFeedReadState.decode(state.encode(), 0.0))
    }
}
