/* Attachment behavior follows CloudWorkspaceBridge at cmux c2715faa.
 * Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later. See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The machine owns this link; attachment owners only release their own slot. */
internal interface CloudTerminalLink {
    fun attach(terminal: String): Long
    fun detach(attachment: Long)
    fun send(attachment: Long, bytes: ByteArray): Boolean
    fun resize(attachment: Long, columns: Int, rows: Int): Long
    suspend fun output(attachment: Long): CloudTerminalOutput?
}
internal class NativeCloudTerminalLink(private val session: CloudNativeSession) : CloudTerminalLink {
    override fun attach(terminal: String) = session.attach(terminal, force = true)
    override fun detach(attachment: Long) = session.detachIfCurrent(attachment)
    override fun send(attachment: Long, bytes: ByteArray) = session.sendAttached(attachment, bytes)
    override fun resize(attachment: Long, columns: Int, rows: Int) = session.resizeAttached(attachment, columns, rows)
    override suspend fun output(attachment: Long) = session.nextOutput(attachment)
}
internal enum class CloudAttachmentPhase { IDLE, CONNECTING, READY, FAILED, EXITED, CLOSED }
internal data class CloudAttachmentState(val terminalId: String? = null, val phase: CloudAttachmentPhase = CloudAttachmentPhase.IDLE,
    val pendingBytes: Int = 0, val failure: String? = null)

/** One machine's single native attachment slot, independent of view collectors.
 * Blocking attach runs on an independent worker. Its serial slot and native token
 * fence late completion/cleanup; callbacks run in order on the parent's dispatcher.
 */
internal class CloudTerminalAttachment(parent: CoroutineScope, private val isCurrent: () -> Boolean,
    private val connect: suspend () -> CloudTerminalLink,
    private val deliver: (String, CloudTerminalOutput) -> Unit,
    private val nativeDispatcher: CoroutineDispatcher = Dispatchers.IO) : AutoCloseable {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val deliveryContext = parent.coroutineContext.minusKey(Job)
    private val lock = Any()
    private val slot = Mutex()
    private val mutable = MutableStateFlow(CloudAttachmentState())
    val state = mutable.asStateFlow()
    private var closed = false
    private var available = false
    private var generation = 0L
    private var worker: Job? = null
    private var signal: Channel<Unit>? = null
    private var repaint: Job? = null
    private var grid: Pair<Int, Int>? = null
    private var appliedGrid: Pair<Int, Int>? = null
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    init { job.invokeOnCompletion { close() } }
    private fun allowed() = !closed && job.isActive && isCurrent()
    private fun admitted(at: Long) = allowed() && available && generation == at
    private fun requireAttempt(at: Long) = synchronized(lock) {
        if (!admitted(at)) throw CancellationException("Cloud terminal selection changed")
    }
    fun select(terminalId: String?) = synchronized(lock) {
        if (!allowed() || mutable.value.terminalId == terminalId) return@synchronized
        require(terminalId == null || terminalId.isNotEmpty())
        cancelLocked(); clearPending(); grid = null; appliedGrid = null
        mutable.value = CloudAttachmentState(terminalId, if (terminalId == null) CloudAttachmentPhase.IDLE else CloudAttachmentPhase.CONNECTING)
        if (terminalId != null && available) startLocked()
    }
    fun setAvailable(value: Boolean) = synchronized(lock) {
        if (!allowed() || available == value) return@synchronized
        available = value
        if (value) {
            if (mutable.value.terminalId != null && mutable.value.phase != CloudAttachmentPhase.EXITED) startLocked()
        } else {
            cancelLocked(); clearPending(); appliedGrid = null
            mutable.value = mutable.value.copy(phase = if (mutable.value.terminalId == null) CloudAttachmentPhase.IDLE else CloudAttachmentPhase.CONNECTING, pendingBytes = 0)
        }
    }
    fun replay() = synchronized(lock) {
        if (!allowed() || !available || mutable.value.terminalId == null ||
            (worker?.isActive == true && mutable.value.phase == CloudAttachmentPhase.CONNECTING)) return@synchronized
        startLocked()
    }
    fun send(bytes: ByteArray): Boolean = synchronized(lock) {
        val limit = if (mutable.value.phase == CloudAttachmentPhase.READY) LIVE_INPUT_LIMIT else INPUT_LIMIT
        if (!allowed() || mutable.value.terminalId == null || mutable.value.phase in setOf(CloudAttachmentPhase.FAILED, CloudAttachmentPhase.EXITED) ||
            bytes.size > limit - pendingBytes || pending.size >= 256) return@synchronized false
        if (bytes.isEmpty()) return@synchronized true
        pending.addLast(bytes.copyOf()); pendingBytes += bytes.size
        mutable.value = mutable.value.copy(pendingBytes = pendingBytes)
        signal?.trySend(Unit); true
    }
    fun resize(columns: Int, rows: Int) = synchronized(lock) {
        if (!allowed() || mutable.value.terminalId == null) return@synchronized
        require(columns in 1..65535 && rows in 1..65535)
        val next = columns to rows
        if (grid != next) { grid = next; signal?.trySend(Unit) }
    }
    private fun cancelLocked() {
        generation++; worker?.cancel(); worker = null
        signal?.close(); signal = null; repaint?.cancel(); repaint = null
    }
    private fun clearPending() { pending.forEach { it.fill(0) }; pending.clear(); pendingBytes = 0 }
    private fun startLocked() {
        cancelLocked()
        val at = generation
        val terminal = checkNotNull(mutable.value.terminalId)
        val wake = Channel<Unit>(Channel.CONFLATED); signal = wake
        mutable.value = mutable.value.copy(phase = CloudAttachmentPhase.CONNECTING, failure = null)
        val task = CoroutineScope(nativeDispatcher).launch(start = CoroutineStart.LAZY) {
            var link: CloudTerminalLink? = null
            var attachment: Long? = null
            try {
                slot.withLock {
                    ensureActive(); requireAttempt(at)
                    val live = connect(); ensureActive(); requireAttempt(at)
                    link = live
                    // No cancellable context hop around handle production: even
                    // cancellation inside this blocking call leaves a token to release.
                    attachment = live.attach(terminal)
                    ensureActive(); requireAttempt(at)
                    synchronized(lock) { if (admitted(at)) mutable.value = mutable.value.copy(phase = CloudAttachmentPhase.READY) }
                }
                val live = checkNotNull(link); val token = checkNotNull(attachment)
                coroutineScope {
                    val commands = launch {
                        var sentGrid: Pair<Int, Int>? = null
                        wake.trySend(Unit)
                        for (ignored in wake) {
                            slot.withLock {
                                ensureActive(); requireAttempt(at)
                                val size = synchronized(lock) { grid }
                                if (size != null && size != sentGrid) {
                                    check(live.resize(token, size.first, size.second) != 0L) { "Cloud terminal resize was rejected" }
                                    sentGrid = size
                                }
                                while (true) {
                                    ensureActive(); requireAttempt(at)
                                    val bytes = synchronized(lock) {
                                        if (!admitted(at)) null else pending.removeFirstOrNull()?.also {
                                            pendingBytes -= it.size; mutable.value = mutable.value.copy(pendingBytes = pendingBytes)
                                        }
                                    } ?: break
                                    try { check(live.send(token, bytes)) { "Cloud terminal input was rejected. Input was not replayed." } }
                                    finally { bytes.fill(0) }
                                }
                            }
                        }
                    }
                    try {
                        while (isActive) {
                            requireAttempt(at)
                            val event = live.output(token) ?: continue
                            withContext(deliveryContext) {
                                synchronized(lock) {
                                    if (admitted(at)) {
                                        deliver(terminal, event)
                                        when (event.kind) {
                                            1 -> { appliedGrid = event.columns to event.rows; repaint?.cancel(); repaint = null }
                                            3 -> if (appliedGrid != null && appliedGrid != (event.columns to event.rows)) scheduleRepaint(at)
                                            4 -> { clearPending(); mutable.value = mutable.value.copy(phase = CloudAttachmentPhase.EXITED, pendingBytes = 0) }
                                        }
                                    }
                                }
                            }
                            if (event.kind == 4) break
                        }
                    } finally { commands.cancel() }
                }
            } catch (failure: Exception) {
                fail(at, if (failure is CancellationException) "Cloud terminal attachment was interrupted" else
                    failure.message?.take(2048) ?: "Cloud terminal disconnected")
            } catch (_: LinkageError) {
                fail(at, "Cloud native runtime is unavailable")
            } finally {
                val live = link; val token = attachment
                if (live != null && token != null) withContext(NonCancellable) {
                    // Generation-aware detach cannot undo a newer native attach.
                    slot.withLock { runCatching { live.detach(token) } }
                }
            }
        }
        worker = task; task.start()
    }
    private fun fail(at: Long, message: String) = synchronized(lock) {
        if (admitted(at)) {
            clearPending(); repaint?.cancel(); repaint = null
            mutable.value = mutable.value.copy(phase = CloudAttachmentPhase.FAILED, pendingBytes = 0, failure = message)
        }
    }
    private fun scheduleRepaint(at: Long) {
        repaint?.cancel()
        repaint = scope.launch {
            delay(400)
            synchronized(lock) { if (admitted(at) && mutable.value.phase == CloudAttachmentPhase.READY) startLocked() }
        }
    }
    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true; cancelLocked(); clearPending(); grid = null; appliedGrid = null
        mutable.value = CloudAttachmentState(phase = CloudAttachmentPhase.CLOSED)
        job.cancel()
    }
    companion object { const val INPUT_LIMIT = 8 * 1024; const val LIVE_INPUT_LIMIT = 256 * 1024 }
}
