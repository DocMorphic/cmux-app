package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** One provider-owned browser view. Every restart re-resolves stable content;
 * frame sequences and pointer authority belong only to that attachment. */
internal class SshCmuxBrowserStream(
    val selection: SshCmuxBrowserSelection,
    private val control: SshCmuxControl,
    private val scope: CoroutineScope,
    private val resolve: suspend () -> SshCmuxTab,
    private val admitted: () -> Boolean
) : BrowserStreamClient, AutoCloseable {
    val panelId = selection.panelId
    private val updates = MutableSharedFlow<BrowserStreamClient.Event>(extraBufferCapacity = 4)
    private val failures = MutableSharedFlow<Throwable>(replay = 1)
    override val events = updates.asSharedFlow()
    override val disconnected = failures.asSharedFlow()
    override val clearsFrameOnRestart = true
    override val reportsHistory = false
    private val mutablePointerReady = MutableStateFlow(false)
    override val pointerReady = mutablePointerReady.asStateFlow()
    private fun refreshPointerReady() {
        mutablePointerReady.value = !closed && attachment?.pointer?.token != null
    }
    override val pageDescription = "SSH browser page"
    private val operations = Mutex()
    private var attachment: SshCmuxBrowserAttachment? = null
    private var streamId: String? = null
    // Shared renderer IDs are local signed integers. Remote UInt64 sequence and
    // pointer authority stay exact; an old presentation can never alias a restart.
    private var renderSequence = 0L
    private val renderedFrames = sortedMapOf<Long, ULong>()
    private fun image(frame: SshCmuxBrowserFrame): JSONObject {
        check(renderSequence < Long.MAX_VALUE) { "Browser presentation sequence exhausted" }
        val sequence = ++renderSequence
        renderedFrames[sequence] = frame.sequence
        while (renderedFrames.size > 8) renderedFrames.remove(renderedFrames.firstKey())
        return JSONObject().put("format", "png").put("seq", sequence)
            .put("page_width", frame.width).put("page_height", frame.height)
            .put("pixel_width", frame.imageWidth).put("pixel_height", frame.imageHeight).put("data_b64", frame.png)
    }
    private var cell = 1 to 1
    private var appliedGrid: Pair<Int, Int>? = null
    private var descriptor = JSONObject()
    var closed = false; private set
    private fun guard(panel: String) { check(panel == panelId && !closed && admitted() && !control.closed) { "SSH browser connection ended" } }
    private fun view(panel: String): SshCmuxBrowserAttachment {
        guard(panel); return checkNotNull(attachment) { "SSH browser is not attached" }
    }
    fun validate(tree: SshCmuxTree) {
        val row = selection.resolve(selection.session, tree)?.second
        if (row == null || attachment?.let { it.surface != row.surface } == true) close()
    }
    private fun emit(topic: String, value: JSONObject, stream: String) {
        if (closed || streamId != stream) return
        if (!updates.tryEmit(BrowserStreamClient.Event(topic, value.put("panel_id", panelId), stream))) {
            fail(IllegalStateException("Browser frames arrived faster than they could be displayed. Reopen the browser."))
        }
    }
    private fun receive(event: SshCmuxBrowserEvent, stream: String) {
        if (closed || streamId != stream) return
        when (event) {
            is SshCmuxBrowserEvent.State -> {
                event.value.frame?.let { emit("browser.frame", image(it), stream) }
                val state = event.value
                descriptor = JSONObject().put("url", state.url).put("title", state.title)
                    .put("is_loading", state.status == SshCmuxBrowserStatus.STARTING)
                    .put("stream_error", when {
                        state.status == SshCmuxBrowserStatus.FAILED -> state.error?.ifBlank { null } ?: "Remote browser failed"
                        state.stalled -> "Remote browser frames have stalled"
                        else -> JSONObject.NULL
                    })
                emit("browser.state", descriptor, stream)
            }
            is SshCmuxBrowserEvent.Frame -> emit("browser.frame", image(event.value), stream)
            is SshCmuxBrowserEvent.Ended -> fail(IllegalStateException("SSH browser stream ended. Reopen the browser to reconnect."))
        }
        refreshPointerReady()
    }
    override suspend fun start(panel: String, stream: String, width: Int, height: Int, scale: Double): JSONObject = operations.withLock {
        guard(panel); check(attachment == null && streamId == null) { "SSH browser already attached" }
        val row = resolve(); guard(panel)
        cell = control.browserCellPixels(); guard(panel)
        val size = grid(width, height, cell)
        streamId = stream; descriptor = JSONObject().put("url", row.url ?: "").put("title", row.title)
        try {
            // Initial frames arrive before attach's reply. The renderer's collector
            // is running already; keep the stream identity in this callback.
            attachment = control.attachBrowser(row.surface, size.first, size.second) { receive(it, stream) }
            guard(panel); appliedGrid = size; refreshPointerReady()
            descriptor
        } catch (failure: Exception) {
            withContext(NonCancellable) { detach() }
            throw failure
        }
    }
    override suspend fun stop(panel: String, stream: String) = operations.withLock {
        require(panel == panelId)
        // A late old composition cannot detach a newer stream.
        if (streamId == stream) detach()
    }
    private suspend fun detach() {
        mutablePointerReady.value = false
        streamId = null; appliedGrid = null; renderedFrames.clear()
        val current = attachment; attachment = null
        if (current != null) withTimeout(2_000) { control.detach(current) }
    }
    override suspend fun input(panel: String, input: BrowserInput) = operations.withLock {
        val current = view(panel)
        // Scroll phase markers carry zero delta and need no remote command.
        if (input is BrowserInput.Scroll && input.dy == 0.0) return@withLock
        // False means definitely not sent (e.g. pointer authority was revoked
        // by resize). Like iOS, discard that gesture without pausing later input.
        // An uncertain delivery still throws and pauses BrowserInputQueue.
        try { control.browserInput(current, input); Unit }
        finally { refreshPointerReady() }
    }
    override suspend fun viewport(panel: String, width: Int, height: Int, scale: Double) = operations.withLock {
        val current = view(panel); val size = grid(width, height, cell)
        if (size != appliedGrid) {
            // The host may apply this resize even if its caller is cancelled.
            appliedGrid = null; mutablePointerReady.value = false
            try {
                check(control.resizeBrowser(current, size.first, size.second)) { "Remote browser did not accept the viewport" }
                appliedGrid = size
            } finally { refreshPointerReady() }
        }
    }
    override suspend fun displayed(panel: String, sequence: Long) = operations.withLock {
        val current = view(panel)
        renderedFrames.keys.filter { it < sequence }.forEach { renderedFrames.remove(it) }
        val remoteSequence = renderedFrames.remove(sequence) ?: return@withLock
        try { control.browserFrameDisplayed(current, remoteSequence); Unit }
        finally { refreshPointerReady() }
    }
    override suspend fun respondDialog(panel: String, id: String, button: String, text: String?) {
        guard(panel); error("This SSH browser does not expose native dialog responses")
    }
    private fun fail(failure: Throwable) {
        if (closed) return
        retire(failure)
    }
    override fun close() = retire(IllegalStateException("SSH browser connection ended"))
    private fun retire(failure: Throwable) {
        if (closed) return
        closed = true; mutablePointerReady.value = false
        failures.tryEmit(failure)
        attachment?.pointer?.revoke()
        scope.launch { operations.withLock { runCatching { detach() } } }
    }
    companion object {
        fun grid(width: Int, height: Int, cell: Pair<Int, Int>): Pair<Int, Int> {
            require(width in 1..4096 && height in 1..4096)
            return (width / maxOf(1, cell.first)).coerceIn(1, 1000) to (height / maxOf(1, cell.second)).coerceIn(1, 1000)
        }
    }
}
