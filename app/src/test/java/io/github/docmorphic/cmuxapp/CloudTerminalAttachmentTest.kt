package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class CloudTerminalAttachmentTest {
    private class Link : CloudTerminalLink {
        val calls = CopyOnWriteArrayList<String>()
        val streams = ConcurrentHashMap<Long, Channel<CloudTerminalOutput>>()
        val next = AtomicLong()
        @Volatile var token = 0L
        var attachWork: (String) -> Unit = {}
        var acceptsInput = true
        override fun attach(terminal: String): Long {
            calls += "attach:$terminal"; attachWork(terminal)
            val id = next.incrementAndGet(); streams[id] = Channel(Channel.UNLIMITED); token = id; return id
        }
        override fun detach(attachment: Long) { calls += "release:$attachment"; if (token == attachment) token = 0 }
        override fun send(attachment: Long, bytes: ByteArray): Boolean {
            check(token == attachment); calls += "input:${bytes.toString(Charsets.UTF_8)}"; return acceptsInput
        }
        override fun resize(attachment: Long, columns: Int, rows: Int): Long {
            check(token == attachment); calls += "resize:$columns:$rows"; return 1
        }
        override suspend fun output(attachment: Long) = streams.getValue(attachment).receive()
        fun emit(kind: Int, text: String = "", cols: Int = 80, rows: Int = 24) {
            check(streams.getValue(token).trySend(CloudTerminalOutput(kind, text.toByteArray(), cols, rows)).isSuccess)
        }
    }
    @Test fun failureClassifiesSessionAndTransportWithoutDisplayingNativeDiagnostics() = runTest {
        for (failure in listOf(java.io.IOException("private-native-diagnostic"), CloudNotSignedIn())) {
            val owner = CloudTerminalAttachment(this, { true }, { throw failure }, { _, _ -> }, StandardTestDispatcher(testScheduler))
            try {
                owner.select("term_a"); owner.setAvailable(true); runCurrent()
                val classified = checkNotNull(owner.state.value.failure)
                assertEquals(CloudAttachmentPhase.FAILED, owner.state.value.phase)
                assertFalse(classified.userReason.contains("private-native-diagnostic"))
                assertEquals(failure is CloudNotSignedIn, classified.signedOut)
                if (failure is CloudNotSignedIn) assertTrue(classified.userReason.contains("Sign in again"))
                else assertEquals(CloudFailureKind.LINK, classified.kind)
                owner.setAvailable(false)
                assertNull(owner.state.value.failure)
            } finally { owner.close(); runCurrent() }
        }
    }
    @Test fun composerReceiptWaitsForNativeAdmissionAndRejectsFailedTransport() = runTest {
        val link = Link(); val ready = CompletableDeferred<CloudTerminalLink>()
        val owner = CloudTerminalAttachment(this, { true }, { ready.await() }, { _, _ -> }, StandardTestDispatcher(testScheduler))
        try {
            owner.select("term_a"); owner.setAvailable(true)
            val sending = async { owner.sendAndAwait("draft\r".toByteArray()) }; runCurrent()
            assertFalse(sending.isCompleted); assertEquals(6, owner.state.value.pendingBytes)
            ready.complete(link); runCurrent(); assertTrue(sending.await())
            link.acceptsInput = false
            val refused = async { owner.sendAndAwait("retain\r".toByteArray()) }; runCurrent()
            assertFalse(refused.await()); assertEquals(CloudAttachmentPhase.FAILED, owner.state.value.phase)
        } finally { owner.close(); runCurrent() }
    }
    @Test fun freshInputRetriesFailedAttachAndOnlyNewInputIsDeliveredAfterTheSavedGrid() = runTest {
        val link = Link()
        var attempts = 0
        link.attachWork = { if (++attempts == 1) throw java.io.IOException("attachment unavailable") }
        val owner = CloudTerminalAttachment(this, { true }, { link }, { _, _ -> }, StandardTestDispatcher(testScheduler))
        try {
            owner.select("term_a"); owner.resize(93, 31)
            val old = async { owner.sendAndAwait("old-unconfirmed".toByteArray()) }
            runCurrent(); owner.setAvailable(true); runCurrent()
            assertFalse(old.await()); assertEquals(CloudAttachmentPhase.FAILED, owner.state.value.phase)
            assertTrue(owner.state.value.retryOnInput); assertEquals(0, owner.state.value.pendingBytes)
            advanceTimeBy(60_000); runCurrent(); assertEquals(1, attempts)
            val fresh = async { owner.sendAndAwait("fresh-command".toByteArray()) }
            runCurrent(); assertTrue(fresh.await())
            assertEquals(listOf("attach:term_a", "attach:term_a", "resize:93:31", "input:fresh-command"), link.calls.toList())
            assertEquals(CloudAttachmentPhase.READY, owner.state.value.phase)
            assertFalse(owner.state.value.retryOnInput); assertNull(owner.state.value.failure)
        } finally { owner.close(); runCurrent() }
    }
    @Test fun failedInitialConnectionRetriesOnceForFreshTypingButNotEmptyOrOversizedInput() = runTest {
        val link = Link(); val retry = CompletableDeferred<CloudTerminalLink>(); var attempts = 0
        val owner = CloudTerminalAttachment(this, { true }, {
            if (++attempts == 1) throw java.io.IOException("dial unavailable")
            retry.await()
        }, { _, _ -> }, StandardTestDispatcher(testScheduler))
        try {
            owner.select("term_a"); owner.setAvailable(true); runCurrent()
            assertTrue(owner.state.value.retryOnInput)
            assertTrue(owner.send(byteArrayOf())); assertFalse(owner.send(ByteArray(CloudTerminalAttachment.INPUT_LIMIT + 1)))
            runCurrent(); assertEquals(1, attempts)
            assertTrue(owner.send("a".toByteArray())); assertTrue(owner.send("b".toByteArray()))
            runCurrent(); assertEquals(2, attempts)
            assertEquals(CloudAttachmentPhase.CONNECTING, owner.state.value.phase)
            assertEquals(2, owner.state.value.pendingBytes)
            retry.complete(link); runCurrent()
            assertEquals(listOf("attach:term_a", "input:a", "input:b"), link.calls.toList())
        } finally { owner.close(); runCurrent() }
    }
    @Test fun retiredAccountCannotRestartFailedAttachmentAndNativeRuntimeFailureRequiresExplicitRecovery() = runTest {
        var current = true; var attempts = 0
        val owner = CloudTerminalAttachment(this, { current }, {
            attempts++; throw java.io.IOException("fixture")
        }, { _, _ -> }, StandardTestDispatcher(testScheduler))
        try {
            owner.select("term_a"); owner.setAvailable(true); runCurrent()
            current = false
            assertFalse(owner.send("stale".toByteArray())); runCurrent(); assertEquals(1, attempts)
        } finally { owner.close(); runCurrent() }
        val missing = CloudTerminalAttachment(this, { true }, { throw UnsatisfiedLinkError("fixture") },
            { _, _ -> }, StandardTestDispatcher(testScheduler))
        try {
            missing.select("term_a"); missing.setAvailable(true); runCurrent()
            assertFalse(missing.state.value.retryOnInput); assertFalse(missing.send("no-native-runtime".toByteArray()))
        } finally { missing.close(); runCurrent() }
    }
    @Test fun timedOutOrCancelledComposerInputIsRemovedBeforeAConnectionCanSendIt() = runTest {
        val link = Link()
        val owner = CloudTerminalAttachment(this, { true }, { link }, { _, _ -> }, StandardTestDispatcher(testScheduler))
        try {
            owner.select("term_a")
            val timed = async { owner.sendAndAwait("timeout".toByteArray()) }; runCurrent()
            advanceTimeBy(30_001); runCurrent(); assertFalse(timed.await()); assertEquals(0, owner.state.value.pendingBytes)
            val canceled = async { owner.sendAndAwait("canceled".toByteArray()) }; runCurrent()
            canceled.cancelAndJoin(); assertEquals(0, owner.state.value.pendingBytes)
            owner.setAvailable(true); runCurrent()
            assertFalse(link.calls.any { it.startsWith("input:") })
        } finally { owner.close(); runCurrent() }
    }
    @Test fun replacingSelectionCompletesAllInlineReceiptsWithoutReentrantQueueMutation() = runTest {
        val owner = CloudTerminalAttachment(this, { true }, { Link() }, { _, _ -> }, StandardTestDispatcher(testScheduler))
        try {
            owner.select("old")
            val first = async(UnconfinedTestDispatcher(testScheduler)) { owner.sendAndAwait("first".toByteArray()) }
            val second = async(UnconfinedTestDispatcher(testScheduler)) { owner.sendAndAwait("second".toByteArray()) }
            owner.select("new")
            assertFalse(first.await()); assertFalse(second.await()); assertEquals(0, owner.state.value.pendingBytes)
        } finally { owner.close(); runCurrent() }
    }

    @Test fun holdsEarlyInputAndViewportThenDeliversOutputInOrder() = runTest {
        val link = Link(); val ready = CompletableDeferred<CloudTerminalLink>(); val output = mutableListOf<String>()
        val owner = CloudTerminalAttachment(this, { true }, { ready.await() }, { terminal, event ->
            output += "$terminal:${event.bytes.toString(Charsets.UTF_8)}"
        }, StandardTestDispatcher(testScheduler))
        try {
            owner.select("term_a"); owner.setAvailable(true); runCurrent()
            val callerBytes = "first".toByteArray(); assertTrue(owner.send(callerBytes)); callerBytes.fill(0)
            assertTrue(owner.send("second".toByteArray())); owner.resize(90, 30)
            assertEquals(11, owner.state.value.pendingBytes); ready.complete(link); runCurrent()
            assertEquals(listOf("attach:term_a", "resize:90:30", "input:first", "input:second"), link.calls.toList())
            link.emit(1, "snapshot"); link.emit(2, "one"); link.emit(2, "two"); runCurrent()
            assertEquals(listOf("term_a:snapshot", "term_a:one", "term_a:two"), output)
            assertEquals(CloudAttachmentPhase.READY, owner.state.value.phase); assertEquals(0, owner.state.value.pendingBytes)
        } finally { owner.close(); runCurrent() }
        assertEquals(0L, link.token)
    }
    @Test fun switchingDiscardsOldPendingInputAndOldOutput() = runTest {
        val link = Link(); val ready = CompletableDeferred<CloudTerminalLink>(); val output = mutableListOf<String>()
        val owner = CloudTerminalAttachment(this, { true }, { ready.await() }, { terminal, _ -> output += terminal }, StandardTestDispatcher(testScheduler))
        try {
            owner.select("old"); owner.setAvailable(true); runCurrent(); owner.send("old-input".toByteArray())
            owner.select("new"); owner.send("new-input".toByteArray()); ready.complete(link); runCurrent()
            assertEquals(listOf("attach:new", "input:new-input"), link.calls.toList())
            link.emit(2, "stale"); owner.select("third"); runCurrent()
            assertTrue(output.isEmpty()); assertEquals(CloudAttachmentPhase.READY, owner.state.value.phase)
            link.emit(1); runCurrent(); assertEquals(listOf("third"), output)
        } finally { owner.close(); runCurrent() }
    }
    @Test fun overflowRefusesThePasteAndRejectedInputIsNeverAutomaticallyReplayed() = runTest {
        val link = Link().apply { acceptsInput = false }
        val owner = CloudTerminalAttachment(this, { true }, { link }, { _, _ -> }, StandardTestDispatcher(testScheduler))
        try {
            owner.select("term_a")
            assertTrue(owner.send(ByteArray(CloudTerminalAttachment.INPUT_LIMIT) { 65 }))
            assertFalse(owner.send(byteArrayOf(1))); assertEquals(CloudTerminalAttachment.INPUT_LIMIT, owner.state.value.pendingBytes)
            owner.setAvailable(true); runCurrent()
            assertEquals(CloudAttachmentPhase.FAILED, owner.state.value.phase); assertEquals(0, owner.state.value.pendingBytes)
            assertFalse(owner.send("ignored".toByteArray()))
            link.acceptsInput = true; owner.replay(); runCurrent()
            assertEquals(1, link.calls.count { it.startsWith("input:") }); assertEquals(CloudAttachmentPhase.READY, owner.state.value.phase)
        } finally { owner.close(); runCurrent() }
    }
    @Test fun reconnectKeepsSelectionAndResizeButDoesNotReplayAlreadyAcceptedInput() = runTest {
        val link = Link(); val owner = CloudTerminalAttachment(this, { true }, { link }, { _, _ -> }, StandardTestDispatcher(testScheduler))
        try {
            owner.select("term_a"); owner.resize(95, 35); owner.setAvailable(true); runCurrent()
            assertTrue(owner.send("once".toByteArray())); runCurrent()
            owner.setAvailable(false); runCurrent(); assertEquals(0L, link.token)
            assertTrue(owner.send("early".toByteArray())); owner.setAvailable(true); runCurrent()
            assertEquals(2, link.calls.count { it == "attach:term_a" }); assertEquals(2, link.calls.count { it == "resize:95:35" })
            assertEquals(listOf("input:once", "input:early"), link.calls.filter { it.startsWith("input:") })
        } finally { owner.close(); runCurrent() }
    }
    @Test fun anAttachedTerminalAcceptsAnOrdinaryLargePasteAndSurfacesRuntimeFailure() = runTest {
        val link = Link(); var unavailable = false
        val owner = CloudTerminalAttachment(this, { true }, { if (unavailable) throw UnsatisfiedLinkError("fixture"); link },
            { _, _ -> }, StandardTestDispatcher(testScheduler))
        try {
            owner.select("term_a"); owner.setAvailable(true); runCurrent()
            assertTrue(owner.send(ByteArray(64 * 1024) { 65 })); runCurrent()
            assertEquals(1, link.calls.count { it.startsWith("input:") })
            unavailable = true; owner.replay(); runCurrent()
            assertEquals(CloudAttachmentPhase.FAILED, owner.state.value.phase)
            assertEquals("Cloud native runtime is unavailable", owner.state.value.failure?.detail)
        } finally { owner.close(); runCurrent() }
    }
    @Test fun resizedGridRepaintsAfterFourHundredMillisecondsAndSnapshotCancelsRepaint() = runTest {
        val link = Link(); val owner = CloudTerminalAttachment(this, { true }, { link }, { _, _ -> }, StandardTestDispatcher(testScheduler))
        try {
            owner.select("term_a"); owner.setAvailable(true); runCurrent(); link.emit(1); runCurrent()
            link.emit(3, cols = 90); runCurrent(); advanceTimeBy(300); link.emit(3, cols = 100); runCurrent()
            advanceTimeBy(399); runCurrent(); assertEquals(1, link.calls.count { it.startsWith("attach:") })
            advanceTimeBy(1); runCurrent(); assertEquals(2, link.calls.count { it.startsWith("attach:") })
            link.emit(1, cols = 100); link.emit(3, cols = 120); runCurrent()
            advanceTimeBy(200); link.emit(1, cols = 120); runCurrent(); advanceTimeBy(500); runCurrent()
            assertEquals(2, link.calls.count { it.startsWith("attach:") })
        } finally { owner.close(); runCurrent() }
    }
    @Test fun exitAndAccountCloseStopInputAndReleaseOnlyTheirOwnSlot() = runTest {
        var current = true; val link = Link()
        val account = Job(); val parent = CoroutineScope(StandardTestDispatcher(testScheduler) + account)
        val owner = CloudTerminalAttachment(parent, { current }, { link }, { _, _ -> }, StandardTestDispatcher(testScheduler))
        owner.select("term_a"); owner.setAvailable(true); runCurrent(); link.emit(4); runCurrent()
        assertEquals(CloudAttachmentPhase.EXITED, owner.state.value.phase); assertFalse(owner.send(byteArrayOf(1)))
        current = false; account.cancel(); runCurrent()
        assertEquals(CloudAttachmentPhase.CLOSED, owner.state.value.phase); assertEquals(0L, link.token)
        owner.select("term_b"); owner.setAvailable(true); runCurrent(); assertNull(owner.state.value.terminalId)
    }
    @Test fun aBlockedOldAttachCannotOvertakeItsSuccessorOrDetachItAfterward() = runTest {
        val workers = Executors.newFixedThreadPool(2); val dispatcher = workers.asCoroutineDispatcher()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val link = Link()
        link.attachWork = { terminal -> if (terminal == "old") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val owner = CloudTerminalAttachment(this, { true }, { link }, { _, _ -> }, dispatcher)
        try {
            owner.select("old"); owner.setAvailable(true); assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.select("new"); release.countDown()
            withContext(Dispatchers.Default) { withTimeout(5000) { owner.state.first { it.terminalId == "new" && it.phase == CloudAttachmentPhase.READY } } }
            assertEquals(listOf("attach:old", "attach:new"), link.calls.filter { it.startsWith("attach:") })
            assertEquals(2L, link.token)
        } finally {
            release.countDown(); owner.close()
            withContext(Dispatchers.Default) { withTimeout(5000) { while (link.token != 0L) delay(1) } }
            dispatcher.close(); runCurrent()
        }
    }
}
