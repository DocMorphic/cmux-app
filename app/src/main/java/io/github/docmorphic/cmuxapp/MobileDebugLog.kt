package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.ArrayDeque
import java.util.Locale

/** Only fixed labels and timing enter the ring; never params, output, URLs or exception messages. */
internal enum class DebugOperation {
    RPC_CONNECT, RPC_DISCONNECT, RPC_HOST, RPC_WORKSPACE, RPC_TERMINAL, RPC_BROWSER,
    RPC_FILES, RPC_NOTIFICATIONS, RPC_TASK, RPC_EVENTS, RPC_OTHER,
    SSH_CONNECT, SSH_DISCONNECT, SSH_IO, BROWSER_PREPARE
}
internal enum class DebugOutcome { STARTED, SUCCESS, TIMEOUT, CANCELLED, REMOTE_ERROR, IO_ERROR, FAILURE }

internal fun debugRpcOperation(method: String): DebugOperation = when (method.substringBefore('.')) {
    "terminal" -> DebugOperation.RPC_TERMINAL
    "workspace" -> DebugOperation.RPC_WORKSPACE
    "notification" -> DebugOperation.RPC_NOTIFICATIONS
    "mobile" -> when (method.split('.', limit = 3).getOrNull(1)) {
        "host" -> DebugOperation.RPC_HOST
        "workspace" -> DebugOperation.RPC_WORKSPACE
        "terminal" -> DebugOperation.RPC_TERMINAL
        "browser" -> DebugOperation.RPC_BROWSER
        "directory", "file", "files" -> DebugOperation.RPC_FILES
        "task" -> DebugOperation.RPC_TASK
        "events" -> DebugOperation.RPC_EVENTS
        else -> DebugOperation.RPC_OTHER
    }
    else -> DebugOperation.RPC_OTHER
}

internal fun debugOutcome(failure: Throwable): DebugOutcome = when (failure) {
    is TimeoutCancellationException, is java.net.SocketTimeoutException -> DebugOutcome.TIMEOUT
    is CancellationException -> DebugOutcome.CANCELLED
    is MobileRpcException -> DebugOutcome.REMOTE_ERROR
    is java.io.IOException -> DebugOutcome.IO_ERROR
    else -> DebugOutcome.FAILURE
}

/** Snapshot and eviction share one short lock; no IO or coroutine launches on terminal paths. */
internal class DebugLogBuffer(private val capacity: Int = 4000, private val maxChars: Int = 96_000,
    private val now: () -> Long = System::nanoTime) {
    init { require(capacity > 0 && maxChars >= 256) }
    private val started = now()
    private val lines = ArrayDeque<String>()
    private var chars = 0
    private var dropped = 0L
    private var sequence = 0L
    data class Operation(val id: Long, val kind: DebugOperation, val started: Long)

    @Synchronized fun begin(kind: DebugOperation): Operation {
        val operation = Operation(++sequence, kind, now())
        append(operation, DebugOutcome.STARTED)
        return operation
    }
    @Synchronized fun finish(operation: Operation, outcome: DebugOutcome) = append(operation, outcome)
    private fun append(operation: Operation, outcome: DebugOutcome) {
        val time = now()
        val line = String.format(Locale.ROOT, "[%9.3f] #%d %s %s %dms", (time - started).coerceAtLeast(0) / 1e9,
            operation.id, operation.kind.name, outcome.name, (time - operation.started).coerceAtLeast(0) / 1_000_000)
        while (lines.isNotEmpty() && (lines.size >= capacity || chars + line.length + 1 > maxChars)) {
            chars -= lines.removeFirst().length + 1; dropped++
        }
        lines.addLast(line); chars += line.length + 1
    }
    @Synchronized fun snapshot(): String = "${lines.size} lines; $dropped older entries discarded\n" + lines.joinToString("\n")
}

internal object MobileDebugLog {
    private val buffer by lazy { DebugLogBuffer() }
    fun begin(kind: DebugOperation): DebugLogBuffer.Operation? = if (BuildConfig.DEBUG) buffer.begin(kind) else null
    fun finish(operation: DebugLogBuffer.Operation?, outcome: DebugOutcome) {
        if (BuildConfig.DEBUG && operation != null) buffer.finish(operation, outcome)
    }
    suspend fun <T> trace(kind: DebugOperation, action: suspend () -> T): T {
        val operation = begin(kind)
        try { return action().also { finish(operation, DebugOutcome.SUCCESS) } }
        catch (failure: Throwable) { finish(operation, debugOutcome(failure)); throw failure }
    }
    fun snapshot(): String = if (BuildConfig.DEBUG) buffer.snapshot() else "Debug logging unavailable"
}
