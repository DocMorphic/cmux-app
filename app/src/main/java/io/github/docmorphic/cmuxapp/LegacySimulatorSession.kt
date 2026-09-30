package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID

internal interface LegacySimulatorEndpoint {
    val events: Flow<MobileRpcClient.Event>
    suspend fun subscribe(streamId: String): JSONObject
    suspend fun unsubscribe(streamId: String)
    suspend fun request(method: String, params: JSONObject): JSONObject
}

internal class MobileLegacySimulatorEndpoint(private val client: MobileRpcClient) : LegacySimulatorEndpoint {
    override val events = client.events
    override suspend fun subscribe(streamId: String) = client.subscribe(
        listOf("simulator.frame", "simulator.state", "simulator.closed"), streamId)
    override suspend fun unsubscribe(streamId: String) { client.unsubscribe(streamId) }
    override suspend fun request(method: String, params: JSONObject) = client.request(method, params)
}

internal enum class LegacySimulatorPhase { STARTING, STREAMING, STALLED, LOCKED, FAILED, CLOSED, STOPPED }
internal data class LegacySimulatorPresentation<T>(val frame: LegacySimulatorFrame, val image: T)
internal data class LegacySimulatorState<T>(val descriptor: NativeSimulator,
    val phase: LegacySimulatorPhase = LegacySimulatorPhase.STARTING,
    val presentation: LegacySimulatorPresentation<T>? = null, val inputPaused: Boolean = false)

/** One attachment. All calls and state changes belong to the caller's single owner dispatcher. */
internal class LegacySimulatorSession<T>(descriptor: NativeSimulator, private val capabilities: Set<String>,
    private val decode: suspend (LegacySimulatorFrame) -> T?, private val discard: (T) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutable = MutableStateFlow(LegacySimulatorState<T>(descriptor.copy(ownedByCurrentConnection = false)))
    val state = mutable.asStateFlow()
    private val pending = ArrayDeque<LegacySimulatorInput>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var bytes = 0L
    private var running = false
    private var used = false
    private var retired = false
    private var recovering = false
    private var recoveryPending = false
    private var started = false
    private var stopRequired = false
    private var stateRevision = 0L
    private var latestSharedState: NativeSimulator? = null
    private var activity = 0L
    private var generation = 0L

    fun accepts(input: LegacySimulatorInput): Boolean {
        val current = state.value
        return running && !retired && !recovering && !recoveryPending && !current.inputPaused &&
            "simulator.input.v1" in capabilities && current.descriptor.ownedByCurrentConnection == true &&
            current.phase in setOf(LegacySimulatorPhase.STARTING, LegacySimulatorPhase.STREAMING) && when (input) {
                is LegacySimulatorInput.Pointer -> current.descriptor.supportsTouch
                is LegacySimulatorInput.Text -> current.descriptor.supportsKeyboard
                is LegacySimulatorInput.Button -> current.descriptor.supportsHardwareButtons
            }
    }

    fun input(value: LegacySimulatorInput): Boolean {
        if (!accepts(value)) return false
        val size = size(value)
        if (size > 64 * 1024 || pending.size >= 512 || bytes + size > 64 * 1024) {
            pauseInput(); return false
        }
        pending.addLast(value); bytes += size; wake.trySend(Unit); return true
    }

    /** Fence synchronously before cancellation so the old view cannot send into a new attachment. */
    fun retire() { retired = true; generation++; pending.clear(); bytes = 0; wake.close() }

    fun refresh(): Boolean {
        if (!running || retired || state.value.phase == LegacySimulatorPhase.CLOSED || recovering) return false
        pending.clear(); bytes = 0; generation++
        recoveryPending = true; wake.trySend(Unit); return true
    }

    private fun size(value: LegacySimulatorInput) = when (value) {
        is LegacySimulatorInput.Text -> value.text.toByteArray(Charsets.UTF_8).size.toLong() + 64
        else -> 128L
    }
    private fun parameters() = JSONObject().put("panel_id", state.value.descriptor.panelId)
        .put("workspace_id", state.value.descriptor.workspaceId)
    private fun pauseInput() {
        generation++; pending.clear(); bytes = 0
        mutable.value = state.value.copy(inputPaused = true)
    }
    private fun markLocked() {
        pauseInput()
        mutable.value = state.value.copy(phase = LegacySimulatorPhase.LOCKED,
            descriptor = state.value.descriptor.copy(ownedByCurrentConnection = false))
    }
    private fun applyDescriptor(descriptor: NativeSimulator) {
        val previous = state.value
        val merged = descriptor.preservingOwnership(previous.descriptor)
        val otherOwner = merged.ownerConnectionId != null && merged.ownedByCurrentConnection != true
        mutable.value = previous.copy(descriptor = merged, phase = when {
            otherOwner -> LegacySimulatorPhase.LOCKED
            previous.phase == LegacySimulatorPhase.LOCKED -> if (previous.presentation != null)
                LegacySimulatorPhase.STREAMING else LegacySimulatorPhase.STARTING
            else -> previous.phase
        })
        if (merged.ownedByCurrentConnection != true) { pending.clear(); bytes = 0; generation++ }
    }

    suspend fun run(client: MobileRpcClient) = client.useEventSession { run(MobileLegacySimulatorEndpoint(it)) }

    suspend fun run(endpoint: LegacySimulatorEndpoint) = coroutineScope {
        check(!used); used = true
        check("simulator.stream.v1" in capabilities)
        val stream = UUID.randomUUID().toString()
        val panel = state.value.descriptor.panelId
        val workspace = state.value.descriptor.workspaceId
        val finished = CompletableDeferred<Unit>()
        running = true; activity = nowMillis()
        val frames = LegacySimulatorFrames(this, decode, discard, presented = { frame, image ->
            val current = state.value
            if (!retired && current.phase != LegacySimulatorPhase.CLOSED) mutable.value = current.copy(
                presentation = LegacySimulatorPresentation(frame, image), phase = if (current.phase == LegacySimulatorPhase.LOCKED)
                    current.phase else LegacySimulatorPhase.STREAMING)
            else discard(image)
        }, stalled = {
            if (started && !retired && state.value.phase != LegacySimulatorPhase.LOCKED) {
                mutable.value = state.value.copy(phase = LegacySimulatorPhase.STALLED); refresh()
            }
        })
        suspend fun start() {
            recovering = true
            val revision = stateRevision
            try {
                val response = endpoint.request("mobile.simulator.stream.start", parameters())
                ensureActive()
                val descriptor = NativeSimulator.read(response, workspace)?.takeIf { it.panelId == panel }
                    ?: error("Invalid Simulator start response")
                if (retired || state.value.phase == LegacySimulatorPhase.CLOSED) return
                stopRequired = true; started = true; activity = nowMillis()
                if (revision == stateRevision) applyDescriptor(descriptor)
                else latestSharedState?.takeIf { it.ownedByCurrentConnection == null &&
                    it.ownerConnectionId != null && it.ownerConnectionId == descriptor.ownerConnectionId }?.let {
                    applyDescriptor(it.copy(ownedByCurrentConnection = descriptor.ownedByCurrentConnection))
                }
                if (state.value.phase == LegacySimulatorPhase.FAILED) mutable.value = state.value.copy(
                    phase = if (state.value.presentation == null) LegacySimulatorPhase.STARTING else LegacySimulatorPhase.STREAMING)
                mutable.value = state.value.copy(inputPaused = false)
            } catch (failure: Exception) {
                if (failure is CancellationException && !currentCoroutineContext().isActive) throw failure
                started = false
                if (failure is MobileRpcException && failure.code == "locked") markLocked()
                else if (state.value.phase != LegacySimulatorPhase.STALLED) mutable.value = state.value.copy(phase = LegacySimulatorPhase.FAILED)
            } finally { recovering = false }
        }
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            endpoint.events.collect { event ->
                if (retired || event.streamId != stream || event.payload.opt("panel_id") != panel) return@collect
                when (event.topic) {
                    "simulator.frame" -> LegacySimulatorFrame.read(event.payload, panel)?.let {
                        activity = nowMillis()
                        frames.submit(it, allowDuplicate = state.value.phase == LegacySimulatorPhase.STALLED)
                    }
                    "simulator.state" -> NativeSimulator.read(event.payload, workspace)?.let {
                        stateRevision++; latestSharedState = it; activity = nowMillis(); applyDescriptor(it)
                    }
                    "simulator.closed" -> {
                        stopRequired = false; started = false; retire(); frames.close()
                        mutable.value = state.value.copy(phase = LegacySimulatorPhase.CLOSED, presentation = null,
                            descriptor = state.value.descriptor.copy(ownedByCurrentConnection = false))
                        finished.complete(Unit)
                    }
                }
            }
        }
        var worker: Job? = null
        var watchdog: Job? = null
        try {
            val subscribed = endpoint.subscribe(stream)
            check(subscribed.opt("stream_id") == stream) { "Simulator subscription identity changed" }
            if (!retired) start()
            worker = launch {
                for (signal in wake) {
                    if (recoveryPending && !retired) { recoveryPending = false; start() }
                    while (!retired) {
                        val value = pending.removeFirstOrNull() ?: break
                        val epoch = generation
                        try {
                            if (accepts(value)) {
                                val (method, params) = value.request(workspace, panel)
                                endpoint.request(method, params)
                            }
                        } catch (failure: Exception) {
                            if (failure is CancellationException && !currentCoroutineContext().isActive) throw failure
                            if (failure is MobileRpcException && failure.code == "locked") markLocked() else pauseInput()
                        } finally { if (epoch == generation) bytes = (bytes - size(value)).coerceAtLeast(0) }
                    }
                }
            }
            if ("simulator.keepalive.v1" in capabilities) watchdog = launch {
                while (isActive) {
                    delay(15_000)
                    if (started && !retired && !recovering && nowMillis() - activity >= 15_000 &&
                        state.value.phase != LegacySimulatorPhase.LOCKED) {
                        mutable.value = state.value.copy(phase = LegacySimulatorPhase.STALLED); refresh()
                    }
                }
            }
            finished.await()
        } finally {
            retire(); running = false; collector.cancel(); worker?.cancel(); watchdog?.cancel(); frames.close()
            if (state.value.phase != LegacySimulatorPhase.CLOSED) mutable.value = state.value.copy(
                phase = LegacySimulatorPhase.STOPPED, descriptor = state.value.descriptor.copy(ownedByCurrentConnection = false))
            withContext(NonCancellable) {
                collector.join(); worker?.join(); watchdog?.join(); frames.awaitClosed()
                if (stopRequired) runCatching { withTimeout(2000) { endpoint.request("mobile.simulator.stream.stop", parameters()) } }
                runCatching { withTimeout(2000) { endpoint.unsubscribe(stream) } }
            }
        }
    }
}
