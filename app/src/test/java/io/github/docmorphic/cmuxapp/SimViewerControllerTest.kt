package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimViewerControllerTest {
    private val config = SimMessage.Config(SimCodec.H264, 64, 96, 2f, SimOrientation.PORTRAIT, 4,
        listOf(byteArrayOf(0x67, 1), byteArrayOf(0x68, 1)))
    private fun frame(n: ULong = 1u) = SimMessage.Frame(n, 1, n, byteArrayOf(0, 0, 0, 1, 0x65))
    private class Lane : SimStreamLane {
        val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val sent = mutableListOf<SimMessage>()
        var closed = false
        var writing: suspend (SimMessage) -> Unit = { }
        override suspend fun read() = inbound.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            check(!closed)
            val message = SimStreamWire.decode(bytes.copyOfRange(4, bytes.size))
            writing(message); sent += message
        }
        override fun close() { closed = true; inbound.close() }
        fun host(vararg messages: SimMessage) { messages.forEach { check(inbound.trySend(SimStreamWire.encode(it)).isSuccess) } }
    }
    private class Source : SimLaneSource {
        val lanes = mutableListOf<Lane>()
        var opening: suspend (Lane) -> Unit = { }
        override suspend fun use(block: suspend (SimStreamLane) -> Unit): Boolean {
            val lane = Lane(); lanes += lane
            try { opening(lane); block(lane); return true } finally { lane.close() }
        }
    }
    private class Presenter : SimFramePresenter {
        var configs = 0; var frames = 0; var resets = 0
        var resetting: suspend () -> Unit = { }
        var configuring: suspend () -> Unit = { }
        override suspend fun configure(config: SimMessage.Config) { configuring(); configs++ }
        override suspend fun present(frame: SimMessage.Frame): Boolean { frames++; return true }
        override suspend fun reset() { resets++; resetting() }
    }
    private fun TestScope.controller(presenter: Presenter = Presenter(), parent: CoroutineScope = this) =
        SimViewerController(parent, presenter, nowMillis = { testScheduler.currentTime })

    @Test fun immediatelyExecutingDispatcherCannotOpenBeforeAttemptPublication() = runBlocking<Unit> {
        val source = Source()
        val owner = SimViewerController(CoroutineScope(coroutineContext + Dispatchers.Unconfined), Presenter())
        try {
            owner.bindSource(source); owner.activate()
            withTimeout(2000) { while (source.lanes.firstOrNull()?.sent?.isEmpty() != false) yield() }
            assertEquals(SimMessage.Start(1u, 2000, listOf(SimCodec.HEVC, SimCodec.H264)), source.lanes.single().sent.single())
            assertFalse(source.lanes.single().closed)
        } finally { owner.awaitClosed() }
    }

    @Test fun hostRecoveryStaysOnOneLaneAndClosedRequiresExplicitRefresh() = runTest {
        val presenter = Presenter(); val source = Source(); val owner = controller(presenter)
        try {
            owner.activate(); runCurrent()
            assertEquals(SimViewerLifecycle.Phase.WAITING, owner.state.value.phase)
            owner.bindSource(source); runCurrent()
            val lane = source.lanes.single()
            assertEquals(SimMessage.Start(1u, 2000, listOf(SimCodec.HEVC, SimCodec.H264)), lane.sent.single())
            lane.host(config, frame()); runCurrent()
            for (status in listOf(SimHostStatus.DEVICE_UNAVAILABLE, SimHostStatus.WORKER_CRASHED, SimHostStatus.FAILED)) {
                lane.host(SimMessage.State(status, "Recoverable host state")); runCurrent()
                assertEquals(status, owner.state.value.hostStatus)
                assertFalse(lane.closed)
                lane.host(frame()); runCurrent()
                assertEquals(SimHostStatus.STREAMING, owner.state.value.hostStatus)
            }
            assertEquals(1, source.lanes.size); assertEquals(4uL, owner.state.value.presentedFrames)
            lane.host(SimMessage.State(SimHostStatus.CLOSED, "Panel closed")); runCurrent()
            assertEquals(SimViewerLifecycle.Phase.UNAVAILABLE, owner.state.value.phase)
            assertEquals("Panel closed", owner.state.value.reason)
            assertTrue(lane.closed); assertFalse(owner.input(SimInput.Button(SimButton.HOME)))
            advanceTimeBy(20000); runCurrent(); assertEquals(1, source.lanes.size)
            owner.refresh(); runCurrent()
            assertEquals(2, source.lanes.size); assertEquals(2uL, (source.lanes.last().sent.single() as SimMessage.Start).epoch)
            assertNull(owner.state.value.hostStatus); assertTrue(owner.state.value.reconnecting)
        } finally { owner.awaitClosed() }
    }

    @Test fun stalledStartingAndStreamingAttemptsUseBackoffUntilActualPresentation() = runTest {
        val source = Source(); val owner = controller()
        try {
            owner.bindSource(source); owner.activate(); runCurrent()
            advanceTimeBy(8000); runCurrent()
            assertTrue(source.lanes[0].closed)
            assertEquals(SimViewerLifecycle.Phase.RETRYING, owner.state.value.phase)
            advanceTimeBy(249); runCurrent(); assertEquals(1, source.lanes.size)
            advanceTimeBy(1); runCurrent(); assertEquals(2, source.lanes.size)
            source.lanes.last().host(config); runCurrent()
            advanceTimeBy(8000); runCurrent()
            advanceTimeBy(499); runCurrent(); assertEquals(2, source.lanes.size)
            advanceTimeBy(1); runCurrent(); assertEquals(3, source.lanes.size)
            source.lanes.last().host(config, frame()); runCurrent()
            // Recent presentation prevents the next watchdog tick from retiring a live stream.
            advanceTimeBy(7900); source.lanes.last().host(frame(2u)); runCurrent()
            advanceTimeBy(100); runCurrent(); assertEquals(3, source.lanes.size)
            assertFalse(source.lanes.last().closed)
            advanceTimeBy(8000); runCurrent()
            advanceTimeBy(250); runCurrent(); assertEquals(4, source.lanes.size)
        } finally { owner.awaitClosed() }
    }

    @Test fun backgroundCancelsRetryAndForegroundUsesCurrentSourceWithPersistedQuality() = runTest {
        val first = Source(); val second = Source(); val owner = controller()
        try {
            owner.bindSource(first); owner.activate(); runCurrent()
            owner.setQuality(SimQuality.BALANCED); runCurrent()
            assertEquals(listOf(2000, 1280), first.lanes.single().sent.filterIsInstance<SimMessage.Start>().map { it.maximumLongSide })
            first.lanes.single().inbound.close(); runCurrent()
            owner.background(); owner.bindSource(second); owner.setQuality(SimQuality.DATA_SAVER)
            advanceTimeBy(20000); runCurrent()
            assertEquals(SimViewerLifecycle.Phase.BACKGROUNDED, owner.state.value.phase)
            assertTrue(second.lanes.isEmpty())
            owner.foreground(); runCurrent()
            assertEquals(800, (second.lanes.single().sent.single() as SimMessage.Start).maximumLongSide)
            owner.deactivate(); runCurrent()
            assertTrue(second.lanes.single().closed)
            advanceTimeBy(20000); runCurrent(); assertEquals(1, second.lanes.size)
        } finally { owner.awaitClosed() }
    }

    @Test fun cancelledLateOpenCannotSendInputOrAttachToAReplacementClient() = runTest {
        val gate = CompletableDeferred<Unit>(); val old = Source(); val fresh = Source(); val owner = controller()
        old.opening = { withContext(NonCancellable) { gate.await() } }
        try {
            owner.bindSource(old); owner.activate(); runCurrent()
            assertTrue(owner.input(SimInput.Text("must not replay")))
            owner.bindSource(fresh); runCurrent(); advanceTimeBy(250); runCurrent()
            assertTrue(fresh.lanes.isEmpty()) // Old cancellation/decoder cleanup is still pending.
            gate.complete(Unit); runCurrent()
            assertTrue(old.lanes.single().closed); assertTrue(old.lanes.single().sent.isEmpty())
            assertEquals(1, fresh.lanes.size)
            assertEquals(1, fresh.lanes.single().sent.size)
            assertTrue(fresh.lanes.single().sent.single() is SimMessage.Start)
        } finally { gate.complete(Unit); owner.awaitClosed() }
    }

    @Test fun repeatedRefreshesAwaitOldResetAndIgnoreCancelledConfiguration() = runTest {
        val configureGate = CompletableDeferred<Unit>(); val resetGate = CompletableDeferred<Unit>()
        val presenter = Presenter(); val source = Source(); val owner = controller(presenter)
        presenter.configuring = { withContext(NonCancellable) { configureGate.await() } }
        presenter.resetting = { resetGate.await() }
        try {
            owner.bindSource(source); owner.activate(); runCurrent()
            source.lanes.single().host(config); runCurrent()
            owner.refresh(); owner.refresh(); runCurrent()
            configureGate.complete(Unit); runCurrent()
            assertEquals(1, source.lanes.size)
            assertEquals(0L, owner.state.value.width) // The old configured callback was fenced.
            assertEquals(1, presenter.resets)
            owner.refresh(); owner.refresh(); runCurrent()
            assertEquals(1, source.lanes.size)
            presenter.configuring = { }; presenter.resetting = { }; resetGate.complete(Unit); runCurrent()
            assertEquals(2, source.lanes.size)
            source.lanes.last().host(config, frame()); runCurrent()
            assertEquals(64L, owner.state.value.width); assertEquals(1uL, owner.state.value.presentedFrames)
            assertEquals(1, presenter.resets) // Old cleanup must not reset the new decoder afterward.
        } finally { configureGate.complete(Unit); resetGate.complete(Unit); owner.awaitClosed() }
    }

    @Test fun closingOrParentCancellationReleasesLaneAndDecoderWithoutClosingSource() = runTest {
        val parentJob = SupervisorJob(coroutineContext[Job])
        val presenter = Presenter(); val source = Source()
        val owner = controller(presenter, CoroutineScope(coroutineContext + parentJob))
        owner.bindSource(source); owner.activate(); runCurrent()
        parentJob.cancel(); runCurrent(); parentJob.join()
        assertTrue(source.lanes.single().closed)
        assertEquals(SimMessage.Stop, source.lanes.single().sent.last())
        assertEquals(1, presenter.resets)
        assertFalse(owner.input(SimInput.Text("late")))
        owner.awaitClosed(); owner.refresh(); owner.foreground(); owner.activate(); runCurrent()
        assertEquals(1, source.lanes.size)
        assertEquals(SimViewerLifecycle.Phase.STOPPED, owner.state.value.phase)
    }

    @Test fun blockedStopHasABoundedDeadlineBeforeDecoderCleanupAndReplacement() = runTest {
        val presenter = Presenter(); val source = Source(); val owner = controller(presenter)
        try {
            owner.bindSource(source); owner.activate(); runCurrent()
            val old = source.lanes.single()
            old.writing = { if (it == SimMessage.Stop) awaitCancellation() }
            owner.refresh(); runCurrent()
            assertEquals(1, source.lanes.size); assertEquals(0, presenter.resets)
            advanceTimeBy(999); runCurrent(); assertFalse(old.closed)
            advanceTimeBy(1); runCurrent()
            assertTrue(old.closed); assertEquals(1, presenter.resets)
            assertEquals(2, source.lanes.size)
        } finally { owner.awaitClosed() }
    }
}
