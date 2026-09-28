package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.EOFException

internal interface MobileControlLane : AutoCloseable {
    suspend fun read(): ByteArray
    suspend fun write(bytes: ByteArray)
    suspend fun retire()
}

/** Frames never cross a stream generation, including a native read returning after replacement. */
internal class IrxControlChannel(
    initial: MobileControlLane,
    private val replacement: suspend () -> MobileControlLane,
    private val permits: () -> Boolean,
    private val connectionClosed: () -> Boolean,
    private val repairTimeoutMillis: Long = 5000
) : AutoCloseable {
    private class Generation(val id: Long, val lane: MobileControlLane, var reader: Job? = null)
    private data class Read(val generation: Long, val frame: ByteArray? = null, val failure: Throwable? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val writes = Mutex()
    private val reads = Channel<Read>(1)
    private var current = Generation(0, initial)
    private var closed = false
    private var repair: CompletableDeferred<Unit>? = null

    init { startReader(current) }

    private fun startReader(generation: Generation) {
        generation.reader = scope.launch {
            try {
                val decoder = MobileFrameDecoder()
                while (true) {
                    val bytes = generation.lane.read()
                    if (bytes.isEmpty()) throw EOFException("cmux control stream ended")
                    for (frame in decoder.feed(bytes)) {
                        if (synchronized(lock) { closed || current !== generation }) return@launch
                        reads.send(Read(generation.id, MobileFrameCodec.encode(frame)))
                    }
                }
            } catch (failure: Throwable) {
                if (synchronized(lock) { !closed && current === generation })
                    runCatching { reads.send(Read(generation.id, failure = failure)) }
            }
        }
    }

    suspend fun read(): ByteArray {
        while (true) {
            val value = reads.receive()
            val waiting = synchronized(lock) {
                check(!closed)
                if (value.generation != current.id) return@synchronized null
                repair
            }
            if (value.failure != null) waiting?.await()
            synchronized(lock) {
                check(!closed)
                if (value.generation == current.id) {
                    value.failure?.let { throw it }
                    return checkNotNull(value.frame)
                }
            }
        }
    }

    suspend fun write(bytes: ByteArray): Long = writes.withLock {
        val generation = synchronized(lock) { check(!closed && permits()); current }
        generation.lane.write(bytes)
        generation.id
    }

    suspend fun repair(): MobileControlRepair {
        val finished = synchronized(lock) {
            if (closed || connectionClosed()) return MobileControlRepair.Closed
            if (repair != null) return MobileControlRepair.Unavailable
            CompletableDeferred<Unit>().also { repair = it }
        }
        var candidate: MobileControlLane? = null
        try {
            return withTimeout(repairTimeoutMillis) {
                writes.withLock {
                    synchronized(lock) { check(!closed && permits()) }
                    val lane = replacement().also { candidate = it }
                    currentCoroutineContext().ensureActive()
                    val retired = synchronized(lock) {
                        check(!closed && permits() && repair === finished)
                        val old = current
                        current = Generation(old.id + 1, lane)
                        candidate = null
                        startReader(current)
                        old
                    }
                    retired.reader?.cancel()
                    retire(retired.lane)
                    MobileControlRepair.Repaired(retired.id + 1)
                }
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            return if (synchronized(lock) { closed } || connectionClosed()) MobileControlRepair.Closed
                else MobileControlRepair.Unavailable
        } finally {
            candidate?.let(::retire)
            synchronized(lock) { if (repair === finished) repair = null }
            finished.complete(Unit)
        }
    }

    private fun retire(lane: MobileControlLane) {
        cleanup.launch { try { runCatching { lane.retire() } } finally { runCatching { lane.close() } } }
    }

    override fun close() {
        val old = synchronized(lock) {
            if (closed) return
            closed = true
            repair?.complete(Unit); repair = null
            current
        }
        reads.close(EOFException("cmux connection closed"))
        old.reader?.cancel()
        retire(old.lane)
        scope.cancel()
    }

    companion object { private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
