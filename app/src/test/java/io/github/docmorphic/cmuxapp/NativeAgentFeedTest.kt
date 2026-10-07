package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeAgentFeedTest {
    private fun row(id: String = "event", kind: String = "permissionRequest") = JSONObject()
        .put("id", id).put("workstream_id", "turn").put("source", "claude").put("kind", kind)
        .put("status", "pending").put("request_id", "request").put("created_at", "2026-10-07T12:00:00.123Z")
        .put("updated_at", 1791374400.123).put("workspace_id", "workspace").put("surface_id", "surface")
    private fun snapshot(revision: Long = 1, vararg rows: JSONObject) = JSONObject().put("revision", revision)
        .put("items", JSONArray(rows.toList().ifEmpty { listOf(row()) }))
    private suspend fun awaitState(predicate: () -> Boolean) = withTimeout(3_000) { while (!predicate()) delay(5) }

    @Test fun decodingDropsMalformedAndNotificationRowsButKeepsUnsupportedRowsInert() {
        val value = snapshot(3, row().put("created_at", "invalid"), row("notification").put("source", " Notification "),
            row("unknown", "futureKind"), row("good"), row("good"))
        val decoded = NativeAgentFeedWire.decode(value)
        assertEquals(listOf("good", "unknown"), decoded.items.map { it.id })
        assertTrue(decoded.items.first().needsInput)
        assertEquals(AgentFeedKind.UNSUPPORTED, decoded.items.last().kind)
        assertFalse(decoded.items.last().needsInput)
        assertEquals(1791374400.123, decoded.items.first().createdAt, 0.001)
    }
    @Test fun byteBoundsKeepWholeUnicodeAndFlagTruncatedPreview() {
        val preview = "🧑".repeat(3000)
        val item = NativeAgentFeedWire.decode(snapshot(1, row().put("full_text_preview", preview))).items.single()
        assertEquals(8192, item.fullTextPreview!!.toByteArray().size)
        assertFalse(Character.isHighSurrogate(item.fullTextPreview.last()))
        assertTrue(item.fullTextTruncated)
        assertTrue(NativeAgentFeedWire.decode(snapshot(1, row().put("id", "x".repeat(513)))).items.isEmpty())
    }
    @Test fun questionAnswersUseLabelsInQuestionOrderAndCustomReplacesOptions() {
        val question = AgentFeedQuestion("q", null, "Which?", true, listOf(AgentFeedOption("b", "Blue", null), AgentFeedOption("r", "Red", null)))
        assertEquals("Blue, Red", question.answer(setOf("r", "b"), ""))
        assertEquals("Green", question.answer(setOf("r"), " Green "))
        assertNull(question.answer(setOf("unknown"), " "))
    }
    @Test fun eventDuringReadRejectsStaleSnapshotAndFetchesTrailingRevision() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var reads = 0
        val session = NativeAgentFeedSession(this, { true }, { _, _ ->
            reads++; if (reads == 1) { entered.complete(Unit); release.await(); snapshot(1) } else snapshot(3)
        })
        try {
            session.start(); entered.await(); session.changed(JSONObject().put("revision", 3)); release.complete(Unit)
            awaitState { session.state.value.snapshot?.revision == 3L }
            assertEquals(2, reads)
        } finally { session.close() }
    }
    @Test fun equalRevisionEventRetriesAfterTransientFailure() = runBlocking {
        var reads = 0
        val session = NativeAgentFeedSession(this, { true }, { _, _ ->
            reads++; if (reads == 1) error("network") else snapshot(1)
        })
        try {
            session.start(); awaitState { session.state.value.error != null }
            session.changed(JSONObject().put("revision", 1)); awaitState { session.state.value.snapshot != null }
            assertEquals(2, reads); assertNull(session.state.value.error)
        } finally { session.close() }
    }
    @Test fun decisionIsSingleFlightAndOldSnapshotCannotUndoAcknowledgement() = runBlocking {
        val initial = NativeAgentFeedWire.decode(snapshot())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var sends = 0
        val session = NativeAgentFeedSession(this, { true }, { method, params ->
            if (method == "feed.list") snapshot() else {
                assertEquals("feed.permission.reply", method); assertEquals("request", params.getString("request_id"))
                assertEquals("once", params.getString("mode")); sends++; entered.complete(Unit); release.await(); JSONObject()
            }
        }, initial)
        try {
            val item = initial.items.single(); val decision = AgentFeedDecision("permission", "once")
            val first = async { session.decide(item, decision) }; entered.await()
            assertFalse(session.decide(item, decision)); release.complete(Unit); assertTrue(first.await())
            session.start(); awaitState { session.state.value.snapshot != null && !session.state.value.loading }
            delay(30)
            assertEquals(AgentFeedStatus.RESOLVED, session.state.value.snapshot!!.items.single().status)
            assertEquals(1, sends)
        } finally { session.close() }
    }
    @Test fun changedQuestionContentCannotReceiveAnswersFromAnOlderVisibleRequest() = runBlocking {
        val question = row(kind = "question").put("questions", JSONArray().put(JSONObject()
            .put("id", "q").put("prompt", "Original prompt").put("options", JSONArray())))
        val initial = NativeAgentFeedWire.decode(snapshot(1, question))
        val changed = JSONObject(question.toString()).apply {
            getJSONArray("questions").getJSONObject(0).put("prompt", "Replacement prompt")
        }
        var sends = 0
        val session = NativeAgentFeedSession(this, { true }, { method, _ ->
            if (method == "feed.list") snapshot(2, changed) else { sends++; JSONObject() }
        }, initial)
        try {
            session.start(); awaitState { session.state.value.snapshot?.revision == 2L }
            assertTrue(runCatching { session.decide(initial.items.single(), AgentFeedDecision("question", selections = listOf("Old answer"))) }.isFailure)
            assertEquals(0, sends)
            assertTrue(session.decide(session.state.value.snapshot!!.items.single(), AgentFeedDecision("question", selections = listOf("New answer"))))
            assertEquals(1, sends)
        } finally { session.close() }
    }
    @Test fun planRevisionUsesManualModeWithFeedbackAsOnIos() = runBlocking {
        val initial = NativeAgentFeedWire.decode(snapshot(1, row(kind = "exitPlan")))
        var sent: JSONObject? = null
        val session = NativeAgentFeedSession(this, { true }, { method, params ->
            if (method == "feed.list") snapshot(1, row(kind = "exitPlan")) else {
                assertEquals("feed.exit_plan.reply", method); sent = params; JSONObject()
            }
        }, initial)
        try {
            assertTrue(runCatching { session.decide(initial.items.single(), AgentFeedDecision("exit_plan", "revise", feedback = "Include tests")) }.isFailure)
            assertNull(sent)
            assertTrue(session.decide(initial.items.single(), AgentFeedDecision("exit_plan", "manual", feedback = "Include tests")))
            assertEquals("manual", sent!!.getString("mode")); assertEquals("Include tests", sent!!.getString("feedback"))
        } finally { session.close() }
    }
    @Test fun agentPreselectedFuturePlanModeIsForwardedWithoutSilentlyChoosingAnotherMode() = runBlocking {
        val initial = NativeAgentFeedWire.decode(snapshot(1, row(kind = "exitPlan").put("default_mode", "future-mode")))
        var sent: JSONObject? = null
        val session = NativeAgentFeedSession(this, { true }, { _, params -> sent = params; JSONObject() }, initial)
        try {
            assertTrue(runCatching { session.decide(initial.items.single(), AgentFeedDecision("exit_plan", "unrelated-mode")) }.isFailure)
            assertNull(sent)
            assertTrue(session.decide(initial.items.single(), initial.items.single().planApproval()))
            assertEquals("future-mode", sent!!.getString("mode"))
        } finally { session.close() }
    }
    @Test fun terminalReplyUsesExactEventAndNeverAutomaticallyRetriesAmbiguousInput() = runBlocking {
        val initial = NativeAgentFeedWire.decode(snapshot(1, row(kind = "stop")))
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val session = NativeAgentFeedSession(this, { true }, { method, params -> calls += method to params; error("lost acknowledgement") }, initial)
        try {
            assertFalse(session.terminalReply(initial.items.single(), " hello "))
            assertEquals(1, calls.size)
            assertEquals("mobile.terminal.paste", calls.single().first)
            assertEquals("event", calls.single().second.getString("feed_event_id"))
            assertEquals("workspace", calls.single().second.getString("workspace_id"))
            assertEquals("surface", calls.single().second.getString("surface_id"))
            assertEquals("hello", calls.single().second.getString("text"))
            assertEquals(AgentFeedDelivery.UNCONFIRMED, session.state.value.failures["event"]?.delivery)
            assertNull(session.state.value.snapshot!!.items.single().replyText)
        } finally { session.close() }
    }
    @Test fun missingSubmitAcknowledgementDoesNotRecordSuccessfulReply() = runBlocking {
        val initial = NativeAgentFeedWire.decode(snapshot(1, row(kind = "stop")))
        val session = NativeAgentFeedSession(this, { true }, { _, _ -> JSONObject().put("submitted", false) }, initial)
        try {
            assertFalse(session.terminalReply(initial.items.single(), "next"))
            assertNull(session.state.value.snapshot!!.items.single().replyText)
        } finally { session.close() }
    }
    @Test fun fullTextUsesUtf8OffsetsAndPinsVersion() = runBlocking {
        val initial = NativeAgentFeedWire.decode(snapshot()); var calls = 0
        val session = NativeAgentFeedSession(this, { true }, { method, params ->
            assertEquals("feed.text", method); assertEquals("event", params.getString("item_id")); calls++
            if (calls == 1) {
                assertEquals(0, params.getInt("offset")); assertFalse(params.has("version"))
                JSONObject().put("text", "🧑").put("version", 7.0).put("next_offset", 4)
            } else {
                assertEquals(4, params.getInt("offset")); assertEquals(7.0, params.getDouble("version"), 0.0)
                JSONObject().put("text", " done").put("version", 7.0)
            }
        }, initial)
        try { assertEquals("🧑 done", session.fullText(initial.items.single())); assertEquals(2, calls) }
        finally { session.close() }
    }
    @Test fun fullTextRejectsChangedVersionOrNonadvancingOffset() = runBlocking {
        for (changed in listOf(false, true)) {
            var calls = 0; val initial = NativeAgentFeedWire.decode(snapshot())
            val session = NativeAgentFeedSession(this, { true }, { _, _ ->
                calls++; JSONObject().put("text", "x").put("version", if (calls == 2) 2 else 1)
                    .put("next_offset", if (changed) 1 else 0)
            }, initial)
            try { assertTrue(runCatching { session.fullText(initial.items.single()) }.isFailure) }
            finally { session.close() }
        }
    }
    @Test fun retiredOwnerCannotPublishReplyOrSendNewRequests() = runBlocking {
        val initial = NativeAgentFeedWire.decode(snapshot()); var allowed = true; var requests = 0
        val session = NativeAgentFeedSession(this, { allowed }, { _, _ -> requests++; allowed = false; JSONObject() }, initial)
        try {
            assertTrue(runCatching { session.decide(initial.items.single(), AgentFeedDecision("permission", "once")) }.isFailure)
            assertEquals(AgentFeedStatus.PENDING, session.state.value.snapshot!!.items.single().status)
            assertTrue(runCatching { session.fullText(initial.items.single()) }.isFailure)
            assertEquals(1, requests)
        } finally { session.close() }
    }
}
