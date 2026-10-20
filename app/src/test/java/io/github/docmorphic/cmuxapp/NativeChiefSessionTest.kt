package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NativeChiefSessionTest {
    private val instant = Instant.parse("2026-10-20T12:00:00Z")
    private fun message(seq: Int, id: String = "m$seq", text: String = "Message $seq", human: Boolean = false,
        clientId: String? = null, parts: List<NativeChiefPart> = emptyList()) = NativeChiefMessage(id, seq.toULong(),
        if (human) "user" else "chief", if (human) "You" else "Chief", if (human) "human" else "chief", text,
        instant, emptyList(), parts, null, clientId)
    private fun page(vararg messages: NativeChiefMessage, more: Boolean = false, cursor: String? = "cursor") =
        NativeChiefRead("chief-main", false, messages.toList(), more, 0uL, 1, messages.maxOfOrNull { it.seq }, cursor)
    private fun conversation(id: String, pinned: Boolean = false, offset: Long = 0) =
        NativeChiefConversation(id, id, "Preview $id", 0, pinned, false, instant.plusSeconds(offset))
    private fun question(id: String = "q", multi: Boolean = false, other: Boolean = false) = NativeChiefQuestion(id,
        NativeChiefQuestion.Source(NativeChiefQuestion.Harness.CHIEF, "main", null, null, null, null, null),
        listOf(NativeChiefQuestion.Item("item", null, "Pick a region", listOf(
            NativeChiefQuestion.Option("us", "US", null, null), NativeChiefQuestion.Option("eu", "EU", null, null)), multi, other)),
        NativeChiefQuestion.State.PENDING, null, null)
    private fun approval(id: String = "request", decided: String? = null, options: List<String> = listOf("allow", "deny")) =
        NativeChiefPart("approval", null, NativeChiefApproval(id, "Run deploy?", options, decided), null)

    private data class Read(val before: ULong?, val after: ULong?, val cursor: String?, val wait: Boolean, val limit: Int)
    private class Api : NativeChiefApi {
        val snapshots = Channel<Result<NativeChiefSnapshot>>(Channel.UNLIMITED)
        val snapshotRequests = mutableListOf<Pair<String?, Boolean>>()
        var statusValue = NativeChiefStatus(false, null, null, "", "", "", "", 0, 0, emptyList(), "")
        val readRequests = mutableListOf<Read>()
        val reads = Channel<Result<NativeChiefRead>>(Channel.UNLIMITED)
        val waits = Channel<Result<NativeChiefRead>>(Channel.UNLIMITED)
        var readBlock: (suspend (Read) -> NativeChiefRead)? = null
        val sends = mutableListOf<Pair<String, String?>>()
        var sendBlock: suspend (String, String?) -> NativeChiefSent = { _, id -> NativeChiefSent("chief-main", "sent", id, false) }
        val approvals = mutableListOf<Pair<String?, String>>()
        var approvalBlock: suspend () -> NativeChiefApproved = { NativeChiefApproved("chief-main", "request", "allow", true) }
        val answers = mutableListOf<Pair<List<String>, String?>>()
        var answerBlock: suspend () -> NativeChiefAnswered = { NativeChiefAnswered("chief-main", "m1", listOf("US")) }
        val marks = mutableListOf<ULong>()
        var stops = 0
        override suspend fun status() = statusValue
        override suspend fun conversations(afterCursor: String?, waitForChange: Boolean): NativeChiefSnapshot {
            snapshotRequests += afterCursor to waitForChange; return snapshots.receive().getOrThrow()
        }
        override suspend fun read(conversation: String?, beforeSeq: ULong?, limit: Int, afterSeq: ULong?, afterCursor: String?, waitForChange: Boolean): NativeChiefRead {
            check(conversation == "chief-main")
            val request = Read(beforeSeq, afterSeq, afterCursor, waitForChange, limit); readRequests += request
            return readBlock?.invoke(request) ?: (if (waitForChange) waits else reads).receive().getOrThrow()
        }
        override suspend fun send(text: String, conversation: String?, from: String?, startTurn: Boolean, clientMessageId: String?): NativeChiefSent {
            sends += text to clientMessageId; return sendBlock(text, clientMessageId)
        }
        override suspend fun markRead(conversation: String?, upToSeq: ULong): NativeChiefMarked {
            marks += upToSeq; return NativeChiefMarked("chief-main", upToSeq, true)
        }
        override suspend fun stop(conversation: String?): NativeChiefStopped { stops++; return NativeChiefStopped("chief-main", true) }
        override suspend fun approve(conversation: String?, request: String?, choice: String): NativeChiefApproved {
            approvals += request to choice; return approvalBlock()
        }
        override suspend fun answer(conversation: String?, message: String?, choices: List<String>, text: String?): NativeChiefAnswered {
            answers += choices to text; return answerBlock()
        }
        override suspend fun open(windowId: String?, workspaceId: String?) = NativeChiefOpened(windowId, true)
    }

    @Test fun homeSortsPinnedRowsSearchesAndWaitsOnTheReturnedOpaqueCursor() = runTest {
        val api = Api(); val session = NativeChiefHomeSession(this, api) { true }
        try {
            api.snapshots.send(Result.success(NativeChiefSnapshot(listOf(conversation("older"), conversation("new", offset = 30), conversation("pin", true)), "first")))
            session.start(); runCurrent()
            assertEquals(listOf("pin", "new", "older"), session.state.value.conversations.map { it.id })
            assertEquals("pin", session.state.value.pinned?.id)
            assertEquals(listOf("new"), session.state.value.matching(" PREVIEW NEW ").map { it.id })
            assertEquals(listOf(null to false, "first" to true), api.snapshotRequests)
            api.snapshots.send(Result.success(NativeChiefSnapshot(listOf(conversation("updated")), "next"))); runCurrent()
            assertEquals("updated", session.state.value.pinned?.id)
            assertEquals("next" to true, api.snapshotRequests.last())
        } finally { session.close() }
    }

    @Test fun startupFailureDoesNotManufactureAnActionableConversationAndARefreshRecovers() = runTest {
        val api = Api(); api.statusValue = api.statusValue.copy(started = true, defaultConversation = "chief-main", startFailure = "Home permission denied")
        val session = NativeChiefHomeSession(this, api) { true }
        try {
            api.snapshots.send(Result.success(NativeChiefSnapshot(emptyList(), "first"))); session.start(); runCurrent()
            assertTrue(session.state.value.conversations.isEmpty()); assertEquals("Home permission denied", session.state.value.error)
            api.statusValue = api.statusValue.copy(startFailure = null)
            session.refresh(); runCurrent()
            assertEquals(null to false, api.snapshotRequests.last())
            api.snapshots.send(Result.success(NativeChiefSnapshot(emptyList(), "second"))); runCurrent()
            assertEquals("chief-main", session.state.value.pinned?.id); assertNull(session.state.value.error)
        } finally { session.close() }
    }

    @Test fun initialHomeFailureRetriesWithoutAViewRecreationAndWithoutBusyPolling() = runTest {
        val api = Api(); val session = NativeChiefHomeSession(this, api) { true }
        try {
            api.snapshots.send(Result.failure(java.io.IOException("temporarily offline"))); session.start(); runCurrent()
            assertEquals(1, api.snapshotRequests.size); assertFalse(session.state.value.loading)
            advanceTimeBy(999); runCurrent(); assertEquals(1, api.snapshotRequests.size)
            api.snapshots.send(Result.success(NativeChiefSnapshot(listOf(conversation("back")), "c")))
            advanceTimeBy(1); runCurrent(); assertEquals("back", session.state.value.pinned?.id)
            assertEquals(3, api.snapshotRequests.size)
        } finally { session.close() }
    }

    @Test fun lateHomeSnapshotCannotPublishAfterOwnerRetires() = runTest {
        val api = Api(); var current = true; val session = NativeChiefHomeSession(this, api) { current }
        session.start(); runCurrent(); current = false
        api.snapshots.send(Result.success(NativeChiefSnapshot(listOf(conversation("private")), "c"))); runCurrent()
        assertTrue(session.state.value.conversations.isEmpty()); assertFalse(session.state.value.loading)
        assertTrue(runCatching { session.start() }.isFailure)
    }

    @Test fun transcriptFillsMultipleMissedPagesBeforePublishingTheNextCursor() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(message(1), message(2)))); session.start(); runCurrent()
            api.waits.send(Result.success(page(message(7), message(8), more = true, cursor = "changed")))
            api.reads.send(Result.success(page(message(5), message(6), more = true)))
            api.reads.send(Result.success(page(message(3), message(4), more = false))); runCurrent()
            assertEquals((1..8).map(Int::toString), session.state.value.messages.map { it.seq.toString() })
            assertEquals(listOf(7uL, 5uL), api.readRequests.mapNotNull { it.before })
            assertEquals(Read(null, 8uL, "changed", true, 200), api.readRequests.last())
        } finally { session.close() }
    }

    @Test fun invalidHistoryProgressStopsInsteadOfSpinningAndPreservesTheLastTranscript() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(message(1)))); session.start(); runCurrent()
            api.waits.send(Result.success(page(message(7), more = true)))
            api.reads.send(Result.success(page(message(7), more = true))); runCurrent()
            assertEquals(listOf("m1"), session.state.value.messages.map { it.id })
            assertFalse(session.state.value.connected); assertEquals("Chief history did not advance", session.state.value.error)
            val count = api.readRequests.size; advanceTimeBy(2999); runCurrent(); assertEquals(count, api.readRequests.size)
        } finally { session.close() }
    }

    @Test fun historyPagingRetainsOlderActionCardsWhileNewTailUpdatesArrive() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(message(500), more = true))); session.start(); runCurrent()
            api.reads.send(Result.success(page(message(2, parts = listOf(approval())), more = false)))
            session.loadOlder()
            api.waits.send(Result.success(page(message(501)))); runCurrent()
            assertEquals(listOf("m2", "m500", "m501"), session.state.value.messages.map { it.id })
            assertNotNull(NativeChiefPresentation.pendingPart(session.state.value.messages.first()))
            assertFalse(session.state.value.hasMore)
        } finally { session.close() }
    }

    @Test fun acceptedSendAndConcurrentPollEchoNeverReintroduceTheOptimisticRow() = runTest {
        val api = Api(); val ack = CompletableDeferred<NativeChiefSent>()
        api.sendBlock = { _, _ -> ack.await() }
        val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "client-1" })
        try {
            api.reads.send(Result.success(page())); session.start(); runCurrent(); session.editDraft("Hello")
            val send = async { session.send() }; runCurrent()
            assertEquals(NativeChiefDelivery.SENDING, session.state.value.outgoing.single().delivery)
            session.editDraft("Next draft")
            api.waits.send(Result.success(page(message(1, "server-1", "Hello", true, "client-1")))); runCurrent()
            assertTrue(session.state.value.outgoing.isEmpty())
            ack.complete(NativeChiefSent("chief-main", "server-1", "client-1", false))
            api.reads.send(Result.failure(java.io.IOException("read failed after commit"))); runCurrent(); send.await()
            assertEquals(listOf("server-1"), session.state.value.messages.map { it.id }); assertTrue(session.state.value.outgoing.isEmpty())
            assertEquals("Next draft", session.state.value.draft); assertEquals(listOf("Hello" to "client-1"), api.sends)
        } finally { session.close() }
    }

    @Test fun acknowledgedSendRetainsItsIdentityUntilALegacyEchoCanReplaceIt() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "client-1" })
        try {
            api.reads.send(Result.success(page())); session.start(); runCurrent(); session.editDraft("Legacy hello")
            api.sendBlock = { _, id -> NativeChiefSent("chief-main", "legacy-server", id, true) }
            api.reads.send(Result.failure(java.io.IOException("follow-up unavailable")))
            session.send()
            assertEquals("client-1", session.state.value.outgoing.single().clientMessageId)
            assertEquals(NativeChiefDelivery.QUEUED, session.state.value.outgoing.single().delivery)
            api.waits.send(Result.success(page(message(1, "legacy-server", "Legacy hello", true)))); runCurrent()
            assertTrue(session.state.value.outgoing.isEmpty()); assertEquals(1, api.sends.size)
        } finally { session.close() }
    }

    @Test fun anUncertainSendIsNotRetriedAndACommittedEchoStillReconcilesIt() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "client-1" })
        try {
            api.reads.send(Result.success(page())); session.start(); runCurrent(); session.editDraft("Do it")
            api.sendBlock = { _, _ -> throw MobileRpcOutcomeUnknown() }; session.send()
            assertEquals(NativeChiefDelivery.UNCONFIRMED, session.state.value.outgoing.single().delivery)
            api.waits.send(Result.success(page(message(1, "committed", "Do it", true, "client-1")))); runCurrent()
            assertTrue(session.state.value.outgoing.isEmpty()); assertEquals(1, api.sends.size)
        } finally { session.close() }
    }

    @Test fun identicalMessagesWithDifferentWireClientIdsDoNotStealAnotherPendingSend() = runTest {
        val api = Api(); var nextId = 0; val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "client-${++nextId}" })
        try {
            api.reads.send(Result.success(page())); session.start(); runCurrent()
            api.sendBlock = { _, id -> NativeChiefSent("chief-main", null, id, true) }
            repeat(2) { session.editDraft("Same text"); api.reads.send(Result.success(page())); session.send() }
            api.waits.send(Result.success(page(message(1, "first", "Same text", true, "client-1"), message(2, "another", "Same text", true, "unrelated")))); runCurrent()
            assertEquals(listOf("client-2"), session.state.value.outgoing.map { it.clientMessageId })
        } finally { session.close() }
    }

    @Test fun oldCardAnswerUsesTargetedRereadAndAReadFailureNeverTurnsACommitIntoAnotherAnswer() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(message(2, parts = listOf(approval()))))); session.start(); runCurrent()
            api.reads.send(Result.success(page(message(500))))
            api.reads.send(Result.success(page(message(2, parts = listOf(approval(decided = "allow"))))))
            assertTrue(session.approve("m2", "allow"))
            assertEquals(Read(3uL, null, null, false, 1), api.readRequests.last())
            assertEquals("Run deploy?\n✓ allow", NativeChiefPresentation.receipt(session.state.value.messages.first()))
            assertTrue(runCatching { session.approve("m2", "allow") }.isFailure)
            assertEquals(1, api.approvals.size)
        } finally { session.close() }
    }

    @Test fun committedQuestionWithFailedReadRemainsReservedUntilItsReceiptArrives() = runTest {
        val api = Api(); val q = question(other = true); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(message(1, parts = listOf(NativeChiefPart("question", null, null, q)))))); session.start(); runCurrent()
            val form = NativeChiefQuestionForm(q).editOther("  Canada  ")
            api.reads.send(Result.failure(java.io.IOException("post-commit read failed")))
            assertTrue(session.answer("m1", form)); assertTrue("m1" in session.state.value.committed)
            assertFalse(session.answer("m1", form)); assertEquals(listOf(emptyList<String>() to "Canada"), api.answers)
            val answered = q.copy(state = NativeChiefQuestion.State.ANSWERED, answer = NativeChiefQuestion.Answer(
                mapOf("item" to NativeChiefQuestion.Selection(other = "Canada")), null, null))
            api.waits.send(Result.success(page(message(1, parts = listOf(NativeChiefPart("question", null, null, answered)))))); runCurrent()
            assertFalse("m1" in session.state.value.committed)
            assertEquals("Pick a region\n✓ Canada", NativeChiefPresentation.receipt(session.state.value.messages.single()))
        } finally { session.close() }
    }

    @Test fun duplicateConcurrentApprovalIsSuppressedAndHiddenAlwaysOptionsAreRejected() = runTest {
        val api = Api(); val ack = CompletableDeferred<NativeChiefApproved>(); api.approvalBlock = { ack.await() }
        val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(message(1, parts = listOf(approval(options = listOf("allow", "always:allow"))))))); session.start(); runCurrent()
            assertTrue(runCatching { session.approve("m1", "always:allow") }.isFailure)
            val first = async { session.approve("m1", "allow") }; runCurrent()
            assertFalse(session.approve("m1", "allow")); assertEquals(1, api.approvals.size)
            ack.complete(NativeChiefApproved("chief-main", "request", "allow", true))
            api.reads.send(Result.failure(java.io.IOException("late read"))); runCurrent(); assertTrue(first.await())
        } finally { session.close() }
    }

    @Test fun pauseKeepsDraftsAndHistoryButRetirementClearsThemAndRejectsLateReads() = runTest {
        val api = Api(); var current = true; val session = NativeChiefTranscriptSession(this, api, "chief-main", { current })
        api.reads.send(Result.success(page(message(1)))); session.start(); runCurrent(); session.editDraft("private draft")
        session.pause(); runCurrent()
        assertEquals("private draft", session.state.value.draft); assertEquals(1, session.state.value.messages.size)
        session.start(); runCurrent(); current = false
        api.reads.send(Result.success(page(message(2)))); runCurrent()
        assertTrue(session.state.value.messages.isEmpty()); assertEquals("", session.state.value.draft)
        assertTrue(runCatching { session.editDraft("other account") }.isFailure)
        assertTrue(runCatching { session.stop() }.isFailure); assertEquals(0, api.stops)
    }

    @Test fun confirmedMarkReadIsMonotonicAndStopUpdatesWorkingState() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(message(9)).copy(working = true))); session.start(); runCurrent()
            session.markRead(); session.markRead(3uL); session.markRead(9uL); session.markRead(10uL)
            assertEquals(listOf(9uL, 10uL), api.marks)
            assertTrue(session.stop()); assertFalse(session.state.value.working); assertEquals(1, api.stops)
        } finally { session.close() }
    }

    @Test fun closingTheSessionCancelsAnInFlightMutationAndClearsItsContent() = runTest {
        val api = Api(); val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        api.sendBlock = { _, _ -> entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        session.editDraft("private action")
        val sending = async { runCatching { session.send() } }; runCurrent(); entered.await()
        session.close(); runCurrent(); cancelled.await()
        assertTrue(sending.await().isFailure)
        assertTrue(session.state.value.outgoing.isEmpty()); assertEquals("", session.state.value.draft)
        assertEquals(1, api.sends.size)
    }

    @Test fun aLatePendingCopyCannotResurrectASettledCardOrHideADifferentPendingCard() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(message(1, parts = listOf(approval(decided = "allow")))))); session.start(); runCurrent()
            val nextQuestion = question()
            api.waits.send(Result.success(page(message(1, parts = listOf(approval(), NativeChiefPart("question", null, null, nextQuestion)))))); runCurrent()
            val updated = session.state.value.messages.single()
            assertEquals("allow", updated.partPayloads.first().approval?.decided)
            assertEquals(nextQuestion, NativeChiefPresentation.pendingPart(updated)?.question)
        } finally { session.close() }
    }

    @Test fun questionFormKeepsSameOptionIdsSeparateAndMirrorsSingleAndMultiSelectOtherRules() {
        val base = question(other = true)
        val q = base.copy(items = base.items + base.items.single().copy(id = "multi", multiSelect = true))
        var form = NativeChiefQuestionForm(q)
        assertFalse(form.canSubmit); assertEquals(listOf("item\u001fus"), form.choices)
        form = form.toggle("multi", "us"); assertTrue(form.canSubmit)
        assertEquals(listOf("item\u001fus", "multi\u001fus"), form.choices)
        form = form.editOther("  "); assertEquals(setOf("us"), form.selections["item"])
        form = form.editOther("Canada"); assertTrue(form.selections["item"].isNullOrEmpty()); assertTrue(form.canSubmit)
        assertEquals(listOf("multi\u001fus"), form.choices)
        form = form.toggle("item", "eu"); assertEquals("", form.otherText)
        assertEquals(listOf("item\u001feu", "multi\u001fus"), form.choices)
        assertFalse(form.copy(question = q.copy(state = NativeChiefQuestion.State.CANCELLED)).canSubmit)
    }

    @Test fun uncertainAnswerCanBeExplicitlyRetriedButPollingNeverResendsIt() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(message(1, parts = listOf(approval()))))); session.start(); runCurrent()
            api.approvalBlock = { throw MobileRpcOutcomeUnknown() }
            assertFalse(session.approve("m1", "allow")); assertTrue("m1" in session.state.value.uncertain)
            api.waits.send(Result.success(page(message(1, parts = listOf(approval()))))); runCurrent()
            assertEquals(1, api.approvals.size)
            api.approvalBlock = { NativeChiefApproved("chief-main", "request", "allow", true) }
            api.reads.send(Result.failure(java.io.IOException("post-commit read unavailable")))
            assertTrue(session.approve("m1", "allow")); assertEquals(2, api.approvals.size)
            assertFalse(session.approve("m1", "allow")); assertEquals(2, api.approvals.size)
        } finally { session.close() }
    }

    @Test fun settledAndUnsupportedCardsDoNotHideALaterPendingQuestionAndWorkKeepsItsDetails() {
        val q = question(other = true)
        val message = message(1, text = "", parts = listOf(approval(decided = "deny"), approval(options = listOf("always:allow")),
            NativeChiefPart("future", null, null, null), NativeChiefPart("question", null, null, q)))
        assertEquals(q, NativeChiefPresentation.pendingPart(message)?.question)
        assertNull(NativeChiefPresentation.compactQuestion(q))
        assertNotNull(NativeChiefPresentation.compactQuestion(q.copy(items = q.items.map { it.copy(allowsOther = false) })))
        val work = NativeChiefPart("work", NativeChiefWork("s", "builder", "Build app", "running", "Compiling"), null, null)
        assertEquals("Build app · running · Compiling", NativeChiefPresentation.displayText(message(2, text = "Compiling", parts = listOf(work))))
        assertEquals("Answered in chat", NativeChiefPresentation.receipt(message(3, parts = listOf(
            NativeChiefPart("question", null, null, q.copy(state = NativeChiefQuestion.State.CANCELLED))))))
    }

    @Test fun acceptedLegacyEchoAllowsFiveSecondsOfClockSkewAndRetainsItsLocalIdentity() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "local-client" })
        try {
            api.reads.send(Result.success(page())); session.start(); runCurrent()
            api.sendBlock = { _, _ -> NativeChiefSent("chief-main", null, null, false) }
            session.editDraft("Legacy reply")
            api.reads.send(Result.success(page(message(1, "legacy", "Legacy reply", true).copy(at = instant.minusSeconds(5)))))
            session.send()
            assertTrue(session.state.value.outgoing.isEmpty())
            assertEquals("local-client", session.state.value.localClientIds["legacy"])
            assertEquals("c:local-client", NativeChiefTimeline.items(session.state.value).single().key)
        } finally { session.close() }
    }

    @Test fun legacyTextMatchingCannotStealAnOlderKnownRowOrAnEchoOutsideTheSkewAllowance() = runTest {
        val api = Api(); val old = message(1, "old", "Same text", true)
        val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "local-client" })
        try {
            api.reads.send(Result.success(page(old))); session.start(); runCurrent()
            api.sendBlock = { _, _ -> NativeChiefSent("chief-main", null, null, false) }
            session.editDraft("Same text")
            api.reads.send(Result.success(page(old, message(2, "too-old", "Same text", true).copy(at = instant.minusSeconds(6)))))
            session.send()
            assertEquals(1, session.state.value.outgoing.size); assertTrue(session.state.value.localClientIds.isEmpty())
        } finally { session.close() }
    }

    @Test fun aCanonicalClientIdInTheAckCanCorrelateAnEchoThatArrivedBeforeTheAck() = runTest {
        val api = Api(); val ack = CompletableDeferred<NativeChiefSent>(); api.sendBlock = { _, _ -> ack.await() }
        val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "local-client" })
        try {
            api.reads.send(Result.success(page())); session.start(); runCurrent(); session.editDraft("Canonical reply")
            val sending = async { session.send() }; runCurrent()
            api.waits.send(Result.success(page(message(1, "canonical-server", "Canonical reply", true, "canonical-client")))); runCurrent()
            assertEquals(1, session.state.value.outgoing.size)
            ack.complete(NativeChiefSent("chief-main", null, "canonical-client", true))
            api.reads.send(Result.failure(java.io.IOException("follow-up read unavailable"))); runCurrent(); sending.await()
            assertTrue(session.state.value.outgoing.isEmpty()); assertEquals("local-client", session.state.value.localClientIds["canonical-server"])
        } finally { session.close() }
    }

    @Test fun exactEchoWinsOverAnIdenticalLegacyRowElsewhereInTheSamePage() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "local-client" })
        try {
            api.reads.send(Result.success(page())); session.start(); runCurrent()
            api.sendBlock = { _, id -> NativeChiefSent("chief-main", null, id, true) }
            session.editDraft("Same text")
            api.reads.send(Result.success(page(message(1, "unrelated", "Same text", true), message(2, "exact", "Same text", true, "local-client"))))
            session.send()
            assertEquals(mapOf("exact" to "local-client"), session.state.value.localClientIds)
            assertTrue(session.state.value.outgoing.isEmpty())
        } finally { session.close() }
    }

    @Test fun aCorrelatedLegacyRowCannotAbsorbASecondIdenticalSendOnALaterRefresh() = runTest {
        val api = Api(); var nextId = 0
        val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "local-${++nextId}" })
        try {
            api.reads.send(Result.success(page())); session.start(); runCurrent()
            api.sendBlock = { _, _ -> NativeChiefSent("chief-main", null, null, false) }
            val first = message(1, "first", "Same text", true)
            session.editDraft("Same text"); api.reads.send(Result.success(page(first))); session.send()
            session.editDraft("Same text"); api.reads.send(Result.success(page(first))); session.send()
            assertEquals(listOf("local-2"), session.state.value.outgoing.map { it.clientMessageId })
            assertEquals(mapOf("first" to "local-1"), session.state.value.localClientIds)
        } finally { session.close() }
    }

    @Test fun failureIsAnchoredAfterTheNewestMessageObservedAtTheMomentItFailed() = runTest {
        val api = Api(); val failed = CompletableDeferred<NativeChiefSent>(); api.sendBlock = { _, _ -> failed.await() }
        val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "local-client" })
        try {
            api.reads.send(Result.success(page(message(1)))); session.start(); runCurrent(); session.editDraft("Will fail")
            val sending = async { session.send() }; runCurrent()
            api.waits.send(Result.success(page(message(2)))); runCurrent()
            failed.completeExceptionally(MobileRpcOutcomeUnknown()); runCurrent(); sending.await()
            api.waits.send(Result.success(page(message(3)))); runCurrent()
            assertEquals(2uL, session.state.value.outgoing.single().failedAnchorSeq)
            assertEquals(listOf("s:m1", "s:m2", "c:local-client", "s:m3"), NativeChiefTimeline.items(session.state.value).map { it.key })
        } finally { session.close() }
    }

    @Test fun explicitRetryKeepsTheClientIdMovesToTheTailAndReservesBeforeSuspending() = runTest {
        val api = Api(); var clock = instant
        val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { clock }, { "retry-client" })
        try {
            api.reads.send(Result.success(page(message(1)))); session.start(); runCurrent()
            api.sendBlock = { _, _ -> throw MobileRpcOutcomeUnknown() }
            session.editDraft("Same request"); session.send()
            api.waits.send(Result.success(page(message(2)))); runCurrent()
            assertEquals(listOf("s:m1", "c:retry-client", "s:m2"), NativeChiefTimeline.items(session.state.value).map { it.key })
            clock = instant.plusSeconds(90); session.editDraft("Keep this new draft")
            val ack = CompletableDeferred<NativeChiefSent>(); api.sendBlock = { _, _ -> ack.await() }
            val retry = async { session.retry("retry-client") }; runCurrent()
            assertFalse(session.retry("retry-client")); assertFalse(session.discardFailed("retry-client"))
            val pending = session.state.value.outgoing.single()
            assertEquals(clock, pending.at); assertNull(pending.failedAnchorSeq); assertNull(pending.error)
            assertEquals(2uL, pending.sentAfterSeq)
            assertEquals(listOf("s:m1", "s:m2", "c:retry-client"), NativeChiefTimeline.items(session.state.value).map { it.key })
            // The retry's exact echo may arrive before its acknowledgment.
            val echo = message(3, "echo", "Same request", true, "retry-client")
            api.waits.send(Result.success(page(echo))); runCurrent()
            api.reads.send(Result.success(page(echo)))
            ack.complete(NativeChiefSent("chief-main", "echo", "retry-client", false)); runCurrent()
            assertTrue(retry.await()); assertTrue(session.state.value.outgoing.isEmpty())
            assertEquals("retry-client", session.state.value.localClientIds["echo"])
            assertEquals(listOf("Same request" to "retry-client", "Same request" to "retry-client"), api.sends)
            assertEquals("Keep this new draft", session.state.value.draft)
            assertFalse(session.retry("retry-client")); assertFalse(session.discardFailed("retry-client"))
        } finally { session.close() }
    }

    @Test fun rejectedRetryReanchorsAtItsOwnFailureAndPollingDoesNotResendIt() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "retry-client" })
        try {
            api.reads.send(Result.success(page(message(1)))); session.start(); runCurrent()
            api.sendBlock = { _, _ -> throw MobileRpcException("unavailable", "Not running", fromHostResponse = true) }
            session.editDraft("Try later"); session.send()
            assertEquals(NativeChiefDelivery.REJECTED, session.state.value.outgoing.single().delivery)
            val failed = CompletableDeferred<NativeChiefSent>(); api.sendBlock = { _, _ -> failed.await() }
            val retry = async { session.retry("retry-client") }; runCurrent()
            api.waits.send(Result.success(page(message(2)))); runCurrent()
            failed.completeExceptionally(MobileRpcOutcomeUnknown()); runCurrent(); assertTrue(retry.await())
            assertEquals(2uL, session.state.value.outgoing.single().failedAnchorSeq)
            assertEquals(NativeChiefDelivery.UNCONFIRMED, session.state.value.outgoing.single().delivery)
            api.waits.send(Result.success(page(message(3)))); runCurrent()
            session.pause(); session.start(); api.reads.send(Result.success(page(message(3)))); runCurrent()
            assertEquals(2, api.sends.size)
        } finally { session.close() }
    }

    @Test fun discardIsLocalAndKnownLateEchoStaysDeletedWithoutHidingAnotherSendWithTheSameText() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "deleted-client" })
        try {
            api.reads.send(Result.success(page(message(1)))); session.start(); runCurrent()
            api.sendBlock = { _, _ -> throw MobileRpcOutcomeUnknown() }
            session.editDraft("Repeated text"); session.send(); session.editDraft("Unrelated draft")
            assertTrue(session.discardFailed("deleted-client")); assertFalse(session.discardFailed("deleted-client"))
            assertFalse(session.retry("deleted-client")); assertTrue(session.state.value.outgoing.isEmpty())
            val late = message(2, "late", "Repeated text", true, "deleted-client")
            val other = message(3, "another", "Repeated text", true, "other-client")
            api.waits.send(Result.success(page(late, other))); runCurrent()
            assertEquals(listOf("m1", "another"), session.state.value.messages.map { it.id })
            session.refresh(); api.reads.send(Result.success(page(late, other))); runCurrent()
            assertEquals(listOf("m1", "another"), session.state.value.messages.map { it.id })
            assertEquals(1, api.sends.size); assertEquals("Unrelated draft", session.state.value.draft)
        } finally { session.close() }
    }

    @Test fun retirementClearsFailedTextAndRejectsRetryOrDeletionBeforeAnyHostCall() = runTest {
        val api = Api(); var current = true
        val session = NativeChiefTranscriptSession(this, api, "chief-main", { current }, { instant }, { "old-client" })
        api.sendBlock = { _, _ -> throw MobileRpcOutcomeUnknown() }
        session.editDraft("Private failed send"); session.send(); current = false
        assertTrue(runCatching { session.retry("old-client") }.isFailure)
        assertTrue(runCatching { session.discardFailed("old-client") }.isFailure)
        assertTrue(session.state.value.outgoing.isEmpty()); assertEquals(1, api.sends.size)
        session.close()
    }

    @Test fun sendsWaitForThePreviousAcknowledgmentAndItsReadBeforeTheNextHostWrite() = runTest {
        val api = Api(); var nextId = 0
        val firstAck = CompletableDeferred<NativeChiefSent>(); val secondAck = CompletableDeferred<NativeChiefSent>()
        api.sendBlock = { _, id -> if (id == "client-1") firstAck.await() else secondAck.await() }
        val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "client-${++nextId}" })
        try {
            session.editDraft("First"); val first = async { session.send() }; runCurrent()
            session.editDraft("Second"); val second = async { session.send() }; runCurrent()
            assertEquals(listOf("First" to "client-1"), api.sends)
            assertEquals(listOf("client-1", "client-2"), session.state.value.outgoing.map { it.clientMessageId })
            firstAck.complete(NativeChiefSent("chief-main", "first", "client-1", false)); runCurrent()
            assertEquals(1, api.sends.size); assertFalse(first.isCompleted)
            api.reads.send(Result.success(page())); runCurrent()
            assertEquals(listOf("First" to "client-1", "Second" to "client-2"), api.sends)
            secondAck.complete(NativeChiefSent("chief-main", "second", "client-2", false))
            api.reads.send(Result.success(page())); runCurrent()
            assertEquals("client-1", first.await()); assertEquals("client-2", second.await())
            assertTrue(session.state.value.outgoing.all { it.delivery == NativeChiefDelivery.SENT })
        } finally { session.close() }
    }

    @Test fun aFailedSendReleasesTheQueueWithoutRetryingOrDroppingTheFollowingMessage() = runTest {
        val api = Api(); var nextId = 0; val firstAck = CompletableDeferred<NativeChiefSent>()
        api.sendBlock = { _, id -> if (id == "client-1") firstAck.await() else NativeChiefSent("chief-main", "second", id, false) }
        val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "client-${++nextId}" })
        try {
            session.editDraft("First"); val first = async { session.send() }; runCurrent()
            session.editDraft("Second"); val second = async { session.send() }; runCurrent()
            assertEquals(1, api.sends.size)
            api.reads.send(Result.success(page())); firstAck.completeExceptionally(MobileRpcOutcomeUnknown()); runCurrent()
            first.await(); second.await()
            assertEquals(listOf("First" to "client-1", "Second" to "client-2"), api.sends)
            assertEquals(listOf(NativeChiefDelivery.UNCONFIRMED, NativeChiefDelivery.SENT), session.state.value.outgoing.map { it.delivery })
        } finally { session.close() }
    }

    @Test fun leavingTheViewCancelsItsWaitersButRetainedSendsContinueUntilTheOwnerRetires() = runTest {
        val api = Api(); var nextId = 0
        val firstAck = CompletableDeferred<NativeChiefSent>(); val secondEntered = CompletableDeferred<Unit>()
        val secondCancelled = CompletableDeferred<Unit>()
        api.sendBlock = { _, id -> if (id == "client-1") firstAck.await() else {
            secondEntered.complete(Unit)
            try { awaitCancellation() } finally { secondCancelled.complete(Unit) }
        } }
        val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "client-${++nextId}" })
        val view = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        try {
            session.editDraft("First"); view.launch { session.send() }; runCurrent()
            session.editDraft("Second"); view.launch { session.send() }; runCurrent()
            view.cancel(); session.pause(); runCurrent()
            assertEquals(1, api.sends.size)
            assertTrue(session.state.value.outgoing.all { it.delivery == NativeChiefDelivery.SENDING })
            api.reads.send(Result.success(page())); firstAck.complete(NativeChiefSent("chief-main", "first", "client-1", false)); runCurrent()
            secondEntered.await(); assertEquals(2, api.sends.size)
            session.close(); runCurrent(); secondCancelled.await()
            assertTrue(session.state.value.outgoing.isEmpty())
        } finally { view.cancel(); session.close() }
    }

    @Test fun aQueuedRetryReceivingItsExactOriginalEchoSkipsTheRedundantHostWrite() = runTest {
        val api = Api(); var nextId = 0
        val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "client-${++nextId}" })
        try {
            api.reads.send(Result.success(page())); session.start(); runCurrent()
            api.sendBlock = { _, _ -> throw MobileRpcOutcomeUnknown() }
            session.editDraft("First"); session.send()
            val secondAck = CompletableDeferred<NativeChiefSent>(); api.sendBlock = { _, _ -> secondAck.await() }
            session.editDraft("Second"); val second = async { session.send() }; runCurrent()
            val retry = async { session.retry("client-1") }; runCurrent()
            assertEquals(listOf("First" to "client-1", "Second" to "client-2"), api.sends)
            val echo = message(1, "first-echo", "First", true, "client-1")
            api.waits.send(Result.success(page(echo))); runCurrent()
            assertEquals("client-1", session.state.value.localClientIds["first-echo"])
            api.reads.send(Result.success(page(echo)))
            secondAck.complete(NativeChiefSent("chief-main", "second", "client-2", false)); runCurrent()
            second.await(); assertTrue(retry.await()); assertEquals(2, api.sends.size)
            assertEquals(listOf("client-2"), session.state.value.outgoing.map { it.clientMessageId })
        } finally { session.close() }
    }

    @Test fun foregroundViewingReadsTheWholeLoadedConversationAndKeepsTheOriginalBacklogTarget() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(*(51..100).map { message(it) }.toTypedArray(), more = true)
                .copy(lastReadSeq = 20uL, unreadCount = 80)))
            session.setViewing(true); session.start(); runCurrent()
            assertEquals(50, api.readRequests.first().limit)
            assertEquals(20uL, session.state.value.catchUpMarker); assertEquals(80L, session.state.value.catchUpCount)
            assertEquals(100uL, session.state.value.lastReadSeq); assertEquals(0L, session.state.value.unread)
            assertEquals(listOf(100uL), api.marks); assertNull(session.catchUpTarget())
            api.waits.send(Result.success(page(message(101)).copy(lastReadSeq = 20uL, unreadCount = 81))); runCurrent()
            assertEquals(listOf(100uL, 101uL), api.marks)
            assertEquals(101uL, session.state.value.lastReadSeq); assertEquals(20uL, session.state.value.catchUpMarker)
            assertEquals(200, api.readRequests.last().limit)
            session.endVisit(); assertNull(session.state.value.catchUpMarker)
        } finally { session.close() }
    }

    @Test fun catchingUpLoadsThroughTheMarkerWithContextAndSkipsOwnMessages() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(*(51..100).map { message(it) }.toTypedArray(), more = true)
                .copy(lastReadSeq = 10uL, unreadCount = 90)))
            session.setViewing(true); session.start(); runCurrent()
            val older = (1..50).map { message(it, human = it == 11) }
            api.reads.send(Result.success(page(*older.toTypedArray(), more = false)))
            assertEquals("m12", session.loadCatchUpTarget())
            assertEquals(Read(51uL, null, null, false, 50), api.readRequests.last())
            assertEquals(listOf(100uL), api.marks)
            session.observeVisible(setOf("m50"), false); assertEquals(10uL, session.state.value.catchUpMarker)
            session.observeVisible(setOf("m12"), false); assertNull(session.state.value.catchUpMarker)
        } finally { session.close() }
    }

    @Test fun resumingAfterBackgroundCapturesTheFreshBacklogRatherThanTheStaleBaseline() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(message(1), message(2), message(3)).copy(lastReadSeq = 3uL, unreadCount = 0)))
            session.setViewing(true); session.start(); runCurrent(); assertTrue(api.marks.isEmpty())
            session.setViewing(false); session.pause()
            session.setViewing(true); session.start()
            api.reads.send(Result.success(page(*(1..5).map { message(it) }.toTypedArray()).copy(lastReadSeq = 3uL, unreadCount = 2))); runCurrent()
            assertEquals(3uL, session.state.value.catchUpMarker); assertEquals(2L, session.state.value.catchUpCount)
            assertEquals(listOf(5uL), api.marks)
            session.observeVisible(setOf("m5"), false); assertEquals(3uL, session.state.value.catchUpMarker)
            session.observeVisible(emptySet(), true); assertNull(session.state.value.catchUpMarker)
        } finally { session.close() }
    }

    @Test fun ordinaryHistoryUsesFiftyWhileGapRecoveryRetainsTwoHundred() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(message(100), more = true))); session.start(); runCurrent()
            api.reads.send(Result.success(page(message(50), more = true))); session.loadOlder()
            assertEquals(50, api.readRequests.first().limit)
            assertEquals(Read(100uL, null, null, false, 50), api.readRequests.last())
            api.waits.send(Result.success(page(message(103), more = true)))
            api.reads.send(Result.success(page(message(101), message(102), more = false))); runCurrent()
            assertTrue(api.readRequests.any { it.before == 103uL && it.limit == 200 })
        } finally { session.close() }
    }

    @Test fun retiringTheOwnerDuringCatchUpCannotPublishItsLateHistoryOrTarget() = runTest {
        val api = Api(); var current = true
        val session = NativeChiefTranscriptSession(this, api, "chief-main", { current })
        try {
            api.reads.send(Result.success(page(message(100), more = true).copy(lastReadSeq = 10uL, unreadCount = 90)))
            session.setViewing(true); session.start(); runCurrent()
            val history = CompletableDeferred<NativeChiefRead>(); api.readBlock = { history.await() }
            val jump = async { runCatching { session.loadCatchUpTarget() } }; runCurrent()
            assertTrue(session.state.value.loadingCatchUp)
            current = false; history.complete(page(message(11), more = false)); runCurrent()
            assertTrue(jump.await().isFailure)
            assertTrue(session.state.value.messages.isEmpty()); assertNull(session.state.value.catchUpMarker)
            assertFalse(session.state.value.loadingCatchUp)
        } finally { session.close() }
    }

    @Test fun anAuthoritativeMarkUnreadAfterReadAcknowledgmentCanBeReadAgainDuringTheVisit() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            val rows = page(message(1), message(2), message(3))
            api.reads.send(Result.success(rows.copy(lastReadSeq = 0uL, unreadCount = 3)))
            session.setViewing(true); session.start(); runCurrent()
            assertEquals(listOf(3uL), api.marks)
            api.waits.send(Result.success(rows.copy(lastReadSeq = 3uL, unreadCount = 0))); runCurrent()
            // Another client marks this conversation unread after the server
            // acknowledged our first read. Viewing it must issue a new receipt.
            api.waits.send(Result.success(rows.copy(lastReadSeq = 0uL, unreadCount = 3))); runCurrent()
            assertEquals(listOf(3uL, 3uL), api.marks)
        } finally { session.close() }
    }

    @Test fun liveAndActionSnapshotsUpdateLoadedRowsWithoutPullingUnknownOlderHistoryAcrossAGap() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "local" })
        try {
            api.reads.send(Result.success(page(message(100), more = true))); session.start(); runCurrent()
            api.waits.send(Result.success(page(message(50), message(100).copy(text = "Updated"), message(101)))); runCurrent()
            assertEquals(listOf("m100", "m101"), session.state.value.messages.map { it.id })
            assertEquals("Updated", session.state.value.messages.first().text)
            session.editDraft("New send")
            api.reads.send(Result.success(page(message(49), message(100), message(102, "echo", "New send", true, "local"))))
            session.send()
            assertEquals(listOf("m100", "m101", "echo"), session.state.value.messages.map { it.id })
            assertEquals("Updated", session.state.value.messages.first().text)
            assertTrue(session.state.value.outgoing.isEmpty())
            api.reads.send(Result.success(page(message(99), more = true))); session.loadOlder()
            assertEquals("m99", session.state.value.messages.first().id)
        } finally { session.close() }
    }

    @Test fun restingWindowBoundsGrowthAndDroppedHistoryRemainsPageable() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(*(1..50).map { message(it) }.toTypedArray(), more = false).copy(lastReadSeq = 50uL, unreadCount = 0)))
            session.setViewing(true); session.start(); runCurrent()
            for (first in 51..1501 step 200) {
                api.waits.send(Result.success(page(*(first..minOf(first + 199, 1501)).map { message(it) }.toTypedArray()))); runCurrent()
            }
            assertEquals(1501, session.state.value.messages.size)
            assertTrue(session.trimHistoryIfResting()); assertEquals(1000, session.state.value.messages.size)
            assertEquals(502uL, session.state.value.messages.first().seq); assertTrue(session.state.value.hasMore)
            api.reads.send(Result.success(page(*(452..501).map { message(it) }.toTypedArray(), more = true)))
            session.loadOlder(); assertEquals(1050, session.state.value.messages.size)
            assertEquals(452uL, session.state.value.messages.first().seq)
            assertFalse(session.trimHistoryIfResting())
        } finally { session.close() }
    }

    @Test fun aDecidingThenUncertainCardRemainsUntilItsReceiptAllowsTheWindowToShrink() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true })
        try {
            api.reads.send(Result.success(page(*(1..50).map { if (it == 10) message(it, parts = listOf(approval())) else message(it) }.toTypedArray())
                .copy(lastReadSeq = 50uL, unreadCount = 0)))
            session.setViewing(true); session.start(); runCurrent()
            for (first in 51..1501 step 200) {
                api.waits.send(Result.success(page(*(first..minOf(first + 199, 1501)).map { message(it) }.toTypedArray()))); runCurrent()
            }
            val answer = CompletableDeferred<NativeChiefApproved>(); api.approvalBlock = { answer.await() }
            val deciding = async { session.approve("m10", "allow") }; runCurrent()
            assertTrue(session.trimHistoryIfResting()); assertEquals("m10", session.state.value.messages.first().id)
            answer.completeExceptionally(MobileRpcOutcomeUnknown()); runCurrent(); assertFalse(deciding.await())
            api.waits.send(Result.success(page(*(1502..1512).map { message(it) }.toTypedArray()))); runCurrent()
            assertFalse(session.trimHistoryIfResting()); assertTrue("m10" in session.state.value.uncertain)
            api.waits.send(Result.success(page(message(10, parts = listOf(approval(decided = "allow")))))); runCurrent()
            assertTrue(session.trimHistoryIfResting()); assertEquals(1000, session.state.value.messages.size)
        } finally { session.close() }
    }

    @Test fun aFailedLocalSendKeepsItsPositionUntilExplicitDeletionReleasesTheWindow() = runTest {
        val api = Api(); val session = NativeChiefTranscriptSession(this, api, "chief-main", { true }, { instant }, { "failed" })
        try {
            api.reads.send(Result.success(page(*(1..50).map { message(it) }.toTypedArray()).copy(lastReadSeq = 50uL, unreadCount = 0)))
            session.setViewing(true); session.start(); runCurrent()
            api.sendBlock = { _, _ -> throw MobileRpcOutcomeUnknown() }; session.editDraft("Keep this failed text"); session.send()
            for (first in 51..1501 step 200) {
                api.waits.send(Result.success(page(*(first..minOf(first + 199, 1501)).map { message(it) }.toTypedArray()))); runCurrent()
            }
            assertTrue(session.trimHistoryIfResting()); assertEquals(51uL, session.state.value.messages.first().seq)
            assertEquals("Keep this failed text", session.state.value.outgoing.single().text)
            api.waits.send(Result.success(page(*(1502..1601).map { message(it) }.toTypedArray()))); runCurrent()
            assertFalse(session.trimHistoryIfResting())
            assertTrue(session.discardFailed("failed")); assertTrue(session.trimHistoryIfResting())
            assertEquals(1000, session.state.value.messages.size); assertEquals(602uL, session.state.value.messages.first().seq)
        } finally { session.close() }
    }
}
