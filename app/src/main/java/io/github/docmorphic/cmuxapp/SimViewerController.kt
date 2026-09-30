package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class SimQuality(val maximumLongSide: Int) { HIGH(2000), BALANCED(1280), DATA_SAVER(800) }

/** The view supplies its current borrowed RPC client; the controller never closes that client. */
internal fun interface SimLaneSource {
    suspend fun use(block: suspend (SimStreamLane) -> Unit): Boolean
}

internal class MobileSimLaneSource(private val client: MobileRpcClient, private val panelId: String) : SimLaneSource {
    init { simulatorLaneDescriptor(panelId) }
    override suspend fun use(block: suspend (SimStreamLane) -> Unit) = client.useSimulatorLane(panelId, block)
}

internal data class SimViewerState(
    val phase: SimViewerLifecycle.Phase = SimViewerLifecycle.Phase.IDLE,
    val reason: String? = null,
    val hostStatus: SimHostStatus? = null,
    val hostDetail: String = "",
    val width: Long = 0,
    val height: Long = 0,
    val scale: Float = 1f,
    val orientation: SimOrientation = SimOrientation.PORTRAIT,
    val presentedFrames: ULong = 0u,
    val attachment: ULong = 0u,
    val quality: SimQuality = SimQuality.HIGH,
) {
    val reconnecting: Boolean get() = phase == SimViewerLifecycle.Phase.RETRYING ||
        (presentedFrames > 0u && phase in setOf(SimViewerLifecycle.Phase.WAITING, SimViewerLifecycle.Phase.STARTING))
}

/**
 * One panel/view owns this controller. Call its methods on the supplied scope's
 * single dispatcher (Main.immediate in the UI). Read-only state is a StateFlow.
 * A source object represents one client/lease identity; replace it on reconnect.
 *
 * Every attempt owns one session/outbox. Generation checks reject late callbacks,
 * and new attempts await the preceding decoder cleanup, including when multiple
 * refreshes occur while an old open/reset is still suspended. The presenter and
 * its Surface remain view-owned: awaitClosed before finally disposing them.
 */
internal class SimViewerController(
    parentScope: CoroutineScope,
    private val presenter: SimFramePresenter,
    private val codecs: List<SimCodec> = listOf(SimCodec.HEVC, SimCodec.H264),
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val progressTimeoutMillis: Long = 8000,
) : AutoCloseable {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val lifecycle = SimViewerLifecycle()
    private val mutableState = MutableStateFlow(SimViewerState())
    val state: StateFlow<SimViewerState> = mutableState.asStateFlow()
    private var source: SimLaneSource? = null
    private var attempt: Attempt? = null
    private var lastAttemptJob: Job? = null
    private var retry: Job? = null
    private var watchdog: Job? = null
    private var epoch = 0uL
    private var closed = false

    private class Attempt(val session: SimStreamSession) {
        lateinit var job: Job
        var lastProgress = 0L
    }

    init { require(progressTimeoutMillis > 0); require(codecs.isNotEmpty()) }

    fun bindSource(value: SimLaneSource?) {
        if (closed || source === value) return
        source = value
        if (attempt != null) transition(SimViewerLifecycle.Event.TRANSPORT_LOST)
        openWhenReady()
    }

    fun activate() {
        if (closed) return
        transition(SimViewerLifecycle.Event.ACTIVATE); openWhenReady()
    }

    fun deactivate() {
        if (closed) return
        transition(SimViewerLifecycle.Event.DEACTIVATE)
    }

    fun background() {
        if (closed) return
        transition(SimViewerLifecycle.Event.BACKGROUND)
    }

    fun foreground() {
        if (closed) return
        transition(SimViewerLifecycle.Event.FOREGROUND); openWhenReady()
    }

    fun refresh() {
        if (closed) return
        mutableState.value = state.value.copy(hostStatus = null, hostDetail = "")
        transition(SimViewerLifecycle.Event.REFRESH); openWhenReady()
    }

    fun input(event: SimInput): Boolean = !closed && lifetime.isActive &&
        attempt?.session?.input(event) == true

    fun setQuality(value: SimQuality) {
        if (closed || value == state.value.quality) return
        mutableState.value = state.value.copy(quality = value)
        attempt?.session?.updateQuality(value.maximumLongSide)
    }

    private fun openWhenReady() {
        if (source != null && lifetime.isActive) transition(SimViewerLifecycle.Event.TRANSPORT_READY)
    }

    private fun transition(event: SimViewerLifecycle.Event) {
        apply(lifecycle.handle(event)); publishPhase()
    }

    private fun publishPhase() {
        mutableState.value = state.value.copy(phase = lifecycle.phase, reason = lifecycle.reason)
    }

    private fun apply(action: SimViewerLifecycle.Action) {
        when (action) {
            SimViewerLifecycle.Action.None -> Unit
            SimViewerLifecycle.Action.Open -> open()
            SimViewerLifecycle.Action.Teardown -> retire()
            is SimViewerLifecycle.Action.Retry -> {
                retire()
                retry = scope.launch {
                    delay(action.delayMillis)
                    transition(SimViewerLifecycle.Event.RETRY_ELAPSED); openWhenReady()
                }
            }
        }
    }

    private fun retire() {
        retry?.cancel(); retry = null
        watchdog?.cancel(); watchdog = null
        val previous = attempt
        attempt = null // Fence callbacks and new input before cancelling any suspended operation.
        previous?.session?.retireInput()
        previous?.job?.cancel()
    }

    private fun open() {
        val binding = source ?: return
        check(attempt == null)
        check(epoch != ULong.MAX_VALUE) { "Simulator attachment sequence exhausted" }
        epoch++
        mutableState.value = state.value.copy(attachment = epoch)
        lateinit var current: Attempt
        val session = SimStreamSession(presenter, epoch, state.value.quality.maximumLongSide, codecs) { event ->
            currentCoroutineContext().ensureActive()
            if (attempt === current && !closed) receive(current, event)
        }
        current = Attempt(session)
        val predecessor = lastAttemptJob
        val published = CompletableDeferred<Unit>()
        current.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var ownsPresenter = false
            try {
                // Enter the cleanup-protected region before publishing the job.
                // Unlike yield(), this gate MUST suspend on immediate/UI dispatchers,
                // so a synchronous opener cannot observe an unpublished attempt.
                published.await()
                predecessor?.join()
                ensureActive()
                ownsPresenter = true
                current.lastProgress = nowMillis()
                startWatchdog(current)
                binding.use { lane ->
                    try {
                        ensureActive()
                        check(attempt === current && source === binding)
                        session.run(lane)
                    } finally { lane.close() }
                }
            } catch (_: CancellationException) {
                // A retired generation is ignored below. Cancellation by the RPC
                // lease itself still causes the current viewer to await/retry transport.
            } catch (_: Exception) {
                // No frame payloads, account details, or transport errors enter UI/log state.
            } finally {
                session.retireInput()
                withContext(NonCancellable) {
                    // Preserve the cleanup chain even if cancelled before acquiring the presenter.
                    predecessor?.join()
                    if (ownsPresenter) runCatching { presenter.reset() }
                }
            }
            if (attempt === current && !closed && lifetime.isActive)
                transition(SimViewerLifecycle.Event.WEDGED)
        }
        attempt = current
        lastAttemptJob = current.job
        published.complete(Unit)
    }

    private fun receive(current: Attempt, event: SimViewerEvent) {
        when (event) {
            is SimViewerEvent.Configured -> {
                val config = event.config
                mutableState.value = state.value.copy(width = config.width, height = config.height,
                    scale = config.scale, orientation = config.orientation)
                transition(SimViewerLifecycle.Event.CONFIGURED)
            }
            is SimViewerEvent.Presented -> {
                current.lastProgress = nowMillis()
                mutableState.value = state.value.copy(hostStatus = SimHostStatus.STREAMING,
                    presentedFrames = state.value.presentedFrames + 1u)
                transition(SimViewerLifecycle.Event.PRESENTED)
            }
            is SimViewerEvent.HostState -> {
                mutableState.value = state.value.copy(hostStatus = event.state.status, hostDetail = event.state.detail)
                apply(lifecycle.hostEnded(event.state.status, event.state.detail)); publishPhase()
            }
        }
    }

    private fun startWatchdog(current: Attempt) {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (true) {
                delay(progressTimeoutMillis)
                if (attempt !== current) return@launch
                if (nowMillis() - current.lastProgress >= progressTimeoutMillis) {
                    transition(SimViewerLifecycle.Event.WEDGED)
                    return@launch
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        transition(SimViewerLifecycle.Event.DEACTIVATE)
        closed = true
        source = null
        lifetime.cancel()
    }

    suspend fun awaitClosed() { close(); lifetime.join() }
}
