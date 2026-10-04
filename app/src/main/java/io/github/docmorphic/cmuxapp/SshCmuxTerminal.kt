package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal object SshCmuxColors {
    fun replay(value: JSONObject): ByteArray = buildString {
        fun color(key: String, osc: Int, reset: Int) {
            val raw = value.opt(key)
            if (raw == null || raw === JSONObject.NULL) append("\u001b]$reset\u0007")
            else if (raw is String && Regex("#[0-9a-fA-F]{6}").matches(raw)) append("\u001b]$osc;$raw\u0007")
        }
        color("fg", 10, 110); color("bg", 11, 111); color("cursor", 12, 112)
        color("selection_bg", 17, 117); color("selection_fg", 19, 119)
        append("\u001b]104\u0007")
        value.optJSONObject("palette")?.let { palette ->
            for (key in palette.keys().asSequence().toList().sorted()) {
                val index = key.toIntOrNull(); val color = palette.opt(key)
                if (index != null && index in 0..255 && color is String && Regex("#[0-9a-fA-F]{6}").matches(color))
                    append("\u001b]4;$index;$color\u0007")
            }
        }
        val base = when (value.opt("cursor_style")) { "bar" -> 5; "underline" -> 3; else -> 1 }
        append("\u001b[${base + if (value.opt("cursor_blink") == false) 1 else 0} q")
    }.toByteArray(Charsets.UTF_8)
}

/** A silent Ghostty mirror: cmux-tui answers application terminal queries.
 * Every snapshot/resize creates a fresh VT, followed by server colors/cursor.
 * Input is ordered and bounded; geometry hints are conflated to the latest UI. */
internal class SshCmuxTerminal private constructor(override val id: String, val selection: SshCmuxSelection,
    tab: SshCmuxTab, private val control: SshCmuxControl, private val owner: CoroutineScope,
    private val admitted: () -> Boolean, override val composer: SshComposerPool.Draft?,
    private val uploadImage: SshImageUpload?) : SshTerminal {
    override val imageUpload: SshImageUpload? = uploadImage?.let { upload -> { bytes, format ->
        check(allowed()); val path = upload(bytes, format); check(allowed()); path
    } }
    var tab = tab; private set
    override val title get() = tab.name?.takeIf { it.isNotBlank() } ?: tab.title.ifBlank { "Terminal" }
    private val job = SupervisorJob(checkNotNull(owner.coroutineContext[Job]))
    private val scope = CoroutineScope(owner.coroutineContext + job)
    private val mutable = MutableStateFlow(SshShellState())
    override val state = mutable.asStateFlow()
    override val bells = TerminalBellSignal()
    override var display = GhosttyVtTerminal(80, 24); private set
    private var attachment: SshCmuxAttachment? = null
    private var detaching: Job? = null
    private var ended = false
    private var disposed = false
    private var pending = 0
    private var columns = 80
    private var rows = 24
    private var metrics = 1 to 1
    private var visible = false
    private val input = Channel<ByteArray>(256)
    private val geometry = Channel<Unit>(Channel.CONFLATED)
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { end(null) } }
        scope.launch {
            try { for (bytes in input) {
                try { check(allowed()); control.send(checkNotNull(attachment), bytes) }
                finally { pending -= bytes.size; bytes.fill(0) }
            } } catch (failure: Exception) {
                if (failure !is CancellationException) end("Input delivery was not confirmed. Reopen the terminal to check its state; input was not replayed.")
            }
        }
        scope.launch {
            try { for (ignored in geometry) {
                check(allowed()); val view = attachment ?: continue
                if (visible) control.claimGeometry(view, columns, rows)
                else if (!control.releaseGeometry(view)) end("Reopen this terminal to resume its view")
            } } catch (failure: Exception) { if (failure !is CancellationException) end(failure.message ?: "Terminal resize failed") }
        }
    }
    private fun allowed() = !disposed && !ended && job.isActive && admitted() && !control.closed
    override suspend fun currentDirectory(): String? {
        check(allowed())
        val info = control.request("process-info", JSONObject().put("surface", tab.surface))
        check(allowed())
        return listOf(info.opt("foreground_cwd"), info.opt("cwd")).filterIsInstance<String>().firstOrNull { it.startsWith('/') }
    }
    fun update(value: SshCmuxTab) {
        require(value.surface == tab.surface)
        if (!disposed && value != tab) { tab = value; changed() }
    }
    private fun changed() { mutable.value = mutable.value.copy(revision = mutable.value.revision + 1) }
    private fun feed(target: GhosttyVtTerminal, bytes: ByteArray) {
        // A replay may contain megabytes of history. JNI bounds individual
        // calls; the VT parser retains partial UTF-8/escape state across chunks.
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + 512 * 1024)
            target.append(bytes.copyOfRange(offset, end)); offset = end
        }
    }
    private fun event(event: SshCmuxEvent) {
        if (!allowed()) return
        try {
            when (event) {
                is SshCmuxEvent.Snapshot -> {
                    val next = GhosttyVtTerminal(event.columns, event.rows)
                    try { next.resize(event.columns, event.rows, metrics.first, metrics.second); feed(next, event.bytes); next.takeBell() }
                    catch (failure: Exception) { next.close(); throw failure }
                    val old = display; display = next; old.close()
                    mutable.value = mutable.value.copy(phase = SshShellPhase.RUNNING)
                }
                is SshCmuxEvent.Output -> { feed(display, event.bytes); if (display.takeBell()) bells.ring() }
                is SshCmuxEvent.Colors -> display.append(SshCmuxColors.replay(event.values))
                is SshCmuxEvent.Ended -> end(if (event.disconnected) "Connection ended. Reconnect to resume the terminal." else "Terminal view ended")
            }
            changed()
        } catch (failure: Exception) { end(failure.message ?: "Could not render terminal") }
    }
    override fun send(text: String, paste: Boolean): Boolean {
        return sendBytes((if (paste) TerminalKeyEncoding.paste(text, display.bracketedPaste) else text).toByteArray(Charsets.UTF_8))
    }
    override fun sendBytes(bytes: ByteArray): Boolean {
        if (!allowed() || attachment == null || state.value.phase != SshShellPhase.RUNNING) return false
        if (bytes.size > 256 * 1024 - pending) { end("Input queue was full. Input was not replayed."); return false }
        val owned = bytes.copyOf()
        pending += owned.size
        if (input.trySend(owned).isSuccess) return true
        pending -= owned.size; owned.fill(0); end("Input queue closed"); return false
    }
    override fun resize(columns: Int, rows: Int, cells: TerminalCellMetrics) {
        if (!allowed()) return
        this.columns = columns.coerceIn(2, 1000); this.rows = rows.coerceIn(2, 1000)
        metrics = cells.widthPx.toInt().coerceIn(1, 4096) to cells.heightPx.toInt().coerceIn(1, 4096)
        display.setCellMetrics(cells); changed(); geometry.trySend(Unit)
    }
    override fun visible(visible: Boolean) {
        if (!allowed() || this.visible == visible) return
        this.visible = visible; geometry.trySend(Unit)
    }
    private fun end(error: String?) {
        if (ended) return
        ended = true; input.close(); geometry.close()
        while (true) { val bytes = input.tryReceive().getOrNull() ?: break; bytes.fill(0) }
        mutable.value = mutable.value.copy(phase = SshShellPhase.ENDED, error = error)
        val view = attachment; attachment = null
        job.cancel()
        if (view != null && !control.closed) detaching = owner.launch {
            // The detach request/reply is a fence before this surface may be
            // attached again. Failure closes the relay in the control layer.
            runCatching { control.detach(view) }
        }
    }
    suspend fun retire() { close(); detaching?.join() }
    override fun close() { if (!disposed) { end(null); disposed = true; display.close() } }
    companion object {
        suspend fun open(id: String, selection: SshCmuxSelection, tab: SshCmuxTab, control: SshCmuxControl,
            owner: CoroutineScope, composer: SshComposerPool.Draft? = null,
            imageUpload: SshImageUpload? = null, admitted: () -> Boolean): SshCmuxTerminal {
            val terminal = SshCmuxTerminal(id, selection, tab, control, owner, admitted, composer, imageUpload)
            try {
                val view = control.attach(tab.surface, 80, 24, terminal::event)
                terminal.attachment = view
                check(terminal.allowed())
                // A retained opening whose screen was canceled must not keep
                // exclusive geometry. The visible screen explicitly claims it.
                control.releaseGeometry(view)
                return terminal
            } catch (failure: Exception) { terminal.close(); throw failure }
        }
    }
}
