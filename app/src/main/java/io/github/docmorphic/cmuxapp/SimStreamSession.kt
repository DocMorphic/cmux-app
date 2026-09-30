package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** A dedicated admitted Iroh lane, never the JSON control or terminal lane. */
internal interface SimStreamLane : AutoCloseable {
    suspend fun read(): ByteArray?
    suspend fun write(bytes: ByteArray)
}

/** Input stays ordered except for unsent moves of the same pointer. Overflow retires the session. */
internal class SimInputOutbox(private val maximumEvents: Int = 1024, private val maximumBytes: Int = 1024 * 1024) {
    private sealed interface Pending {
        data class Input(val value: SimInput) : Pending
        data class Quality(val value: SimMessage.Start) : Pending
    }
    private val pending = mutableListOf<Pending>()
    private var nextSequence = 1uL
    private var bytes = 0
    private fun cost(event: SimInput): Int = when (event) {
        is SimInput.Text -> {
            if (event.text.length > maximumBytes) throw IOException("Simulator input queue full")
            5 + event.text.toByteArray(Charsets.UTF_8).size
        }
        is SimInput.Touch -> 19
        is SimInput.Key -> 4
        is SimInput.Button -> 2
    }
    fun enqueue(event: SimInput) {
        if (event is SimInput.Touch && event.phase == SimTouchPhase.MOVED) {
            for (index in pending.indices.reversed()) {
                val old = (pending[index] as? Pending.Input)?.value as? SimInput.Touch ?: break
                if (old.phase != SimTouchPhase.MOVED) break
                if (old.pointer == event.pointer) { pending[index] = Pending.Input(event); return }
            }
        }
        val cost = cost(event)
        if (pending.size >= maximumEvents || cost > maximumBytes - bytes) throw IOException("Simulator input queue full")
        pending += Pending.Input(event); bytes += cost
    }
    /** A renegotiation is an ordering barrier: touch moves cannot coalesce across it. */
    fun quality(start: SimMessage.Start) {
        val cost = 13 + start.codecs.size
        if (pending.size >= maximumEvents || cost > maximumBytes - bytes) throw IOException("Simulator input queue full")
        pending += Pending.Quality(start); bytes += cost
    }
    fun drain(limit: Int = 64): SimMessage? {
        require(limit in 1..65535)
        if (pending.isEmpty()) return null
        (pending.first() as? Pending.Quality)?.let {
            pending.removeAt(0); bytes -= 13 + it.value.codecs.size
            return it.value
        }
        if (nextSequence == ULong.MAX_VALUE) throw IOException("Simulator input sequence exhausted")
        val events = pending.take(limit).takeWhile { it is Pending.Input }.map { (it as Pending.Input).value }
        pending.subList(0, events.size).clear(); bytes -= events.sumOf(::cost)
        return SimMessage.Input(nextSequence++, events)
    }
    fun clear() { pending.clear(); bytes = 0 }
}

internal interface SimFramePresenter {
    suspend fun configure(config: SimMessage.Config)
    /** True only after output was installed for display. Merely decoding is insufficient. */
    suspend fun present(frame: SimMessage.Frame): Boolean
    suspend fun reset()
}

internal sealed interface SimViewerEvent {
    data class Configured(val config: SimMessage.Config) : SimViewerEvent
    data class Presented(val sequence: ULong) : SimViewerEvent
    data class HostState(val state: SimMessage.State) : SimViewerEvent
}

/** One attach, one outbox, one presentation owner. A reconnect must create a new instance. */
internal class SimStreamSession(private val presenter: SimFramePresenter, private val epoch: ULong,
    maximumLongSide: Int, codecs: List<SimCodec>,
    private val receiptMicros: () -> ULong = { (System.nanoTime() / 1000).toULong() },
    private val event: suspend (SimViewerEvent) -> Unit) {
    private val lock = Any()
    private val writes = Mutex()
    private val outbox = SimInputOutbox()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var lane: SimStreamLane? = null
    private var used = false
    private var ended = false
    private var failure: IOException? = null
    private var maximumLongSide = maximumLongSide
    private val codecs = codecs.toList()

    init { require(maximumLongSide in 1..65535); require(codecs.isNotEmpty()) }

    fun input(input: SimInput): Boolean = synchronized(lock) {
        if (ended || failure != null) return false
        try { outbox.enqueue(input) }
        catch (error: IOException) { failure = error; outbox.clear(); lane?.close() }
        wake.trySend(Unit)
        failure == null
    }

    fun updateQuality(pixels: Int): Boolean = synchronized(lock) {
        require(pixels in 1..65535)
        if (ended || failure != null) return false
        if (maximumLongSide == pixels) return true
        maximumLongSide = pixels
        if (used) try { outbox.quality(SimMessage.Start(epoch, pixels, codecs)) }
        catch (error: IOException) { failure = error; outbox.clear(); lane?.close() }
        wake.trySend(Unit)
        failure == null
    }

    /** Synchronous fence used before cancellation; queued gestures can never reach a replacement. */
    fun retireInput() = synchronized(lock) { ended = true; outbox.clear() }

    /** EOF is clean only at a message boundary. Every exit drops input and closes the lane. */
    suspend fun run(connection: SimStreamLane) {
        val start = try { synchronized(lock) {
            check(!used && !ended); used = true; lane = connection
            SimMessage.Start(epoch, maximumLongSide, codecs)
        } }
        catch (error: Throwable) { connection.close(); throw error }
        var configured = false
        var rejected = 0
        var requestedKeyframe = false
        try {
            send(start)
            coroutineScope {
                val writer = launch {
                    for (signal in wake) {
                        while (true) {
                            val batch = synchronized(lock) { failure?.let { throw it }; outbox.drain() } ?: break
                            send(batch)
                        }
                    }
                }
                try {
                    val framer = SimStreamFramer()
                    while (true) {
                        synchronized(lock) { failure?.let { throw it } }
                        val chunk = connection.read() ?: break
                        ensureActive()
                        framer.feed(chunk) { message ->
                            when (message) {
                                is SimMessage.Config -> {
                                    presenter.configure(message); configured = true; rejected = 0; requestedKeyframe = false
                                    event(SimViewerEvent.Configured(message))
                                }
                                is SimMessage.Frame -> {
                                    if (!configured) throw IOException("Simulator frame before config")
                                    val shown = try { presenter.present(message) }
                                    catch (error: CancellationException) { throw error }
                                    catch (_: Exception) { false }
                                    if (shown) {
                                        rejected = 0; requestedKeyframe = false
                                        send(SimMessage.Ack(message.sequence, receiptMicros()))
                                        event(SimViewerEvent.Presented(message.sequence))
                                    } else {
                                        rejected++
                                        if (rejected >= 6) throw IOException("Simulator display rejected frames")
                                        if (rejected >= 3 && !requestedKeyframe) {
                                            requestedKeyframe = true; presenter.reset(); send(SimMessage.KeyframeRequest)
                                        }
                                    }
                                }
                                is SimMessage.State -> event(SimViewerEvent.HostState(message))
                                else -> throw IOException("Host sent a viewer-only simulator message")
                            }
                        }
                    }
                    synchronized(lock) { failure?.let { throw it } }
                    framer.finish()
                } finally { writer.cancelAndJoin() }
            }
        } finally {
            // The writer has stopped before this final bounded control message.
            // Cancellation still asks the host to release its encoder promptly.
            withContext(NonCancellable) { runCatching { stop() } }
            synchronized(lock) { ended = true; outbox.clear(); lane = null; wake.close() }
            connection.close()
        }
    }

    suspend fun stop() {
        retireInput()
        try { withTimeout(1000) { send(SimMessage.Stop) } }
        finally { synchronized(lock) { lane }?.close() }
    }

    private suspend fun send(message: SimMessage) = writes.withLock {
        val active = synchronized(lock) {
            failure?.let { throw it }
            if (ended && message != SimMessage.Stop) throw IOException("Simulator session ended")
            lane ?: throw IOException("Simulator lane closed")
        }
        active.write(SimStreamWire.encode(message))
    }
}
