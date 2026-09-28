package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger

internal interface IrxProbeLane : AutoCloseable {
    suspend fun write(value: JSONObject)
    suspend fun read(): JSONObject?
    suspend fun retire()
}

internal data class IrxProbeActivity(val active: Boolean, val revision: Long = 0)

/** Optional application probes. Failure retires only their lane; native closure owns teardown. */
internal class IrxKeepalive(
    private val active: StateFlow<IrxProbeActivity>,
    private val open: suspend () -> IrxProbeLane,
    private val permits: () -> Boolean,
    private val lastInboundNanos: () -> Long?,
    private val intervalMillis: Long,
    private val deadlineMillis: Long,
    private val now: () -> Long = System::nanoTime
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var closed = false
    private var generation = 0L
    private var probingSince: Long? = null
    private var activityPeriod: IrxProbeActivity? = null
    private var misses = 0
    private var lane: IrxProbeLane? = null
    private var sequence = 0uL

    init {
        require(intervalMillis in 1..60_000 && deadlineMillis in 1..30_000)
        scope.launch {
            active.collectLatest { enabled ->
                if (!enabled.active) return@collectLatest
                val started = now()
                val run = synchronized(lock) {
                    if (closed) return@collectLatest
                    misses = 0
                    activityPeriod = enabled
                    probingSince = started
                    ++generation
                }
                var lastProgress = started
                fun progress(expectedMillis: Long) {
                    val tick = now()
                    // Include stalls during a native probe and between coroutine turns, not
                    // only a delayed timer. Such time cannot establish continuous probing.
                    if (tick - lastProgress > (expectedMillis + deadlineMillis) * 1_000_000) {
                        val stale = synchronized(lock) {
                            if (generation != run) null else {
                                probingSince = tick; misses = 0
                                lane.also { lane = null }
                            }
                        }
                        stale?.let(::retire)
                    }
                    lastProgress = tick
                }
                try {
                    while (current(run)) {
                        progress(0)
                        delay(intervalMillis)
                        if (!current(run)) break
                        progress(intervalMillis)
                        val success = probe(run)
                        progress(deadlineMillis)
                        synchronized(lock) {
                            if (generation == run) misses = if (success) 0 else (misses + 1).coerceAtMost(2)
                        }
                    }
                } finally {
                    val retired = synchronized(lock) {
                        if (generation != run) null else {
                            generation++; probingSince = null; misses = 0
                            lane.also { lane = null }
                        }
                    }
                    retired?.let(::retire)
                }
            }
        }
    }

    private suspend fun probe(run: Long): Boolean {
        try {
            return withTimeout(deadlineMillis) {
                checkCurrent(run)
                val probe = synchronized(lock) { lane } ?: kotlin.run {
                    val candidate = open()
                    try {
                        currentCoroutineContext().ensureActive(); checkCurrent(run)
                        synchronized(lock) { checkCurrent(run); lane = candidate }
                        candidate
                    } catch (failure: Throwable) { retire(candidate); throw failure }
                }
                val seq = synchronized(lock) { ++sequence }
                probe.write(JSONObject().put("v", 1).put("seq", BigInteger(seq.toString())).put("pong", false))
                while (true) {
                    val reply = probe.read() ?: throw EOFException("Keepalive stream ended")
                    checkCurrent(run)
                    val version = reply.opt("v")
                    if ((version !is Int && version !is Long) || (version as Number).toLong() != 1L || reply.opt("pong") !is Boolean)
                        throw IOException("Invalid keepalive reply")
                    val value = reply.opt("seq")
                    if (value !is Int && value !is Long && value !is BigInteger) throw IOException("Invalid keepalive sequence")
                    val received = value.toString().toULongOrNull() ?: throw IOException("Invalid keepalive sequence")
                    if (reply.getBoolean("pong") && received == seq) break
                }
                true
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            val old = synchronized(lock) { if (generation == run) lane.also { lane = null } else null }
            old?.let(::retire)
            return false
        }
    }

    fun positiveSilenceSince(start: Long): Boolean = synchronized(lock) {
        val since = probingSince
        !closed && permits() && active.value.active && active.value == activityPeriod && since != null && since <= start && misses >= 2 &&
            (lastInboundNanos()?.let { it >= start } != true) &&
            now() - start >= (intervalMillis + deadlineMillis) * 2 * 1_000_000
    }

    private fun current(run: Long) = synchronized(lock) { !closed && generation == run && permits() && active.value.active && active.value == activityPeriod }
    private fun checkCurrent(run: Long) { if (!current(run)) throw CancellationException("Probe owner changed") }
    private fun retire(old: IrxProbeLane) { cleanup.launch { try { runCatching { old.retire() } } finally { runCatching { old.close() } } } }
    override fun close() {
        val old = synchronized(lock) {
            if (closed) return
            closed = true; generation++; probingSince = null; misses = 0
            lane.also { lane = null }
        }
        scope.cancel()
        old?.let(::retire)
    }
    companion object { private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
