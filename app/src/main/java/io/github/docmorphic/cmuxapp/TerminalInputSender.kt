package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Session-dispatcher confined. Payloads must be immutable snapshots, never selected-terminal lookups. */
internal class TerminalInputSender<P>(
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val acknowledgementTimeoutMs: Long = 5_000,
    private val unavailableTimeoutMs: Long = 30_000,
    private val writeTimeoutMs: Long = 5_000,
    private val retryDelayMs: (Int) -> Long = { it * 250L },
    private val maximumPendingBytes: Int = 16 * 1024 * 1024,
    private val maximumTerminals: Int = 64,
    private val merge: (P, P) -> P? = { _, _ -> null }
) : AutoCloseable {
    data class Owner(val login: String, val user: String, val team: String?, val device: String, val build: String?)
    data class Key(val owner: Owner, val surface: UUID)
    enum class Settlement { DELIVERED, UNDELIVERABLE, ABANDONED }
    enum class Failure { UNCONFIRMED, REFUSED, UNSUPPORTED, INVALID_ACK }
    data class Status(val pendingUnits: Int, val pendingBytes: Int, val failure: Failure? = null)
    sealed interface SendResult {
        data object AwaitingAcknowledgement : SendResult
        data class Acknowledged(val acknowledgement: TerminalInputAcknowledgement) : SendResult
        data object AppliedWithoutIdentity : SendResult
        data object Refused : SendResult
        data object Failed : SendResult
        data object Unavailable : SendResult
    }
    interface Transport<P> {
        val supportsIdentifiedInput: Boolean
        suspend fun sendOnLane(payload: P, delivery: TerminalInputDelivery): SendResult
        suspend fun sendOverRpc(payload: P, delivery: TerminalInputDelivery): SendResult
    }
    class Ticket internal constructor(internal val answer: CompletableDeferred<Settlement>) {
        val settled: Deferred<Settlement> get() = answer
    }
    private inner class Unit(val payload: P, val tickets: List<Ticket> = listOf(Ticket(CompletableDeferred()))) {
        var uncertain = false
    }
    private inner class Slot(val key: Key) {
        var outbox = TerminalInputOutbox<Unit>(key.surface, maximumPendingBytes = maximumPendingBytes)
        var connection: Binding? = null
        var worker: Job? = null
        var generation = 0L
        // Revision fences a send result when a concurrent negative ACK rewound/rebased it.
        var revision = 0L
        var failures = 0
        var retryAt: Long? = null
        var ackSince: Long? = null
        var unavailableSince: Long? = null
        var failure: Failure? = null
        val wake = Channel<kotlin.Unit>(Channel.CONFLATED)
    }
    inner class Binding internal constructor(val key: Key, internal val transport: Transport<P>) : AutoCloseable {
        internal var identityAccepted = transport.supportsIdentifiedInput
        fun receive(acknowledgement: TerminalInputAcknowledgement) {
            val slot = slots[key]?.takeIf { it.connection === this && it.failure == null } ?: return
            apply(slot, acknowledgement)
        }
        /** Call when a lane becomes ready. This never resets the retry budget. */
        fun resume() { slots[key]?.takeIf { it.connection === this }?.let(::wake) }
        /** A lost path may have written input. Preserve its identity when trying another path. */
        fun pathLost() {
            val slot = slots[key]?.takeIf { it.connection === this && it.failure == null } ?: return
            if (slot.outbox.entries.any { it.sent }) {
                slot.outbox.rewind(); slot.revision++; slot.ackSince = null
                retry(slot, Failure.UNCONFIRMED)
            }
            wake(slot)
        }
        override fun close() {
            val slot = slots[key]?.takeIf { it.connection === this } ?: return
            slot.connection = null
            slot.unavailableSince = now()
            restart(slot)
        }
    }
    private val slots = LinkedHashMap<Key, Slot>()
    private val state = MutableStateFlow<Map<Key, Status>>(emptyMap())
    val status = state.asStateFlow()
    private var closed = false
    init {
        require(acknowledgementTimeoutMs > 0 && unavailableTimeoutMs > 0 && writeTimeoutMs > 0)
        require(maximumPendingBytes > 0 && maximumTerminals > 0)
    }

    /** Called only with an authenticated, admitted connection for this exact account/Mac/terminal. */
    fun bind(key: Key, transport: Transport<P>): Binding? {
        if (closed) return null
        val slot = slots[key] ?: run {
            if (slots.size >= maximumTerminals) {
                val idle = slots.values.firstOrNull { it.connection == null && it.outbox.entries.isEmpty() && it.failure == null }
                    ?: return null
                remove(idle)
            }
            Slot(key).also { slots[key] = it }
        }
        val binding = Binding(key, transport)
        slot.connection = binding; slot.unavailableSince = null
        if (!transport.supportsIdentifiedInput && slot.outbox.entries.isNotEmpty()) fail(slot, Failure.UNSUPPORTED)
        restart(slot); publish()
        return binding
    }

    /** Null means not admitted or full; no older unit is discarded to admit a new one. */
    fun submit(key: Key, payload: P, bytes: Int): Ticket? {
        require(bytes >= 0)
        val slot = slots[key]?.takeIf { !closed && it.failure == null && it.connection?.identityAccepted == true && it.connection?.transport?.supportsIdentifiedInput == true }
            ?: return null
        // Merging must not turn the byte/unit limits into an unbounded list of waiters.
        if (slot.outbox.entries.sumOf { it.payload.tickets.size } >= TerminalInputOutbox.MAX_UNITS) return null
        val unit = Unit(payload)
        val merged = slot.outbox.mergeLast(bytes) { last ->
            merge(last.payload, payload)?.let { Unit(it, last.tickets + unit.tickets) }
        }
        if (!merged) slot.outbox.enqueue(unit, bytes) ?: return null
        publish(); wake(slot)
        return unit.tickets.single()
    }

    /** Explicit user recovery after checking the terminal. Never replays abandoned payloads. */
    fun resumeAfterFailure(key: Key): Boolean {
        val slot = slots[key]?.takeIf { !closed && it.failure != null && it.outbox.entries.isEmpty() } ?: return false
        slot.failure = null; slot.failures = 0; slot.retryAt = null; slot.ackSince = null
        slot.outbox = TerminalInputOutbox(key.surface, maximumPendingBytes = maximumPendingBytes)
        publish(); return true
    }

    fun abandonWhere(matches: (Key) -> Boolean) {
        slots.values.filter { matches(it.key) }.forEach { slot ->
            val pending = slot.outbox.abandon()
            remove(slot)
            pending.forEach { settle(it.payload, Settlement.ABANDONED) }
        }
        publish()
    }
    override fun close() {
        if (closed) return
        closed = true; abandonWhere { true }
    }
    private fun remove(slot: Slot) {
        slots.remove(slot.key); slot.generation++; slot.worker?.cancel(); slot.wake.close()
    }
    private fun publish() {
        state.value = slots.mapValues { (_, s) -> Status(s.outbox.entries.size, s.outbox.pendingBytes, s.failure) }
    }
    private fun wake(slot: Slot) {
        slot.wake.trySend(kotlin.Unit)
        if (slot.worker?.isActive != true && slot.failure == null && slot.outbox.entries.isNotEmpty()) {
            val run = ++slot.generation
            val task = scope.launch(start = CoroutineStart.LAZY) {
                try { pump(slot, run) }
                finally { if (slot.generation == run) slot.worker = null }
            }
            slot.worker = task; task.start()
        }
    }
    private fun restart(slot: Slot) {
        slot.generation++; slot.worker?.cancel(); slot.worker = null
        if (slot.outbox.entries.any { it.sent }) {
            slot.outbox.rewind(); slot.revision++; slot.ackSince = null
            retry(slot, Failure.UNCONFIRMED)
        }
        wake(slot)
    }
    private fun current(slot: Slot, run: Long) = !closed && slots[slot.key] === slot && slot.generation == run && slot.failure == null
    private fun pending(slot: Slot, entry: TerminalInputOutbox.Entry<Unit>) =
        slot.outbox.entries.any { it.delivery == entry.delivery && it.payload === entry.payload }

    private suspend fun waitForChange(slot: Slot, timeout: Long): Boolean =
        withTimeoutOrNull(timeout.coerceAtLeast(1)) { slot.wake.receive(); true } ?: false

    private suspend fun waitForAcknowledgement(slot: Slot) {
        val since = slot.ackSince ?: now().also { slot.ackSince = it }
        if (!waitForChange(slot, acknowledgementTimeoutMs - (now() - since))) {
            slot.outbox.rewind(); slot.revision++; slot.ackSince = null
            retry(slot, Failure.UNCONFIRMED)
        }
    }
    private suspend fun waitForAvailability(slot: Slot) {
        val since = slot.unavailableSince ?: now().also { slot.unavailableSince = it }
        if (!waitForChange(slot, unavailableTimeoutMs - (now() - since))) fail(slot, Failure.UNCONFIRMED)
    }
    private suspend fun send(operation: suspend () -> SendResult): SendResult = try {
        withTimeout(writeTimeoutMs) { operation() }
    } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); SendResult.Failed }
    catch (failure: CancellationException) { throw failure }
    catch (_: Exception) { SendResult.Failed }

    private suspend fun pump(slot: Slot, run: Long) {
        while (current(slot, run) && slot.outbox.entries.isNotEmpty()) {
            // Existing wake-ups are represented by state inspected in this iteration.
            slot.wake.tryReceive()
            slot.retryAt?.let { deadline ->
                if (deadline > now()) { waitForChange(slot, deadline - now()); return@let }
            }
            if (!current(slot, run)) return
            if ((slot.retryAt ?: Long.MIN_VALUE) > now()) continue
            slot.retryAt = null
            val binding = slot.connection
            if (binding == null) { waitForAvailability(slot); continue }
            if (!binding.identityAccepted || !binding.transport.supportsIdentifiedInput) { fail(slot, Failure.UNSUPPORTED); return }
            val entry = slot.outbox.nextUnsent()
            if (entry == null) { waitForAcknowledgement(slot); continue }
            val revision = slot.revision
            slot.outbox.markSent(entry.delivery.sequence)
            val wasUncertain = entry.payload.uncertain
            entry.payload.uncertain = true
            var result = send { binding.transport.sendOnLane(entry.payload.payload, entry.delivery) }
            if (!current(slot, run) || slot.connection !== binding || !pending(slot, entry) || slot.revision != revision) continue
            if (result == SendResult.Unavailable) {
                entry.payload.uncertain = wasUncertain
                // A control request must not pass earlier units that may still be in flight on a lane.
                if (slot.outbox.hasUnacknowledgedSend(entry.delivery.sequence)) {
                    slot.outbox.rewind(entry.delivery.sequence)
                    waitForAcknowledgement(slot); continue
                }
                entry.payload.uncertain = true
                result = send { binding.transport.sendOverRpc(entry.payload.payload, entry.delivery) }
                if (!current(slot, run) || slot.connection !== binding || !pending(slot, entry) || slot.revision != revision) continue
            }
            when (result) {
                SendResult.AwaitingAcknowledgement -> {
                    entry.payload.uncertain = true
                    if (slot.ackSince == null) slot.ackSince = now()
                    slot.unavailableSince = null
                }
                is SendResult.Acknowledged -> {
                    entry.payload.uncertain = true
                    if (slot.ackSince == null) slot.ackSince = now()
                    apply(slot, result.acknowledgement)
                }
                SendResult.AppliedWithoutIdentity -> {
                    binding.identityAccepted = false
                    if (slot.outbox.hasUnacknowledgedSend(entry.delivery.sequence)) {
                        fail(slot, Failure.UNSUPPORTED); continue
                    }
                    apply(slot, TerminalInputAcknowledgement(TerminalInputAcknowledgement.Status.APPLIED,
                        entry.delivery.stream, entry.delivery.sequence))
                    // The host stopped honouring identity. No remaining or future unit may silently
                    // fall through to a legacy replay until the caller explicitly handles downgrade.
                    fail(slot, Failure.UNSUPPORTED)
                }
                SendResult.Failed, SendResult.Refused -> {
                    if (result == SendResult.Refused) entry.payload.uncertain = wasUncertain
                    slot.outbox.rewind(); slot.revision++; slot.ackSince = null
                    retry(slot, if (result == SendResult.Refused) Failure.REFUSED else Failure.UNCONFIRMED)
                }
                SendResult.Unavailable -> {
                    entry.payload.uncertain = wasUncertain
                    slot.outbox.rewind(entry.delivery.sequence)
                    waitForAvailability(slot)
                }
            }
        }
    }
    private fun apply(slot: Slot, ack: TerminalInputAcknowledgement) {
        val result = slot.outbox.apply(ack)
        if (result.outcome == TerminalInputOutbox.Outcome.IGNORED) return
        val progressed = result.delivered.isNotEmpty() || result.undeliverable.isNotEmpty()
        if (progressed) { slot.failures = 0; slot.retryAt = null; slot.ackSince = null }
        when (result.outcome) {
            TerminalInputOutbox.Outcome.INVALID -> fail(slot, Failure.INVALID_ACK)
            TerminalInputOutbox.Outcome.RESEND, TerminalInputOutbox.Outcome.RETRY_LATER -> {
                slot.revision++; slot.ackSince = null; retry(slot, Failure.UNCONFIRMED)
            }
            TerminalInputOutbox.Outcome.UNDELIVERABLE -> fail(slot, Failure.REFUSED)
            else -> kotlin.Unit
        }
        // State is updated before tickets complete; a continuation may submit more input.
        publish()
        result.delivered.forEach { settle(it.payload, Settlement.DELIVERED) }
        result.undeliverable.forEach { settle(it.payload, Settlement.UNDELIVERABLE) }
        wake(slot)
    }
    private fun retry(slot: Slot, reason: Failure) {
        slot.failures++
        if (slot.failures >= MAXIMUM_FAILURES) fail(slot, reason)
        else slot.retryAt = now() + retryDelayMs(slot.failures).coerceAtLeast(1)
    }
    private fun fail(slot: Slot, reason: Failure) {
        val pending = slot.outbox.abandon()
        slot.failure = reason; slot.retryAt = null; slot.ackSince = null; slot.revision++
        publish()
        pending.forEach {
            val settlement = if (reason == Failure.REFUSED && !it.payload.uncertain) Settlement.UNDELIVERABLE else Settlement.ABANDONED
            settle(it.payload, settlement)
        }
    }
    private fun settle(unit: Unit, settlement: Settlement) { unit.tickets.forEach { it.answer.complete(settlement) } }
    companion object { const val MAXIMUM_FAILURES = 3 }
}
