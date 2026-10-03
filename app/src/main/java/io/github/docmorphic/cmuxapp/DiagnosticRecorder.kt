package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** One bounded event queue and writer per process. Control commands cannot be displaced by traffic. */
internal class DiagnosticRecorder(private val files: DiagnosticFiles, private val boot: Int, private val role: DiagnosticRole,
    private val elapsed: () -> Long = System::nanoTime, private val wall: () -> Long = System::currentTimeMillis,
    private val capacity: Int = 4096, private val onClear: (Long) -> Unit = {},
    private val exitHistory: () -> List<DiagnosticExit> = { emptyList() }) : AutoCloseable {
    private sealed interface Command {
        data class Record(val value: DiagnosticRecord) : Command
        class Control(val run: suspend () -> Unit) : Command
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val admission = Any()
    private var pending = 0
    private var dropped = 0L
    private var closed = false
    private var recoveryPending = false
    private lateinit var worker: Job
    private val failed = MutableStateFlow(false)
    val storageFailed = failed.asStateFlow()
    init {
        require(capacity > 0)
        worker = scope.launch {
            var next: Command? = null
            while (true) {
                val command = next ?: commands.receiveCatching().getOrNull() ?: break
                next = null
                when (command) {
                    is Command.Control -> command.run()
                    is Command.Record -> {
                        val batch = mutableListOf(command.value)
                        while (batch.size < 128) {
                            val queued = commands.tryReceive().getOrNull() ?: break
                            if (queued is Command.Record) batch += queued.value else { next = queued; break }
                        }
                        val omitted = synchronized(admission) { pending -= batch.size; dropped.also { dropped = 0 } }
                        if (omitted > 0) batch += DiagnosticRecord(DebugOperation.LOG_DROPPED, DebugOutcome.FAILURE, role, wall(), elapsed(), boot, count = omitted)
                        try { files.record(batch); failed.value = false }
                        catch (_: Exception) {
                            failed.value = true
                            synchronized(admission) { dropped += batch.sumOf { it.count } }
                        }
                    }
                }
            }
        }
    }
    fun record(operation: DebugOperation, outcome: DebugOutcome, duration: Long = 0, id: Long = 0) = synchronized(admission) {
        if (closed) return@synchronized
        if (pending >= capacity) { dropped++; return@synchronized }
        val value = DiagnosticRecord(operation, outcome, role, wall(), elapsed(), boot, duration, id)
        if (commands.trySend(Command.Record(value)).isSuccess) pending++
    }
    private suspend fun <T> control(discard: (T) -> Unit = {}, action: (Job) -> T): T {
        val caller = currentCoroutineContext().job
        return suspendCancellableCoroutine { answer ->
        synchronized(admission) {
            check(!closed) { "Diagnostic recorder closed" }
            check(commands.trySend(Command.Control {
                try {
                    caller.ensureActive()
                    val result = action(caller)
                    answer.resume(result, onCancellation = { _, value, _ -> discard(value) })
                    failed.value = false
                } catch (failure: Throwable) {
                    if (failure !is CancellationException) failed.value = true
                    if (answer.isActive) answer.resumeWith(Result.failure(failure))
                }
            }).isSuccess)
        }
        }
    }
    suspend fun verbose(): Boolean = control { files.verbose() }
    suspend fun flush() = control { Unit }
    suspend fun clearCutoff(): Long = control { files.clearCutoff(boot) }
    suspend fun setVerbose(enabled: Boolean) = control { files.setVerbose(enabled) }
    private fun recoverSystemExits() {
        val exits = try { exitHistory() } catch (_: Exception) {
            // An unavailable OS service must not prevent sharing the logs already on disk.
            files.record(listOf(DiagnosticRecord(DebugOperation.EXIT_HISTORY, DebugOutcome.FAILURE, role, wall(), elapsed(), boot)))
            return
        }
        files.recoverExits(exits)
    }
    fun recoverExits() = synchronized(admission) {
        if (closed || recoveryPending) return@synchronized
        recoveryPending = true
        commands.trySend(Command.Control {
            try { recoverSystemExits(); failed.value = false }
            catch (_: Exception) { failed.value = true }
            finally { synchronized(admission) { recoveryPending = false } }
        })
        Unit
    }
    suspend fun clear() {
        val cutoff = elapsed()
        val wallCutoff = wall()
        control { files.clear(boot, cutoff, wallCutoff); synchronized(admission) { dropped = 0 }; onClear(cutoff) }
    }
    suspend fun export(): File = control(discard = { it: File -> it.delete() }) { caller ->
        recoverSystemExits()
        val omitted = synchronized(admission) { dropped }
        if (omitted > 0) {
            files.record(listOf(DiagnosticRecord(DebugOperation.LOG_DROPPED, DebugOutcome.FAILURE, role, wall(), elapsed(), boot, count = omitted)))
            synchronized(admission) { dropped -= omitted }
        }
        files.export { caller.ensureActive() }
    }
    override fun close() { synchronized(admission) { closed = true; commands.close() } }
    suspend fun shutdown() { close(); worker.join(); scope.cancel() }
}
