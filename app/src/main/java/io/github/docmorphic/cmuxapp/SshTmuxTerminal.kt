package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A pane is sized by tmux's layout; the phone only requests a client viewport.
 * tmux already answers its application's terminal queries, so this mirror must
 * not send Ghostty query replies back as keyboard input. */
internal class SshTmuxTerminal(override val id: String, val workspace: SshTmuxWorkspace,
    pane: SshTmuxPaneRow, private val control: SshTmuxControl, lifetime: CoroutineScope,
    private val admitted: () -> Boolean,
) : SshTerminal {
    var pane = pane; private set
    override val title get() = pane.title
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(lifetime.coroutineContext + job + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(SshShellState())
    override val state = mutable.asStateFlow()
    override var display = GhosttyVtTerminal(pane.columns, pane.rows); private set
    private var grid = pane.columns to pane.rows
    private var metrics = 1 to 1
    private var disposed = false
    private var ended = false
    private val input = Channel<ByteArray>(256)
    private var pending = 0
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { end(null) } }
        try { control.attach(pane.id, ::event) }
        catch (failure: Exception) { close(); throw failure }
        scope.launch {
            try {
                for (bytes in input) {
                    try { check(allowed()); control.write(pane.id, bytes) }
                    finally { pending -= bytes.size; bytes.fill(0) }
                }
            } catch (failure: Exception) {
                if (failure !is CancellationException) end("Input delivery was not confirmed. Reopen the pane to check its state; input was not replayed.")
            }
        }
    }
    private fun allowed() = !disposed && !ended && job.isActive && admitted() && !control.isClosed
    fun updatePane(row: SshTmuxPaneRow) {
        require(row.id == pane.id)
        if (row != pane && !disposed) {
            pane = row
            mutable.value = mutable.value.copy(revision = mutable.value.revision + 1)
        }
    }
    private fun event(event: TmuxPaneEvent) {
        if (disposed || ended || !admitted()) return
        try {
            when (event) {
                is TmuxPaneEvent.Grid -> {
                    require(event.columns in 1..1000 && event.rows in 1..1000)
                    grid = event.columns to event.rows
                    display.resize(grid.first, grid.second, metrics.first, metrics.second)
                }
                is TmuxPaneEvent.Snapshot -> {
                    val next = GhosttyVtTerminal(grid.first, grid.second)
                    try { next.resize(grid.first, grid.second, metrics.first, metrics.second); next.append(event.bytes) }
                    catch (failure: Exception) { next.close(); throw failure }
                    val old = display; display = next; old.close()
                    mutable.value = mutable.value.copy(phase = SshShellPhase.RUNNING)
                }
                is TmuxPaneEvent.Output -> display.append(event.bytes)
                TmuxPaneEvent.Ended -> end("tmux pane or connection ended")
            }
            mutable.value = mutable.value.copy(revision = mutable.value.revision + 1)
        } catch (failure: Exception) { end(failure.message ?: "Could not render tmux pane") }
    }
    override fun send(text: String, paste: Boolean): Boolean {
        if (!allowed() || state.value.phase != SshShellPhase.RUNNING) return false
        val bytes = (if (paste) TerminalKeyEncoding.paste(text, display.bracketedPaste) else text).toByteArray(Charsets.UTF_8)
        if (bytes.size > 256 * 1024 - pending) { bytes.fill(0); end("Input queue was full. Input was not replayed."); return false }
        pending += bytes.size
        if (input.trySend(bytes).isSuccess) return true
        pending -= bytes.size; bytes.fill(0); end("Input queue closed"); return false
    }
    override fun resize(columns: Int, rows: Int, cells: TerminalCellMetrics) {
        if (!allowed()) return
        metrics = cells.widthPx.toInt().coerceIn(1, 4096) to cells.heightPx.toInt().coerceIn(1, 4096)
        try {
            display.resize(grid.first, grid.second, metrics.first, metrics.second)
            control.resize(columns.coerceIn(2, 1000), rows.coerceIn(2, 1000))
            mutable.value = mutable.value.copy(revision = mutable.value.revision + 1)
        } catch (failure: Exception) { end(failure.message ?: "tmux resize failed") }
    }
    private fun end(error: String?) {
        if (ended) return
        ended = true; control.detach(pane.id); input.close()
        while (true) { val bytes = input.tryReceive().getOrNull() ?: break; bytes.fill(0) }
        mutable.value = mutable.value.copy(phase = SshShellPhase.ENDED, error = error)
        job.cancel()
    }
    override fun close() { if (!disposed) { end(null); disposed = true; display.close() } }
}

internal class SshTmuxHosts(private val connections: SshConnections<SshTransport>, lifetime: CoroutineScope,
    private val admitted: () -> Boolean,
) : AutoCloseable {
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(lifetime.coroutineContext + job + Dispatchers.Main.immediate)
    private val hosts = mutableMapOf<java.util.UUID, SshTmuxHost>()
    init { scope.launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { closeAll() } } }
    suspend fun open(id: java.util.UUID): SshTmuxHost = withContext(Dispatchers.Main.immediate) {
        check(job.isActive && admitted()) { "Sign in to open tmux workspaces" }
        adopt(id, connections.open(id))
    }
    suspend fun autoOpen(id: java.util.UUID): SshTmuxHost? = withContext(Dispatchers.Main.immediate) {
        check(job.isActive && admitted()) { "Sign in to open tmux workspaces" }
        connections.autoConnect(id)?.let { adopt(id, it) }
    }
    private fun adopt(id: java.util.UUID, connection: SshTransport): SshTmuxHost {
        check(job.isActive && admitted() && connection.isConnected)
        hosts[id]?.takeIf { it.connection === connection }?.let { return it }
        hosts.remove(id)?.close()
        return SshTmuxHost(id, connection, scope, { job.isActive && admitted() }).also { hosts[id] = it }
    }
    private fun closeAll() { hosts.values.toList().forEach { it.close() }; hosts.clear() }
    override fun close() { job.cancel(); scope.launch(NonCancellable) { closeAll() } }
}
