package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.EOFException
import java.util.UUID
import javax.net.SocketFactory

/** Retains the protocol's error code so callers can distinguish retryable failures. */
internal class MobileRpcException(val code: String?, message: String) : IllegalStateException(message)
internal class MobileRpcOutcomeUnknown : java.io.IOException("The connection recovered, but this action’s outcome is unknown. Check the Mac before retrying.")

/**
 * The control channel of cmux's mobile RPC protocol. The caller must supply a
 * legacy route or admitted native transport and a current same-account Stack token.
 * Control requests are never replayed here after a timeout. Optional input delivery
 * identities are interpreted by the session sender.
 */
class MobileRpcClient internal constructor(
    private val transport: MobileRpcTransport,
    private val accessToken: suspend () -> String?,
    private val attachToken: String? = null,
    private val delegate: MobileRpcClient? = null,
    private val releaseLease: (() -> Unit)? = null
) : AutoCloseable {
    constructor(route: PairingCode.Route, accessToken: suspend () -> String?, attachToken: String? = null,
                socketFactory: SocketFactory = SocketFactory.getDefault()) :
        this(SocketMobileRpcTransport(route, socketFactory), accessToken, attachToken)

    // Scoped to this foreground lease only. Internal identity overloads bypass it.
    @Volatile internal var terminalInputDispatcher: (suspend (TerminalInputOperation) -> JSONObject)? = null
    private suspend fun terminalOperation(operation: TerminalInputOperation): JSONObject =
        terminalInputDispatcher?.invoke(operation) ?: operation.rpc(this, null)

    @Volatile internal var terminalDeviceName: String? = null
    private fun JSONObject.withTerminalDevice(): JSONObject = apply {
        terminalDeviceName?.let { put("device_kind", "unknown"); put("device_name", it) }
    }
    @Volatile internal var terminalTrafficAllowed: (String) -> Boolean = { true }
    internal fun checkTerminalTraffic(surface: String) {
        check(terminalTrafficAllowed(surface)) { "This terminal was disconnected. Reattach before continuing." }
    }
    private val terminalEventObservers = java.util.concurrent.CopyOnWriteArrayList<(Event) -> Unit>()
    /** Synchronous admission updates precede the lossy display-event flow. Callbacks must not call RPC. */
    internal fun observeTerminalSizing(observer: (Event) -> Unit): AutoCloseable {
        if (delegate != null) return delegate.observeTerminalSizing(observer)
        terminalEventObservers += observer
        return AutoCloseable { terminalEventObservers -= observer }
    }

    data class Event(val topic: String, val payload: JSONObject, val streamId: String?, val deliverySequence: Long = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val stateLock = Any()
    private class Pending(val answer: CompletableDeferred<JSONObject>, val frame: ByteArray,
                          val resend: Boolean, val inbound: Long, val epoch: Long,
                          val startedNanos: Long, val deadlineNanos: Long, val admitted: () -> Unit, var generation: Long? = null, var sequence: Long = 0)
    private val pending = linkedMapOf<String, Pending>()
    private val leaseOperations = mutableSetOf<Job>()
    private val eventsMutable = MutableSharedFlow<Event>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: kotlinx.coroutines.flow.SharedFlow<Event> = delegate?.events ?: eventsMutable.asSharedFlow()
    private val disconnectedMutable = MutableSharedFlow<Throwable>(replay = 1)
    val disconnected: kotlinx.coroutines.flow.SharedFlow<Throwable> = delegate?.disconnected ?: disconnectedMutable.asSharedFlow()

    private val connectMutex = Mutex()
    private var connected = false
    private var closed = false
    private var eventDeliverySequence = 0L
    private var inboundDelivery = 0L
    private var silenceEpoch = 0L
    private var silentTimeouts = 0
    private var repairing = false
    private var unverifiedRepairs = 0
    private var writeSequence = 0L
    private var independentEventReader: Job? = null
    private val independentEventSubscriptions = mutableSetOf<String>()

    internal val isClosed: Boolean get() = synchronized(stateLock) { closed } || delegate?.isClosed == true

    internal fun retire() = failConnection(EOFException("Computer access or account session changed"))

    internal suspend fun transportDiagnostics(): MobileTransportDiagnostics? {
        if (delegate != null) return borrowing { it.transportDiagnostics() }
        return withContext(Dispatchers.IO) {
            check(!isClosed)
            transport.diagnostics().also { check(!isClosed) }
        }
    }

    /** Each consumer owns its subscriptions and cancellation, while one owner retains the wire. */
    internal fun lease(release: () -> Unit): MobileRpcClient {
        check(delegate == null && !isClosed) { "Cannot lease a closed or borrowed connection" }
        return MobileRpcClient(transport, accessToken, attachToken, this, release)
    }

    private suspend fun <T> borrowing(block: suspend (MobileRpcClient) -> T): T = coroutineScope {
        val operation = checkNotNull(currentCoroutineContext()[Job])
        synchronized(stateLock) {
            check(!closed) { "Connection lease closed" }
            leaseOperations += operation
        }
        try { block(checkNotNull(delegate)) }
        finally { synchronized(stateLock) { leaseOperations -= operation } }
    }

    suspend fun connect(): Unit = connectMutex.withLock {
        if (delegate != null) return@withLock borrowing { it.connect() }
        synchronized(stateLock) {
            check(!closed) { "Connection has been closed" }
            if (connected) return@withLock
        }
        try {
            transport.connect()
            synchronized(stateLock) {
                check(!closed) { "Connection has been closed" }
                connected = true
                scope.launch { readLoop() }
                transport.disconnections?.let { closures -> scope.launch {
                    try { closures.collect { failConnection(it) } }
                    catch (failure: Throwable) { failConnection(failure) }
                } }
            }
            prepareIndependentEvents()
            Unit
        } catch (error: Throwable) { failConnection(error); throw error }
    }

    suspend fun request(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMillis: Long = 15_000
    ): JSONObject {
        val surface = params.optString("surface_id")
        return requestAdmitted(method, params, timeoutMillis) {
            if (TerminalSizingTraffic.guarded(method)) checkTerminalTraffic(surface)
        }
    }

    private suspend fun requestAdmitted(method: String, params: JSONObject, timeoutMillis: Long,
        admitted: () -> Unit): JSONObject {
        admitted()
        if (delegate != null) return borrowing { it.requestAdmitted(method, params, timeoutMillis, admitted) }
        require(method.isNotBlank())
        val id = UUID.randomUUID().toString()
        val parameters = MobileJson.objectValue(params.toString())
        val body = JSONObject().put("id", id).put("method", method).put("params", parameters)
        val token = if (method == "mobile.host.status") {
            try { accessToken()?.trim() }
            catch (error: Exception) { if (error is CancellationException) throw error; null }
        } else {
            accessToken()?.trim().also {
                require(!it.isNullOrEmpty()) { "Sign in to cmux with the same account as your Mac" }
            }
        }
        if (!token.isNullOrEmpty()) {
            val auth = JSONObject().put("stack_access_token", token)
            if (method != "mobile.host.status" && !attachToken.isNullOrBlank()) auth.put("attach_token", attachToken)
            body.put("auth", auth)
        }
        val answer = CompletableDeferred<JSONObject>()
        val entry = synchronized(stateLock) {
            check(!closed && connected) { "Not connected to cmux" }
            val started = System.nanoTime()
            Pending(answer, MobileFrameCodec.encode(body.toString().toByteArray(Charsets.UTF_8)),
                MobileControlResendPolicy.allows(method, parameters), inboundDelivery, silenceEpoch, started,
                started + timeoutMillis.coerceIn(0, 86_400_000) * 1_000_000, admitted).also { pending[id] = it }
        }
        try {
            return withTimeout(timeoutMillis) {
                writeMutex.withLock {
                    val caller = currentCoroutineContext()
                    // A viewport/editor can disappear while its frame is being written. Finish
                    // that frame once started; abandoning it would corrupt every consumer's wire.
                    withContext(NonCancellable + Dispatchers.IO) {
                        caller.ensureActive() // Cancellation before writing sends nothing.
                        admitted() // Recheck after token lookup and waiting behind another write.
                        synchronized(stateLock) { check(!closed && connected) { "Connection closed" } }
                        val remaining = ((entry.deadlineNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
                        withTimeout(remaining) {
                            // Closing the transport also interrupts legacy blocking Socket writes,
                            // which coroutine timeout alone cannot interrupt.
                            val deadline = scope.launch {
                                delay(remaining)
                                failConnection(java.net.SocketTimeoutException("cmux RPC frame write timed out"))
                            }
                            try {
                                val generation = transport.writeWithGeneration(entry.frame)
                                synchronized(stateLock) { entry.generation = generation; entry.sequence = ++writeSequence }
                            } catch (error: Throwable) { failConnection(error); throw error }
                            finally { deadline.cancel() }
                        }
                    }
                }
                answer.await()
            }
        } catch (failure: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            responseTimedOut(entry, timeoutMillis)
            throw failure
        } finally {
            synchronized(stateLock) { pending.remove(id) }
        }
    }

    private fun responseTimedOut(entry: Pending, timeoutMillis: Long) {
        var retire = false
        var replace = false
        synchronized(stateLock) {
            if (closed || entry.generation == null || entry.answer.isCompleted) return
            if (entry.inbound != inboundDelivery) { silentTimeouts = 0; return }
            if (entry.epoch != silenceEpoch || repairing) return
            silenceEpoch++
            silentTimeouts++
            if (silentTimeouts >= 2) retire = true
            else if (transport.supportsControlRepair && unverifiedRepairs == 0) {
                repairing = true
                unverifiedRepairs++
                replace = true
            }
        }
        if (retire) failConnection(EOFException("cmux stopped answering requests"))
        if (replace) scope.launch { repairControl(entry.startedNanos, timeoutMillis.coerceIn(1, 5000)) }
    }

    private suspend fun repairControl(silentSinceNanos: Long, verificationMillis: Long) {
        try {
            val outcome = writeMutex.withLock {
                synchronized(stateLock) { if (closed) return }
                val result = transport.repairControl(silentSinceNanos)
                if (result is MobileControlRepair.Repaired) {
                    val stranded = synchronized(stateLock) { pending.toList().sortedBy { it.second.sequence } }
                    for ((id, request) in stranded) {
                        val resend = synchronized(stateLock) {
                            if (closed || pending[id] !== request || request.answer.isCompleted ||
                                request.generation == null || request.generation!! >= result.generation ||
                                System.nanoTime() >= request.deadlineNanos) false
                            else if (!request.resend) {
                                request.answer.completeExceptionally(MobileRpcOutcomeUnknown())
                                false
                            } else true
                        }
                        if (resend) {
                            try { request.admitted() }
                            catch (failure: Exception) { request.answer.completeExceptionally(failure); continue }
                            val remaining = ((request.deadlineNanos - System.nanoTime()) / 1_000_000).coerceIn(1, 5000)
                            val generation = withTimeout(remaining) { transport.writeWithGeneration(request.frame) }
                            synchronized(stateLock) { request.generation = generation }
                        }
                    }
                }
                result
            }
            when (outcome) {
                MobileControlRepair.Closed -> failConnection(EOFException("cmux connection closed"))
                MobileControlRepair.Unavailable -> Unit
                is MobileControlRepair.Repaired -> {
                    val answered = try {
                        withTimeout(verificationMillis) {
                            request(MobileControlResendPolicy.PROBE,
                                JSONObject().put("stream_id", "cmux.control-stream-probe.${UUID.randomUUID()}"), verificationMillis)
                        }
                        true
                    } catch (_: MobileRpcException) { true }
                    catch (failure: Exception) { currentCoroutineContext().ensureActive(); false }
                    if (answered) synchronized(stateLock) { silentTimeouts = 0; unverifiedRepairs = 0 }
                    else failConnection(EOFException("cmux did not answer on the replacement stream"))
                }
            }
        } catch (failure: Throwable) { failConnection(failure) }
        finally { synchronized(stateLock) { repairing = false } }
    }

    suspend fun hostStatus(): JSONObject = request("mobile.host.status")
    suspend fun workspaces(): JSONObject = request("mobile.workspace.list")
    internal suspend fun exchangePhonePushKey(buildID: String, descriptor: PhonePushDescriptor): JSONObject =
        request("phone_push.keys.exchange", JSONObject().put("version", 1).put("hpke_envelope_version", 2)
            .put("client_id", clientId).put("ios_build_id", buildID).put("descriptor", descriptor.wire()))
    suspend fun notifications(): JSONObject = request("notification.feed.list")
    suspend fun markNotificationRead(id: String): JSONObject = setNotificationRead(id, true)
    suspend fun setNotificationRead(id: String, read: Boolean): JSONObject = request(
        if (read) "notification.feed.mark_read" else "notification.feed.mark_unread",
        JSONObject().put("notification_ids", org.json.JSONArray().put(id))
    )
    suspend fun dismissNotifications(ids: List<String>): JSONObject {
        require(ids.isNotEmpty() && ids.size <= 128 && ids.all { it.isNotBlank() && it.length <= 1024 })
        return request("notification.dismiss", JSONObject().put("notification_ids", org.json.JSONArray(ids.distinct()))
            .put("client_id", clientId))
    }
    suspend fun reconcileNotifications(ids: List<String>): JSONObject {
        require(ids.size <= 256 && ids.all { it.isNotBlank() && it.length <= 1024 })
        return request("notification.reconcile", JSONObject().put("delivered_ids", org.json.JSONArray(ids.distinct()))
            .put("client_id", clientId))
    }
    suspend fun markAllNotificationsRead(): JSONObject = request("notification.feed.mark_all_read")
    suspend fun workspaceAction(
        workspaceId: String,
        windowId: String?,
        action: String,
        title: String? = null
    ): JSONObject {
        require(action in setOf("rename", "pin", "unpin", "mark_read", "mark_unread"))
        val params = JSONObject().put("workspace_id", workspaceId)
            .put("client_id", clientId).put("action", action)
        if (!windowId.isNullOrBlank()) params.put("window_id", windowId)
        if (action == "rename") params.put("title", title?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("Enter a workspace title"))
        return request("workspace.action", params)
    }

    suspend fun closeWorkspace(workspaceId: String, windowId: String?): JSONObject {
        val params = JSONObject().put("workspace_id", workspaceId).put("client_id", clientId)
        if (!windowId.isNullOrBlank()) params.put("window_id", windowId)
        return request("workspace.close", params)
    }
    suspend fun createGroup(title: String): JSONObject {
        val params = JSONObject()
        title.trim().takeIf { it.isNotEmpty() }?.let { params.put("title", it) }
        return request("workspace.group.create", params)
    }
    suspend fun groupAction(groupId: String, action: String, title: String? = null): JSONObject {
        require(action in setOf("rename", "pin", "unpin", "ungroup"))
        val params = JSONObject().put("group_id", groupId).put("action", action)
        if (action == "rename") params.put("title", title?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("Enter a group title"))
        return request("workspace.group.action", params)
    }
    suspend fun moveWorkspace(workspaceId: String, windowId: String?, groupId: String?,
        beforeWorkspaceId: String? = null, movesGroup: Boolean = false): JSONObject {
        val params = JSONObject().put("workspace_id", workspaceId).put("client_id", clientId)
        if (!windowId.isNullOrBlank()) params.put("window_id", windowId)
        if (!groupId.isNullOrBlank()) params.put("group_id", groupId)
        if (!beforeWorkspaceId.isNullOrBlank()) params.put("before_workspace_id", beforeWorkspaceId)
        if (movesGroup) params.put("move_group", true)
        return request("workspace.move", params)
    }
    suspend fun createTerminal(workspaceId: String): JSONObject = request(
        "terminal.create", JSONObject().put("workspace_id", workspaceId)
    )
    suspend fun createBrowser(workspaceId: String): JSONObject = request(
        "mobile.browser.create", JSONObject().put("workspace_id", workspaceId)
    )
    suspend fun changedFiles(workspaceId: String): JSONObject = request(
        "mobile.workspace.changes.files", JSONObject().put("workspace_id", workspaceId)
    )
    suspend fun fileDiff(workspaceId: String, path: String, maxLines: Int? = null): JSONObject = request(
        "mobile.workspace.changes.file_diff", JSONObject().put("workspace_id", workspaceId)
            .put("path", path).also { params -> maxLines?.let { params.put("max_lines", it.coerceIn(100, 96_000)) } }
    )
    internal fun changesContent(workspaceId: String) = ChangesContentTransfer(
        stat = { path, revision -> request("mobile.workspace.changes.file_stat", JSONObject()
            .put("workspace_id", workspaceId).put("path", path).put("revision", revision.wire)) },
        fetch = { path, revision, offset, length -> request("mobile.workspace.changes.file_fetch", JSONObject()
            .put("workspace_id", workspaceId).put("path", path).put("revision", revision.wire)
            .put("offset", offset).put("length", length)) },
    )
    suspend fun browserPanels(workspaceId: String): JSONObject = request(
        "mobile.browser.list", JSONObject().put("workspace_id", workspaceId)
    )
    suspend fun startBrowserStream(panelId: String, width: Int, height: Int, scale: Double): JSONObject = request(
        "mobile.browser.stream.start", JSONObject().put("panel_id", panelId)
            .put("viewport_width", width).put("viewport_height", height).put("viewport_scale", scale)
    )
    suspend fun stopBrowserStream(panelId: String): JSONObject = request(
        "mobile.browser.stream.stop", JSONObject().put("panel_id", panelId)
    )
    suspend fun browserViewport(panelId: String, width: Int, height: Int, scale: Double): JSONObject = request(
        "mobile.browser.viewport", JSONObject().put("panel_id", panelId)
            .put("viewport_width", width).put("viewport_height", height).put("viewport_scale", scale)
    )
    suspend fun acknowledgeBrowserFrame(panelId: String, sequence: Long): JSONObject = request(
        "mobile.browser.frame.ack", JSONObject().put("panel_id", panelId).put("seq", sequence)
    )
    suspend fun browserCommand(panelId: String, command: String, url: String? = null): JSONObject {
        require(command in setOf("navigate", "back", "forward", "reload"))
        val params = JSONObject().put("panel_id", panelId)
        if (command == "navigate") params.put("url", url ?: error("Enter an address"))
        return request("mobile.browser.$command", params)
    }
    suspend fun browserClick(panelId: String, x: Double, y: Double): JSONObject = request(
        "mobile.browser.input.pointer", JSONObject().put("panel_id", panelId)
            .put("kind", "click").put("x", x).put("y", y)
            .put("click_count", 1).put("button", "left")
    )
    suspend fun browserScroll(panelId: String, dy: Double, x: Double, y: Double, phase: String): JSONObject = request(
        "mobile.browser.input.scroll", JSONObject().put("panel_id", panelId)
            .put("dx", 0).put("dy", dy).put("x", x).put("y", y).put("phase", phase)
    )
    suspend fun browserText(panelId: String, text: String): JSONObject = request(
        "mobile.browser.input.text", JSONObject().put("panel_id", panelId).put("text", text)
    )
    suspend fun respondBrowserDialog(
        panelId: String, dialogId: String, buttonId: String, text: String?
    ): JSONObject {
        val params = JSONObject().put("panel_id", panelId).put("dialog_id", dialogId)
            .put("button_id", buttonId)
        if (text != null) params.put("text", text)
        return request("mobile.browser.dialog.respond", params)
    }
    suspend fun terminalClick(workspaceId: String, surfaceId: String, cell: TerminalGeometry.Cell): JSONObject =
        terminalOperation(TerminalInputOperation.Click(workspaceId, surfaceId, cell))

    internal suspend fun terminalClick(workspaceId: String, surfaceId: String, cell: TerminalGeometry.Cell,
        delivery: TerminalInputDelivery?): JSONObject =
        request("mobile.terminal.mouse", JSONObject().put("workspace_id", workspaceId)
            .put("surface_id", surfaceId).put("client_id", clientId)
            .put("col", cell.column.coerceAtLeast(0)).put("row", cell.row.coerceAtLeast(0)).withInputDelivery(delivery))

    suspend fun terminalScroll(workspaceId: String, surfaceId: String, scroll: TerminalScroll): JSONObject =
        terminalOperation(TerminalInputOperation.Scroll(workspaceId, surfaceId, scroll))

    internal suspend fun terminalScroll(workspaceId: String, surfaceId: String, scroll: TerminalScroll,
        delivery: TerminalInputDelivery?): JSONObject {
        val params = JSONObject().put("workspace_id", workspaceId).put("surface_id", surfaceId)
            .put("client_id", clientId).put("delta_lines", scroll.lines)
            .put("col", scroll.column.coerceAtLeast(0)).put("row", scroll.row.coerceAtLeast(0))
        scroll.prefetchRows?.let { params.put("max_scrollback_rows", it) }
        return request("mobile.terminal.scroll", params.withInputDelivery(delivery))
    }

    /** Materialize a selected lazy terminal without sending input or registering a viewport.
     * Inventory readiness alone cannot start a never-foregrounded Mac surface.
     */
    internal suspend fun prepareTerminal(workspaceId: String, surfaceId: String): Unit {
        request("mobile.terminal.replay", JSONObject().put("workspace_id", workspaceId)
            .put("surface_id", surfaceId).put("anchor", "screen").put("max_scrollback_rows", 0))
    }

    suspend fun replay(workspaceId: String, surfaceId: String, columns: Int, rows: Int, viewportGeneration: Long,
                       screenAnchor: Boolean = true, maxScrollbackRows: Int = 10_000): JSONObject {
        val params = JSONObject().put("workspace_id", workspaceId).put("surface_id", surfaceId)
            .put("client_id", clientId).put("viewport_columns", columns).put("viewport_rows", rows)
            .put("viewport_generation", viewportGeneration)
        if (screenAnchor) params.put("anchor", "screen").put("max_scrollback_rows", maxScrollbackRows.coerceIn(0, 10_000))
        return request("mobile.terminal.replay", params.withTerminalDevice())
    }

    suspend fun reportViewport(
        workspaceId: String, surfaceId: String, viewport: TerminalViewport, generation: Long
    ): JSONObject = request("mobile.terminal.viewport", JSONObject()
        .put("workspace_id", workspaceId).put("surface_id", surfaceId)
        .put("client_id", clientId)
        .put("viewport_columns", viewport.columns).put("viewport_rows", viewport.rows)
        .put("viewport_generation", generation).withTerminalDevice())

    suspend fun clearViewport(workspaceId: String, surfaceId: String, generation: Long): JSONObject =
        request("mobile.terminal.viewport", JSONObject()
            .put("workspace_id", workspaceId).put("surface_id", surfaceId)
            .put("client_id", clientId).put("clear", true)
            .put("viewport_generation", generation))

    internal val supportsArtifactLanes get() = transport.supportsArtifactLanes

    internal val supportsSimulatorLanes get() = transport.supportsSimulatorLanes
    internal val simulatorConnectionId: String get() = delegate?.simulatorConnectionId ?: clientId

    /** Keeps an event consumer inside its lease for its entire lifetime, including cleanup. */
    internal suspend fun useEventSession(use: suspend (MobileRpcClient) -> Unit) {
        if (delegate != null) return borrowing { it.useEventSession(use) }
        coroutineScope {
            val operation = checkNotNull(currentCoroutineContext()[Job])
            synchronized(stateLock) {
                check(!closed && connected) { "Connection closed" }
                leaseOperations += operation
            }
            try { use(this@MobileRpcClient) }
            finally { synchronized(stateLock) { leaseOperations -= operation } }
        }
    }

    internal val supportsBrowserTunnels: Boolean get() = !isClosed && transport.supportsBrowserTunnels

    /** Call only after negotiating browser.tunnel.v1 for this admitted Mac. */
    internal suspend fun useBrowserTunnel(host: String, port: Int, use: suspend (BrowserTunnelLane) -> Unit): Boolean {
        if (delegate != null) return borrowing { it.useBrowserTunnel(host, port, use) }
        synchronized(stateLock) { check(!closed && connected) }
        BrowserTunnelProtocol.connect(host, port) // Validate even when the route has no lanes.
        val lane = transport.openBrowserTunnel(host, port) ?: return false
        try {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) { check(!closed && connected) }
            use(lane)
            return true
        } finally { lane.close() }
    }

    internal suspend fun browserListeningPorts(): BrowserTunnelProtocol.ListeningPorts? {
        if (delegate != null) return borrowing { it.browserListeningPorts() }
        synchronized(stateLock) { check(!closed && connected) }
        val listing = transport.browserListeningPorts()
        currentCoroutineContext().ensureActive()
        synchronized(stateLock) { check(!closed && connected) }
        return listing
    }

    /** The caller's lease owns cancellation; a simulator never borrows terminal/control bytes. */
    internal suspend fun useSimulatorLane(panelId: String, use: suspend (SimStreamLane) -> Unit): Boolean {
        if (delegate != null) return borrowing { it.useSimulatorLane(panelId, use) }
        synchronized(stateLock) { check(!closed && connected) }
        val lane = transport.openSimulator(panelId) ?: return false
        try {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) { check(!closed && connected) }
            use(lane)
            return true
        } finally { lane.close() }
    }

    internal suspend fun useArtifactLane(resource: String, use: suspend (ArtifactLane) -> Unit): Boolean {
        if (delegate != null) return borrowing { it.useArtifactLane(resource, use) }
        synchronized(stateLock) { check(!closed && connected) }
        val lane = transport.openArtifact(resource) ?: return false
        try {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) { check(!closed && connected) }
            use(lane)
            return true
        } finally { lane.close() }
    }

    /** The consuming lease owns this coroutine and cancels it when released. */
    internal suspend fun useTerminalInputLane(surfaceId: String, use: suspend (TerminalInputLane) -> Unit): Boolean {
        checkTerminalTraffic(surfaceId)
        if (delegate != null) return borrowing { it.useTerminalInputLane(surfaceId) { lane ->
            use(guardedInputLane(surfaceId, lane))
        } }
        synchronized(stateLock) { check(!closed && connected) }
        val lane = transport.openTerminalInput(surfaceId)?.let { guardedInputLane(surfaceId, it) } ?: return false
        try {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) { check(!closed && connected) }
            use(lane)
            return true
        } finally { lane.close() }
    }

    internal suspend fun useTerminalOutputLane(surfaceId: String, cursor: ULong?, use: suspend (TerminalOutputLane) -> Unit): Boolean {
        checkTerminalTraffic(surfaceId)
        if (delegate != null) return borrowing { it.useTerminalOutputLane(surfaceId, cursor) { lane ->
            use(guardedOutputLane(surfaceId, lane))
        } }
        synchronized(stateLock) { check(!closed && connected) }
        val lane = transport.openTerminalOutput(surfaceId, cursor)?.let { guardedOutputLane(surfaceId, it) } ?: return false
        try {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) { check(!closed && connected) }
            use(lane)
            return true
        } finally { lane.close() }
    }

    private fun guardedInputLane(surface: String, lane: TerminalInputLane): TerminalInputLane =
        object : TerminalInputLane by lane {
            override suspend fun send(text: String) { checkTerminalTraffic(surface); lane.send(text) }
            override suspend fun sendIdentified(text: String, delivery: TerminalInputDelivery) {
                checkTerminalTraffic(surface); lane.sendIdentified(text, delivery)
            }
        }
    private fun guardedOutputLane(surface: String, lane: TerminalOutputLane): TerminalOutputLane =
        object : TerminalOutputLane by lane {
            override suspend fun receive(): TerminalLaneProtocol.Output? {
                checkTerminalTraffic(surface)
                return lane.receive().also { checkTerminalTraffic(surface) }
            }
            override suspend fun send(text: String) { checkTerminalTraffic(surface); lane.send(text) }
            override suspend fun sendIdentified(text: String, delivery: TerminalInputDelivery) {
                checkTerminalTraffic(surface); lane.sendIdentified(text, delivery)
            }
        }

    internal val terminalParticipantId: String get() = "mobile:$clientId"

    internal suspend fun changeTerminalSizing(workspace: String, surface: String, action: TerminalSizingAction,
        viewport: TerminalViewport?, generation: Long, isCurrent: () -> Boolean): JSONObject {
        val base = JSONObject().put("workspace_id", workspace).put("surface_id", surface).put("client_id", clientId)
        fun admitted() { check(isCurrent()) { "Terminal selection changed. Open its size controls again." }; checkTerminalTraffic(surface) }
        admitted()
        suspend fun send(method: String, params: JSONObject): JSONObject {
            val result = requestAdmitted(method, params, 15_000, ::admitted)
            admitted()
            return result
        }
        return when (action) {
            is TerminalSizingAction.Policy -> {
                val policy = action.policy
                require(policy.priority.size <= 1024 && policy.priority.all { it.isNotBlank() && it.length <= 1024 })
                if (policy.mode == TerminalSizeMode.FIXED) require(policy.fixed != null)
                policy.fixed?.let { require(it.columns in 20..300 && it.rows in 5..120) }
                send("mobile.terminal.size_policy.set", base.put("policy", policy.wire()))
            }
            is TerminalSizingAction.Counts -> {
                val size = checkNotNull(viewport) { "Wait for this phone's terminal viewport." }
                require(generation >= 0)
                send("mobile.terminal.viewport", base.put("viewport_columns", size.columns).put("viewport_rows", size.rows)
                    .put("viewport_generation", generation).put("counts_override", action.counts ?: JSONObject.NULL).withTerminalDevice())
            }
            is TerminalSizingAction.Disconnect -> {
                require(action.ids.isNotEmpty() && action.ids.size <= 1024 && action.ids.distinct().size == action.ids.size &&
                    action.ids.all { it.isNotBlank() && it.length <= 4096 && it != terminalParticipantId })
                var result = JSONObject()
                for (id in action.ids) result = send("mobile.terminal.participant.disconnect",
                    JSONObject(base.toString()).put("participant_id", id))
                result
            }
        }
    }

    internal suspend fun reattachTerminal(workspace: String, surface: String, viewer: Boolean,
        viewport: TerminalViewport?): JSONObject = request("mobile.terminal.reattach", JSONObject()
        .put("workspace_id", workspace).put("surface_id", surface).put("client_id", clientId)
        .put("as_viewer", viewer).withTerminalDevice().apply {
            viewport?.let { put("viewport_columns", it.columns); put("viewport_rows", it.rows) }
        })

    suspend fun input(workspaceId: String, surfaceId: String, text: String): JSONObject =
        terminalOperation(TerminalInputOperation.Text(workspaceId, surfaceId, text))

    internal suspend fun input(workspaceId: String, surfaceId: String, text: String, delivery: TerminalInputDelivery?): JSONObject =
        request("terminal.input", JSONObject()
            .put("workspace_id", workspaceId)
            .put("surface_id", surfaceId)
            .put("client_id", clientId)
            .put("text", text).withInputDelivery(delivery))

    /** cmux's literal multiline paste, optionally followed by a Return key event. */
    suspend fun paste(workspaceId: String, surfaceId: String, text: String, submit: Boolean): JSONObject =
        terminalOperation(TerminalInputOperation.Paste(workspaceId, surfaceId, text, submit))

    internal suspend fun paste(workspaceId: String, surfaceId: String, text: String, submit: Boolean,
        delivery: TerminalInputDelivery?): JSONObject =
        request("terminal.paste", JSONObject()
            .put("workspace_id", workspaceId).put("surface_id", surfaceId)
            .put("client_id", clientId).put("text", text)
            .put("submit_key", if (submit) "return" else "none").withInputDelivery(delivery))

    suspend fun pasteImage(workspaceId: String, surfaceId: String, bytes: ByteArray, format: String): JSONObject =
        terminalOperation(TerminalInputOperation.Image(workspaceId, surfaceId, bytes, format))

    internal suspend fun pasteImage(workspaceId: String, surfaceId: String, bytes: ByteArray, format: String,
        delivery: TerminalInputDelivery?): JSONObject =
        request("terminal.paste_image", JSONObject()
            .put("workspace_id", workspaceId).put("surface_id", surfaceId).put("client_id", clientId)
            .put("image_base64", java.util.Base64.getEncoder().encodeToString(bytes))
            .put("image_format", format).withInputDelivery(delivery))

    /** Stable attachment IDs make re-upload after an explicit retry idempotent on the Mac. */
    suspend fun uploadAttachment(attachment: ComposerAttachment, bytes: ByteArray, checkCurrent: () -> Unit, operationId: String = attachment.id): String {
        require(bytes.size == attachment.size && bytes.size in 0..ComposerAttachment.FILE_LIMIT)
        UUID.fromString(operationId)
        var path: String? = null
        var offset = 0
        do {
            checkCurrent()
            val end = minOf(offset + 3 * 1024 * 1024, bytes.size)
            val last = end == bytes.size
            val result = request("mobile.task.attachment.upload", JSONObject()
                .put("operation_id", operationId).put("upload_id", attachment.id)
                .put("file_name", attachment.name).put("total_bytes", bytes.size)
                .put("offset", offset).put("last", last)
                .put("data_b64", java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end))))
            checkCurrent()
            if (last) {
                path = result.optString("path")
                require(path.startsWith('/') && '\u0000' !in path) { "Invalid attachment path from Mac" }
            }
            offset = end
        } while (offset < bytes.size)
        return requireNotNull(path)
    }

    private val clientId = UUID.randomUUID().toString()

    /** Optional events cannot settle control replies or retire a healthy control connection. */
    private fun prepareIndependentEvents(streamId: String? = null) {
        if (delegate != null) {
            if (!isClosed) delegate.prepareIndependentEvents(streamId)
            return
        }
        val events = transport.independentEvents ?: return
        val reader = synchronized(stateLock) {
            if (closed || !connected) return
            val stream = streamId?.trim()?.takeIf(String::isNotEmpty)
            // Reasserting a subscription is a control liveness probe, not a new sidecar attempt.
            if (stream != null && !independentEventSubscriptions.add(stream)) return
            if (independentEventReader?.isCompleted == false) return
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    events.collect { payload ->
                        val envelope = try { MobileJson.objectValue(payload.toString(Charsets.UTF_8)) }
                        catch (_: Exception) { return@collect }
                        if (envelope.optString("kind") == "event") dispatch(envelope)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    // The reader's cleanup stops its lanes; the host can fall back to control.
                    // A later new subscription may prepare a new reader. Connection closure
                    // remains authoritative through disconnections/control read or write.
                }
            }.also { independentEventReader = it }
        }
        reader.invokeOnCompletion {
            synchronized(stateLock) { if (independentEventReader === reader) independentEventReader = null }
        }
        reader.start()
    }

    private fun forgetIndependentSubscription(streamId: String) {
        if (delegate != null) delegate.forgetIndependentSubscription(streamId)
        else synchronized(stateLock) { independentEventSubscriptions.remove(streamId.trim()) }
    }

    suspend fun subscribe(topics: List<String>, streamId: String = UUID.randomUUID().toString(),
                          screenAnchor: Boolean = false): JSONObject {
        prepareIndependentEvents(streamId)
        val params = JSONObject().put("client_id", clientId).put("stream_id", streamId)
            .put("topics", org.json.JSONArray(topics))
        if (transport.surfaceEventLanes) params.put("surface_event_lanes", "v1")
        if (screenAnchor && "terminal.render_grid" in topics) params.put("render_grid_anchor", "screen")
        return request("mobile.events.subscribe", params)
    }

    suspend fun unsubscribe(streamId: String): JSONObject = try {
        request("mobile.events.unsubscribe", JSONObject().put("stream_id", streamId))
    } finally { forgetIndependentSubscription(streamId) }

    private suspend fun readLoop() {
        val decoder = MobileFrameDecoder()
        var failure: Throwable = EOFException("cmux disconnected")
        try {
            while (true) {
                val bytes = transport.read() ?: break
                for (frame in decoder.feed(bytes)) dispatch(MobileJson.objectValue(frame.toString(Charsets.UTF_8)))
            }
        } catch (error: Throwable) { failure = error }
        finally { failConnection(failure) }
    }

    private fun failConnection(failure: Throwable, notify: Boolean = true) {
        val operations = synchronized(stateLock) {
            if (closed) return
            closed = true; connected = false
            independentEventSubscriptions.clear()
            pending.values.forEach { it.answer.completeExceptionally(failure) }
            pending.clear()
            leaseOperations.toList().also { leaseOperations.clear() }
        }
        try { transport.close() }
        finally {
            operations.forEach { it.cancel(CancellationException("Connection closed", failure)) }
            if (notify) disconnectedMutable.tryEmit(failure)
            scope.cancel()
        }
    }

    private fun dispatch(envelope: JSONObject) = synchronized(stateLock) {
        if (closed) return@synchronized
        inboundDelivery++
        silentTimeouts = 0
        if (envelope.optString("kind") == "event") {
            val topic = envelope.optString("topic")
            if (topic.isNotBlank()) {
                val event = Event(topic, envelope.optJSONObject("payload") ?: JSONObject(),
                    envelope.optString("stream_id").takeIf { it.isNotBlank() }, ++eventDeliverySequence)
                if (topic in TerminalSizingTraffic.topics) terminalEventObservers.forEach { it(event) }
                eventsMutable.tryEmit(event)
            }
            return@synchronized
        }
        val id = envelope.optString("id")
        val waiter = pending[id] ?: return@synchronized
        if (envelope.optBoolean("ok")) {
            waiter.answer.complete(envelope.optJSONObject("result") ?: JSONObject())
        } else {
            val error = envelope.optJSONObject("error")
            val message = error?.optString("message")
                .orEmpty().ifBlank { "cmux RPC request failed" }
            waiter.answer.completeExceptionally(MobileRpcException(error?.opt("code") as? String, message))
        }
    }

    override fun close() {
        if (delegate == null) {
            failConnection(EOFException("cmux connection closed"), notify = false)
            return
        }
        val operations = synchronized(stateLock) {
            if (closed) return
            closed = true
            leaseOperations.toList().also { leaseOperations.clear() }
        }
        try { operations.forEach { it.cancel(CancellationException("Connection lease closed")) } }
        finally { try { releaseLease?.invoke() } finally { scope.cancel() } }
    }
}
