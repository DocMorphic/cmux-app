package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class AgentFeedTimelineTest {
    private val mac = NativeCredentialStore.PairedMac("PRIVATE-PAIRING-CODE", "mac", "Mac", instanceTag = "stable")
    private val item = NativeAgentFeedItem("event", "turn", "Claude", AgentFeedKind.STOP, AgentFeedStatus.TELEMETRY,
        10.0, 10.0, reason = "Report ready", workspaceId = "workspace", surfaceId = "terminal")
    private val failure = AgentFeedFailure("Delivery unconfirmed", AgentFeedDelivery.UNCONFIRMED, "Unsent draft")
    private val source = NativeFeedSource(mac, availability = NativeFeedAvailability.CONNECTED,
        capabilities = setOf(AGENT_FEED_CAPABILITY), agentFeed = NativeAgentFeedState(
            NativeAgentFeedSnapshot(1, listOf(item)), pending = setOf(item.id), failures = mapOf(item.id to failure)))
    private fun snapshot(sources: List<NativeFeedSource> = listOf(source), allowed: List<NativeCredentialStore.PairedMac> = listOf(mac),
        read: NativeAgentFeedReadState = NativeAgentFeedReadState(0.0)) =
        agentFeedUiSnapshot(sources, aggregateNativeAgentFeed(sources), allowed, read) { "Custom Mac name" }

    @Test fun displayProjectionPreservesPendingFailuresReadStateAndIdentityWithoutPairingMaterial() {
        val projected = snapshot()
        val row = projected.entries.single()
        assertEquals(NativeAgentFeedEntry(source, item).key, row.key)
        assertEquals("Custom Mac name", row.computerName)
        assertTrue(row.connected); assertTrue(row.pending); assertTrue(row.needsInput)
        assertEquals(failure, row.failure)
        assertFalse(projected.toString().contains("PRIVATE-PAIRING-CODE"))
        val read = NativeAgentFeedReadState(0.0).triage(NativeAgentFeedEntry(source, item), false)
        assertFalse(snapshot(read = read).entries.single().needsInput)
        // Local triage must not clear a pending delivery or its failure/draft.
        assertTrue(snapshot(read = read).entries.single().pending)
        assertEquals(failure, snapshot(read = read).entries.single().failure)
    }

    @Test fun savedModalWaitsForReloadButRejectsRevocationDifferentBuildAndAuthoritativeRemoval() {
        val row = snapshot().entries.single()
        val modal = AgentFeedModal.from("account-team", row, "terminal")
        assertEquals("Unsent draft", modal.draft)
        assertEquals(AgentFeedModalStatus.READY, modal.status("account-team", snapshot()))
        assertEquals(AgentFeedModalStatus.WAITING, modal.status("account-team", snapshot(emptyList())))
        assertEquals(AgentFeedModalStatus.GONE, modal.status("other-team", snapshot()))
        assertEquals(AgentFeedModalStatus.GONE, modal.status("account-team", snapshot(allowed = emptyList())))
        assertEquals(AgentFeedModalStatus.GONE, modal.status("account-team", snapshot(allowed = listOf(mac.copy(instanceTag = "nightly")))))
        val removed = source.copy(agentFeed = NativeAgentFeedState(NativeAgentFeedSnapshot(2, emptyList())))
        assertEquals(AgentFeedModalStatus.GONE, modal.status("account-team", snapshot(listOf(removed))))
        assertEquals(modal, AgentFeedModal.decode(modal.encode()))
        assertFalse(modal.encode().contains("PRIVATE-PAIRING-CODE"))
    }

    @Test fun reusedRowKeysCannotReopenDraftForAnotherOwnerRequestOrDestination() {
        val row = snapshot().entries.single()
        val modal = AgentFeedModal.from("account-team", row, "terminal")
        for (changed in listOf(row.copy(owner = AgentFeedUiOwner("other", "stable")),
            row.copy(item = item.copy(workstream = "new")), row.copy(item = item.copy(surfaceId = "new")),
            row.copy(item = item.copy(requestId = "new")), row.copy(item = item.copy(replyText = "Sent")))) {
            assertFalse(modal.matches(changed))
            val value = snapshot().copy(entries = listOf(changed), allowedOwners = snapshot().allowedOwners + changed.owner)
            assertEquals(AgentFeedModalStatus.GONE, modal.status("account-team", value))
        }
    }

    @Test fun offlineUnsupportedLoadingAndNoSourcePresentationsRemainDistinct() {
        val offline = snapshot(listOf(source.copy(availability = NativeFeedAvailability.OFFLINE)))
        assertFalse(offline.connected); assertFalse(offline.unsupported); assertTrue(offline.hasSources)
        assertFalse(offline.entries.single().connected)
        val unsupported = snapshot(listOf(source.copy(capabilities = emptySet())))
        assertFalse(unsupported.connected); assertTrue(unsupported.unsupported)
        val loading = snapshot(listOf(source.copy(agentFeed = source.agentFeed.copy(loading = true, error = "Read failed"))))
        assertTrue(loading.updating); assertTrue(loading.refreshFailed)
        val empty = snapshot(emptyList())
        assertFalse(empty.hasSources); assertFalse(empty.unsupported); assertFalse(empty.updating)
    }

    @Test fun completedReadsCannotMarkAReusedEventOrChangedQuestionAsRead() {
        val original = NativeAgentFeedEntry(source, item)
        val rendered = original.ui("Mac", true)
        assertTrue(rendered.matchesIdentity(original.copy(item = item.copy(status = AgentFeedStatus.RESOLVED))))
        val replacements = listOf(item.copy(id = "new"), item.copy(workstream = "new"),
            item.copy(requestId = "new"), item.copy(kind = AgentFeedKind.MESSAGE),
            item.copy(workspaceId = "new"), item.copy(surfaceId = "new"),
            item.copy(questions = listOf(AgentFeedQuestion("q", null, "Changed question", false, emptyList()))))
        replacements.forEach { assertFalse(rendered.matchesIdentity(original.copy(item = it))) }
        assertFalse(rendered.matchesIdentity(original.copy(source = source.copy(mac = mac.copy(instanceTag = "nightly")))))
    }
}
