package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque

internal interface SshTmuxPipe : AutoCloseable {
    val output: Flow<ByteArray>
    suspend fun write(bytes: ByteArray)
}
internal sealed interface TmuxPaneEvent {
    data class Grid(val columns: Int, val rows: Int) : TmuxPaneEvent
    data class Snapshot(val bytes: ByteArray) : TmuxPaneEvent
    data class Output(val bytes: ByteArray) : TmuxPaneEvent
    data object Ended : TmuxPaneEvent
}

/** One grouped-session control client. Its owner and all public calls use the
 * same dispatcher (Main in the app). Pane IDs are server IDs, never list indices.
 * A timeout, write failure or protocol overflow retires this pipe: uncertain
 * commands are not retried and late replies cannot answer a subsequent command. */
internal class SshTmuxControl(val groupedSession: String, private val pipe: SshTmuxPipe,
    lifetime: CoroutineScope, private val commandTimeoutMillis: Long = 30000,
) : AutoCloseable {
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(lifetime.coroutineContext + job)
    private val parser = SshTmuxParser()
    private data class Reply(val lines: List<ByteArray>, val error: Boolean)
    private class Pending(val receive: (Reply) -> Unit) { var timeout: Job? = null }
    private class Pane(val window: Int, val target: String, val events: (TmuxPaneEvent) -> Unit) {
        val titles = SshTmuxTitleFilter()
        var stage = 0 // before capture, waiting for state, live
        var snapshot: ByteArray? = null
        val buffered = ByteArrayOutputStream()
        var grid: Pair<Int, Int>? = null
    }
    private val pending = ArrayDeque<Pending>()
    private val writes = Channel<ByteArray>(256)
    private var queuedBytes = 0
    private val panes = mutableMapOf<Int, Pane>()
    private val layouts = mutableMapOf<Int, List<TmuxLeaf>>()
    private var size: Pair<Int, Int>? = null
    private var topology: Job? = null
    private var layoutRefresh: Job? = null
    private var layoutRefreshAgain = false
    private var receivedExit = false
    var onTopologyChange: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var isClosed = false; private set
    val attachedPaneCount get() = panes.size
    init {
        require(commandTimeoutMillis > 0)
        // Enter the finally block even if the owner is canceled before the
        // dispatcher first runs its queued work.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { finish() } }
        scope.launch {
            try { for (bytes in writes) { queuedBytes -= bytes.size; pipe.write(bytes) } }
            catch (_: Exception) { finish() }
        }
        scope.launch {
            try { pipe.output.collect { bytes -> parser.feed(bytes).forEach(::handle) } }
            catch (_: Exception) { /* finish retires all pending commands and panes. */ }
            finally { finish() }
        }
    }
    private fun enqueue(commands: List<Pair<String, (Reply) -> Unit>>) {
        check(!isClosed && job.isActive) { "tmux control connection ended" }
        require(commands.all { (line, _) -> line.isNotEmpty() && line.none { it == '\n' || it == '\r' || it == '\u0000' } })
        val payload = commands.joinToString("", transform = { it.first + "\n" }).toByteArray(Charsets.UTF_8)
        if (pending.size + commands.size > 512 || payload.size > 256 * 1024 - queuedBytes) {
            finish(); throw IOException("tmux command queue exceeded its limit")
        }
        for ((_, handler) in commands) {
            val item = Pending(handler); pending.addLast(item)
            item.timeout = scope.launch { delay(commandTimeoutMillis); finish() }
        }
        queuedBytes += payload.size
        if (!writes.trySend(payload).isSuccess) { finish(); throw IOException("tmux writer unavailable") }
    }
    suspend fun command(line: String): List<ByteArray> {
        val result = CompletableDeferred<List<ByteArray>>()
        enqueue(listOf(line to { reply ->
            if (reply.error) result.completeExceptionally(IOException("tmux command failed or its delivery was not confirmed"))
            else result.complete(reply.lines)
        }))
        // Caller cancellation deliberately leaves its reply slot in place.
        return result.await()
    }
    suspend fun initialize() {
        val rows = command("list-panes -s -F '#{window_id} #{pane_id} #{pane_width}x#{pane_height}'")
        val next = mutableMapOf<Int, List<TmuxLeaf>>()
        for (row in rows) {
            val fields = row.toString(Charsets.UTF_8).split(' ')
            check(fields.size == 3) { "Invalid tmux pane geometry" }
            val window = checkNotNull(SshTmuxParser.id(fields[0], '@'))
            val pane = checkNotNull(SshTmuxParser.id(fields[1], '%'))
            val dimensions = fields[2].split('x').map { it.toInt() }
            check(dimensions.size == 2 && dimensions.all { it in 1..65535 })
            next[window] = next[window].orEmpty() + TmuxLeaf(pane, dimensions[0], dimensions[1], 0, 0)
        }
        layouts.clear(); layouts.putAll(next); updateGrids()
    }
    private fun refreshLayouts() {
        if (layoutRefresh?.isActive == true) { layoutRefreshAgain = true; return }
        layoutRefresh = scope.launch {
            try { do { layoutRefreshAgain = false; initialize() } while (layoutRefreshAgain && !isClosed) }
            catch (_: Exception) { finish() }
        }
    }
    fun attach(pane: Int, window: Int, events: (TmuxPaneEvent) -> Unit) {
        require(pane >= 0 && window >= 0); check(pane !in panes) { "tmux pane already attached" }
        // Pane IDs are global to the server and survive join-pane. Bind every
        // read/write to the original grouped session and window at execution.
        val target = SshTmuxEncoding.quote("=$groupedSession:@$window.%$pane")
        val entry = Pane(window, target, events); panes[pane] = entry
        var alternate = false
        fun current() = panes[pane] === entry
        try {
            enqueue(listOf(
                "refresh-client -A '%$pane:pause'" to { _ -> },
                "display-message -p -t $target -F '#{alternate_on}'" to { reply -> alternate = reply.lines.firstOrNull()?.toString(Charsets.UTF_8) == "1" },
                "capture-pane -p -e -S -${SshTmuxEncoding.HISTORY_LINES} -t $target" to { reply ->
                    if (current()) {
                        if (reply.error) endPane(pane) else {
                            val out = ByteArrayOutputStream()
                            out.write((if (alternate) "\u001b[?1049h\u001b[H\u001b[2J" else "\u001b[H\u001b[2J").toByteArray())
                            reply.lines.forEachIndexed { index, row -> if (index > 0) out.write(byteArrayOf(13, 10)); out.write(row) }
                            entry.snapshot = out.toByteArray(); entry.stage = 1
                        }
                    }
                },
                "display-message -p -t $target -F '${SshTmuxEncoding.stateFormat}'" to { reply ->
                    if (current() && entry.stage == 1) {
                        if (reply.error) endPane(pane) else {
                            val fields = SshTmuxEncoding.fields(reply.lines.firstOrNull())
                            val width = fields["pane_width"]?.toIntOrNull(); val height = fields["pane_height"]?.toIntOrNull()
                            entry.grid = if (width != null && height != null && width in 1..65535 && height in 1..65535) width to height
                                else leaf(pane, entry)?.let { it.columns to it.rows }
                            entry.stage = 2
                            entry.grid?.let { entry.events(TmuxPaneEvent.Grid(it.first, it.second)) }
                            entry.events(TmuxPaneEvent.Snapshot(checkNotNull(entry.snapshot) + SshTmuxEncoding.stateSequence(fields)))
                            entry.snapshot = null
                            if (entry.buffered.size() > 0) entry.events(TmuxPaneEvent.Output(entry.buffered.toByteArray()))
                            entry.buffered.reset()
                        }
                    }
                },
                "refresh-client -A '%$pane:continue'" to { _ -> },
            ))
        } catch (failure: Exception) { endPane(pane); throw failure }
    }
    fun detach(pane: Int) { panes.remove(pane) }
    suspend fun write(pane: Int, bytes: ByteArray) {
        val entry = checkNotNull(panes[pane]) { "tmux pane is not attached" }
        for (start in bytes.indices step 256) {
            check(panes[pane] === entry && entry.stage == 2) { "tmux pane attachment ended" }
            val hex = bytes.copyOfRange(start, minOf(start + 256, bytes.size)).joinToString(" ") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            command("send-keys -t ${entry.target} -H $hex")
        }
    }
    fun resize(columns: Int, rows: Int) {
        require(columns in 1..65535 && rows in 1..65535)
        if (size == columns to rows) return
        enqueue(listOf("refresh-client -C ${columns}x$rows" to { _ -> }))
        size = columns to rows
    }
    private fun leaf(pane: Int, entry: Pane) = layouts[entry.window]?.firstOrNull { it.pane == pane }
    private fun changed() {
        if (topology?.isActive == true) return
        topology = scope.launch { yield(); onTopologyChange?.invoke() }
    }
    private fun handle(message: TmuxMessage) {
        if (isClosed) return
        when (message) {
            is TmuxMessage.Output -> panes[message.pane]?.let { entry ->
                val bytes = entry.titles.feed(message.bytes)
                when (entry.stage) {
                    1 -> { check(bytes.size <= 1024 * 1024 - entry.buffered.size()) { "tmux seed output exceeded its limit" }; entry.buffered.write(bytes) }
                    2 -> if (bytes.isNotEmpty()) entry.events(TmuxPaneEvent.Output(bytes))
                }
            }
            is TmuxMessage.Reply -> if (message.flags and 1 == 1) {
                val item = checkNotNull(pending.pollFirst()) { "Unexpected tmux reply" }
                item.timeout?.cancel(); item.receive(Reply(message.lines, message.error))
            }
            is TmuxMessage.Notice -> {
                val fields = message.arguments
                when (message.kind) {
                    "%exit" -> { receivedExit = true; finish() }
                    "%window-add", "%window-renamed", "%sessions-changed", "%session-renamed" -> changed()
                    // tmux also broadcasts this when another grouped session
                    // unlinks a window that our session still owns. Confirm
                    // membership before retiring its panes.
                    "%window-close" -> { refreshLayouts(); changed() }
                    "%layout-change" -> {
                        if (layoutRefresh?.isActive == true) layoutRefreshAgain = true
                        val window = checkNotNull(SshTmuxParser.id(fields.firstOrNull().orEmpty(), '@'))
                        val leaves = SshTmuxLayout.parse(fields[1]).toMutableList()
                        fields.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { visible ->
                            for (leaf in SshTmuxLayout.parse(visible)) {
                                val index = leaves.indexOfFirst { it.pane == leaf.pane }; if (index >= 0) leaves[index] = leaf
                            }
                        }
                        val before = layouts[window].orEmpty().map { it.pane }.toSet()
                        layouts[window] = leaves; updateGrids()
                        if (before != leaves.map { it.pane }.toSet()) changed()
                    }
                }
            }
        }
    }
    private fun updateGrids() {
        for ((pane, entry) in panes.toMap()) {
            val geometry = leaf(pane, entry)
            if (geometry == null) { endPane(pane); continue }
            val next = geometry.columns to geometry.rows
            if (entry.stage == 2 && entry.grid != next) { entry.grid = next; entry.events(TmuxPaneEvent.Grid(next.first, next.second)) }
        }
    }
    private fun endPane(pane: Int) { panes.remove(pane)?.events?.invoke(TmuxPaneEvent.Ended) }
    suspend fun detachSession() {
        if (isClosed) return
        try { command("kill-session -t ${SshTmuxEncoding.quote("=$groupedSession")}") }
        catch (failure: IOException) { if (!receivedExit) throw failure }
        finally { finish() }
    }
    private fun finish() {
        if (isClosed) return
        isClosed = true; writes.close(); runCatching { pipe.close() }; job.cancel()
        while (true) { val bytes = writes.tryReceive().getOrNull() ?: break; bytes.fill(0) }; queuedBytes = 0
        val replies = pending.toList(); pending.clear()
        replies.forEach { it.timeout?.cancel(); it.receive(Reply(emptyList(), true)) }
        panes.keys.toList().forEach(::endPane)
        onClose?.invoke()
    }
    override fun close() = finish()
}
