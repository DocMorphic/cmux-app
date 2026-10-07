package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class AgentFeedPresentationTest {
    private fun row(kind: AgentFeedKind = AgentFeedKind.PERMISSION) = NativeAgentFeedItem("id", "turn", "claude", kind,
        AgentFeedStatus.PENDING, 10.0, 10.0, requestId = "request")
    @Test fun toolPayloadsUnwrapAndPreferHumanFieldsWithoutLeakingWireJson() {
        assertEquals("cat README", NativeAgentFeedPresentation.humanizedToolText("{\"command\":\"cat README\",\"timeout\":42}"))
        assertEquals("a b", NativeAgentFeedPresentation.humanizedToolText(JSONObject.quote("{\"text\":\"a\\nb\"}")))
        assertEquals("a: 1 · b: value · c: 0", NativeAgentFeedPresentation.humanizedToolText("{\"z\":2,\"b\":\"value\",\"c\":false,\"a\":1}"))
        assertNull(NativeAgentFeedPresentation.humanizedToolText("{\"nested\":{\"secret\":1}}"))
        assertNull(NativeAgentFeedPresentation.humanizedToolText("{broken json"))
        assertNull(NativeAgentFeedPresentation.humanizedToolText("[1,2]"))
    }
    @Test fun oldPlanEnvelopesNeverAppearAsRawJson() {
        for (key in listOf("plan", "planText", "text")) {
            val json = JSONObject().put(key, "# Plan\n\n**Ship it**").toString()
            assertEquals("# Plan\n\n**Ship it**", NativeAgentFeedPresentation.planText(json))
            assertEquals("# Plan\n\n**Ship it**", NativeAgentFeedPresentation.planText(JSONObject.quote(json)))
        }
        assertNull(NativeAgentFeedPresentation.planText("{invalid}"))
        assertEquals("Summary", NativeAgentFeedPresentation.from(row(AgentFeedKind.PLAN).copy(plan = "{broken", planSummary = "Summary")).output)
    }
    @Test fun pendingQuestionsSuppressGenericOutputAndQuotesButRetainOwnPrompt() {
        val item = row(AgentFeedKind.QUESTION).copy(fullTextPreview = "redundant", text = "redundant", context = mapOf("last_user_message" to "duplicate"),
            questions = listOf(AgentFeedQuestion("q", "Header", "Prompt", false, emptyList())))
        val pending = NativeAgentFeedPresentation.from(item)
        assertNull(pending.output); assertNull(pending.quote); assertTrue(pending.visible)
        val resolved = NativeAgentFeedPresentation.from(item.copy(status = AgentFeedStatus.RESOLVED, fullTextPreview = null))
        assertEquals("Header\nPrompt", resolved.output)
    }
    @Test fun outputAndVisibilityFollowEventKindInsteadOfArbitraryFallbackFields() {
        val empty = row(AgentFeedKind.MESSAGE).copy(text = null, reason = "wrong field", toolResult = "wire noise", title = "metadata")
        assertFalse(NativeAgentFeedPresentation.from(empty).visible)
        assertNull(NativeAgentFeedPresentation.from(empty).output)
        val tool = NativeAgentFeedPresentation.from(row(AgentFeedKind.TOOL_RESULT).copy(toolResultIsError = true,
            toolResult = "{\"message\":\"Build failed\"}", fullTextPreview = "raw transcript"))
        assertNull(tool.output); assertEquals("Build failed", tool.tool); assertTrue(tool.visible)
        assertTrue(NativeAgentFeedPresentation.from(row(AgentFeedKind.TODOS)).visible)
        assertFalse(NativeAgentFeedPresentation.from(row(AgentFeedKind.UNSUPPORTED)).visible)
    }
    @Test fun decisionsUseReadableLabelsAndMapQuestionIdsToOptions() {
        fun resolution(decision: AgentFeedDecision) = NativeAgentFeedPresentation.from(row().copy(status = AgentFeedStatus.RESOLVED, decision = decision)).resolution
        assertEquals("Always allowed", resolution(AgentFeedDecision("permission", "always")))
        assertEquals("Permissions bypassed", resolution(AgentFeedDecision("permission", "bypass")))
        assertEquals("Revision requested: Include tests", resolution(AgentFeedDecision("exit_plan", "revise", feedback = "Include tests")))
        val item = row(AgentFeedKind.QUESTION).copy(status = AgentFeedStatus.RESOLVED, decision = AgentFeedDecision("question", selections = listOf("a", "Other")),
            questions = listOf(AgentFeedQuestion("q", null, "Which?", false, listOf(AgentFeedOption("a", "Alpha", null)))))
        assertEquals("Answered: Alpha, Other", NativeAgentFeedPresentation.from(item).resolution)
        assertEquals("Expired unanswered", NativeAgentFeedPresentation.from(item.copy(status = AgentFeedStatus.EXPIRED)).resolution)
    }
    @Test fun workspaceFallbackUsesRemotePosixPathAndTabIsOptIn() {
        val model = NativeAgentFeedPresentation.from(row().copy(cwd = "/Users/me/project/", surfaceTitle = "Agent 2"))
        assertEquals("project", model.location(false)); assertEquals("project › Agent 2", model.location(true))
        assertEquals("Claude", model.author)
    }
    @Test fun snippetsKeepComposedEmojiAndStopAuthorOmitsRedundantHeadline() {
        val glyph = "👩🏽‍💻"
        val tool = NativeAgentFeedPresentation.humanizedToolText(glyph.repeat(205))!!
        assertEquals(glyph.repeat(200) + "…", tool)
        val stop = NativeAgentFeedPresentation.from(row(AgentFeedKind.STOP).copy(reason = "\n${glyph.repeat(95)}\nsecond line", replyText = "Thanks"))
        assertNull(stop.headline); assertEquals(glyph.repeat(90) + "…", stop.replyReference)
    }
    @Test fun compactTimeLabelsUseMinuteHourDayBuckets() {
        assertEquals("", agentFeedTimeLabel(0.0, 1010.0, Locale.US))
        assertEquals("now", agentFeedTimeLabel(1000.0, 1010.0, Locale.US))
        assertEquals("2m", agentFeedTimeLabel(1000.0, 1120.0, Locale.US))
        assertEquals("3h", agentFeedTimeLabel(1000.0, 11800.0, Locale.US))
        assertEquals("2d", agentFeedTimeLabel(1000.0, 173800.0, Locale.US))
    }
}
