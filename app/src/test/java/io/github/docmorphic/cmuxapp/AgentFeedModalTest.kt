package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class AgentFeedModalTest {
    private val mac = NativeCredentialStore.PairedMac("private-token", "mac", "Mac", instanceTag = "build-a")
    private val item = NativeAgentFeedItem("event", "turn", "Claude", AgentFeedKind.STOP, AgentFeedStatus.TELEMETRY,
        5.0, 5.0, workspaceId = "workspace", surfaceId = "surface", reason = "Output")
    private val entry = NativeAgentFeedEntry(NativeFeedSource(mac), item)
    private val target = AgentFeedModal.from("account-team", entry, "terminal")
    private fun status(value: AgentFeedModal = target, scope: String = "account-team",
        allowed: List<NativeCredentialStore.PairedMac> = listOf(mac), sources: List<NativeFeedSource> = emptyList(),
        entries: List<NativeAgentFeedEntry> = emptyList()) = value.status(scope, allowed, sources, entries)

    @Test fun coldStartupWaitsForTheAuthorizedMacButRemovalAndScopeChangesClearTheDraft() {
        assertEquals(AgentFeedModalStatus.WAITING, status())
        assertEquals(AgentFeedModalStatus.GONE, status(scope = "other-team"))
        assertEquals(AgentFeedModalStatus.GONE, status(allowed = emptyList()))
        assertEquals(AgentFeedModalStatus.GONE, status(allowed = listOf(mac.copy(instanceTag = "build-b"))))
        assertEquals(AgentFeedModalStatus.READY, status(entries = listOf(entry)))
    }
    @Test fun authoritativeRemovalAndReusedEventDestinationCannotReceiveOldDraft() {
        val removed = entry.source.copy(agentFeed = NativeAgentFeedState(snapshot = NativeAgentFeedSnapshot(5, emptyList())))
        assertEquals(AgentFeedModalStatus.GONE, status(sources = listOf(removed)))
        for (replacement in listOf(item.copy(workstream = "new"), item.copy(surfaceId = "new"), item.copy(replyText = "Already sent"))) {
            assertEquals(AgentFeedModalStatus.GONE, status(entries = listOf(entry.copy(item = replacement))))
        }
    }
    @Test fun revisionDraftRequiresTheSameStillPendingRequestButReaderMayShowResolvedEvents() {
        val plan = entry.copy(item = item.copy(kind = AgentFeedKind.PLAN, status = AgentFeedStatus.PENDING, requestId = "request"))
        val revise = AgentFeedModal.from("account-team", plan, "revise")
        assertTrue(revise.matches(plan)); assertFalse(revise.matches(plan.copy(item = plan.item.copy(requestId = "replacement"))))
        val done = plan.copy(item = plan.item.copy(status = AgentFeedStatus.RESOLVED))
        assertFalse(revise.matches(done)); assertTrue(revise.copy(mode = "read").matches(done))
    }
    @Test fun restorationCarriesOnlyTargetAndDraftNotCredentialsOrMessageContent() {
        val value = target.copy(draft = "Run next\n👩🏽‍💻", expanded = true, raw = true)
        assertEquals(value, AgentFeedModal.decode(value.encode()))
        assertFalse(value.encode().contains("private-token")); assertFalse(value.encode().contains("Output"))
        assertNull(AgentFeedModal.decode("broken")); assertNull(AgentFeedModal.decode(value.copy(mode = "unknown").encode()))
    }
    @Test fun questionDraftIsBoundToOrderedContentEvenWhenServerReusesTheRequestId() {
        val prompt = AgentFeedQuestion("q", "Heading", "Private prompt", false,
            listOf(AgentFeedOption("a", "First", "Description"), AgentFeedOption("b", "Second", null)))
        val pending = entry.copy(item = item.copy(kind = AgentFeedKind.QUESTION, status = AgentFeedStatus.PENDING,
            requestId = "request", questions = listOf(prompt)))
        val modal = AgentFeedModal.from("account-team", pending, "question")
            .copy(draft = AgentFeedQuestionDrafts().write(prompt, "Answer").encode())
        assertEquals(modal, AgentFeedModal.decode(modal.encode()))
        assertTrue(modal.matches(pending)); assertFalse(modal.encode().contains("Private prompt"))
        val changed = listOf(prompt.copy(prompt = "New prompt"), prompt.copy(header = "New heading"),
            prompt.copy(multiSelect = true), prompt.copy(options = prompt.options.reversed()),
            prompt.copy(options = listOf(prompt.options.first().copy(description = "Changed"))))
        changed.forEach { assertFalse(modal.matches(pending.copy(item = pending.item.copy(questions = listOf(it))))) }
        assertFalse(modal.matches(pending.copy(item = pending.item.copy(requestId = "next"))))
        assertFalse(modal.matches(pending.copy(item = pending.item.copy(status = AgentFeedStatus.RESOLVED))))
        assertFalse(modal.matches(pending.copy(item = pending.item.copy(questions = emptyList()))))
        assertEquals(AgentFeedModalStatus.WAITING, status(value = modal))
    }
    @Test fun approvalUsesTheAgentsPreselectedSupportedModeAndExposesEverySourceMode() {
        assertEquals("manual", item.planApproval().mode)
        assertEquals("future-mode", item.copy(defaultMode = "future-mode").planApproval().mode)
        assertEquals(setOf("manual", "autoAccept", "bypassPermissions", "ultraplan"), agentFeedPlanModes.map { it.first }.toSet())
        agentFeedPlanModes.forEach { (mode, _) -> assertEquals(mode, item.copy(defaultMode = mode).planApproval().mode) }
    }
}
