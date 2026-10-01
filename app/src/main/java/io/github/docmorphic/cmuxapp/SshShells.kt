package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID

internal enum class SshShellPhase { OPENING, RUNNING, ENDED }
internal data class SshShellState(val phase: SshShellPhase = SshShellPhase.OPENING, val revision: Int = 0, val error: String? = null)

/** Main-dispatcher owned shell. UI disposal releases its view, not the PTY.
 * Raw SSH writes have no delivery acknowledgement and are never replayed. */
internal class SshShell(
    val hostId: UUID, val title: String, lifetime: CoroutineScope,
    private val admitted: () -> Boolean,
    private val connect: suspend () -> SshTransport,
) : AutoCloseable {
    val id = "cmux-ssh-$hostId:shell:${UUID.randomUUID()}"
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(lifetime.coroutineContext + job + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(SshShellState())
    val state = mutable.asStateFlow()
    val display = GhosttyVtTerminal(80, 24) { bytes -> enqueue(Command.Write(bytes.copyOf())) }
    private var pty: SshPty? = null
    private var connection: SshTransport? = null
    private var ended = false
    private var disposed = false
    private var pendingBytes = 0
    private val commands = Channel<Command>(256)
    private var viewport = listOf(80, 24, 1, 1)
    private sealed interface Command {
        data class Write(val bytes: ByteArray) : Command
        data class Resize(val columns: Int, val rows: Int) : Command
    }
    init {
        scope.launch { try { awaitCancellation() } finally { end(null) } }
        scope.launch {
            try {
                val transport = connect()
                check(admitted() && !ended) { "SSH computer changed" }
                connection = transport
                scope.launch { transport.disconnected.first { it }; end("SSH connection ended") }
                val size = viewport
                val opened = transport.openPty(size[0], size[1])
                if (!admitted() || ended) { opened.close(); return@launch }
                pty = opened
                mutable.value = mutable.value.copy(phase = SshShellPhase.RUNNING)
                scope.launch { writeLoop(opened) }
                // Explicit close runs in a separate cancellation child, so a
                // blocking read cannot keep account retirement waiting for EOF.
                val buffer = ByteArray(8192)
                while (isActive && !ended) {
                    val count = withContext(Dispatchers.IO) { opened.output.read(buffer) }
                    if (count < 0) break
                    check(admitted() && transport.isConnected) { "SSH computer changed" }
                    if (count > 0) {
                        display.append(buffer.copyOf(count))
                        mutable.value = mutable.value.copy(revision = mutable.value.revision + 1)
                    }
                }
                end(null)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                end(failure.message ?: "SSH shell ended")
            }
        }
    }
    private fun allowed() = !ended && !disposed && job.isActive && admitted() && connection?.isConnected != false
    fun send(text: String, paste: Boolean = false): Boolean {
        if (!allowed() || mutable.value.phase != SshShellPhase.RUNNING) return false
        val encoded = if (paste) TerminalKeyEncoding.paste(text, display.bracketedPaste) else text
        return enqueue(Command.Write(encoded.toByteArray(Charsets.UTF_8)))
    }
    fun resize(columns: Int, rows: Int, cells: TerminalCellMetrics) {
        if (!allowed()) return
        val next = listOf(columns.coerceIn(2, 1000), rows.coerceIn(2, 1000), cells.widthPx.toInt().coerceIn(1, 4096), cells.heightPx.toInt().coerceIn(1, 4096))
        if (next == viewport) return
        viewport = next
        if (!enqueue(Command.Resize(next[0], next[1]))) return
        try {
            display.resize(next[0], next[1], next[2], next[3])
            mutable.value = mutable.value.copy(revision = mutable.value.revision + 1)
        } catch (failure: Exception) { end(failure.message ?: "Terminal resize failed") }
    }
    private fun enqueue(command: Command): Boolean {
        val bytes = (command as? Command.Write)?.bytes
        if (!allowed()) { bytes?.fill(0); return false }
        val count = bytes?.size ?: 0
        if (count <= MAX_PENDING_BYTES - pendingBytes) {
            pendingBytes += count
            if (commands.trySend(command).isSuccess) return true
            pendingBytes -= count
        }
        bytes?.fill(0)
        end("SSH input could not keep up. This shell was closed; queued input was not replayed.")
        return false
    }
    private suspend fun writeLoop(channel: SshPty) {
        try {
            for (command in commands) {
                check(allowed()) { "SSH shell ended" }
                when (command) {
                    is Command.Write -> try { channel.write(command.bytes) } finally {
                        pendingBytes -= command.bytes.size; command.bytes.fill(0)
                    }
                    is Command.Resize -> channel.resize(command.columns, command.rows)
                }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            end("SSH input delivery was not confirmed. This shell was closed; input was not replayed.")
        }
    }
    private fun end(error: String?) {
        if (ended) return
        ended = true
        pty?.close(); pty = null
        commands.close()
        while (true) {
            val item = commands.tryReceive().getOrNull() ?: break
            (item as? Command.Write)?.bytes?.fill(0)
        }
        mutable.value = mutable.value.copy(phase = SshShellPhase.ENDED, error = error)
        job.cancel()
    }
    override fun close() {
        if (disposed) return
        end(null); disposed = true; display.close()
    }
    companion object { private const val MAX_PENDING_BYTES = 256 * 1024 }
}

/** One device-local shell registry per account owner. Host IDs never enter a Mac RPC route. */
internal class SshShells(private val hosts: SshHostStore, private val connections: SshConnections<SshTransport>,
    lifetime: CoroutineScope, private val admitted: () -> Boolean,
) : AutoCloseable {
    private val job = SupervisorJob(checkNotNull(lifetime.coroutineContext[Job]))
    private val scope = CoroutineScope(lifetime.coroutineContext + job + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow<List<SshShell>>(emptyList())
    val state = mutable.asStateFlow()
    private var counter = 0
    init {
        scope.launch {
            hosts.state.collect { records ->
                mutable.value.filter { records.host(it.hostId) == null }.forEach { remove(it.id) }
            }
        }
        scope.launch { try { awaitCancellation() } finally { closeAll() } }
    }
    suspend fun create(hostId: UUID): SshShell = withContext(Dispatchers.Main.immediate) {
        check(job.isActive && admitted()) { "Sign in to open an SSH shell" }
        check(hosts.state.value.host(hostId) != null) { "SSH computer was removed" }
        check(mutable.value.size < 16) { "Close an SSH shell before opening another" }
        SshShell(hostId, "Shell ${++counter}", scope, { job.isActive && admitted() }) {
            connections.open(hostId)
        }.also { mutable.value += it }
    }
    fun remove(id: String) {
        val removed = mutable.value.firstOrNull { it.id == id } ?: return
        mutable.value -= removed; removed.close()
    }
    private fun closeAll() { val retired = mutable.value; mutable.value = emptyList(); retired.forEach { it.close() } }
    override fun close() { job.cancel(); scope.launch(NonCancellable) { closeAll() } }
}
