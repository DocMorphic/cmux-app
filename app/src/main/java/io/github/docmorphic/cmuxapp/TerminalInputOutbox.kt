package io.github.docmorphic.cmuxapp

import java.util.UUID

/** Single-owner model. The session sender owns dispatch, owner admission and bounded retry timing. */
internal class TerminalInputOutbox<T>(val surface: UUID, stream: UUID = UUID.randomUUID(),
    val maximumPendingBytes: Int = 16 * 1024 * 1024,
    private val freshStream: () -> UUID = UUID::randomUUID) {
    data class Entry<T>(val delivery: TerminalInputDelivery, val payload: T, val bytes: Int,
        val sent: Boolean = false, val everSent: Boolean = false)
    enum class Outcome { IGNORED, PROGRESSED, RESEND, RETRY_LATER, UNDELIVERABLE, INVALID }
    data class Result<T>(val outcome: Outcome, val delivered: List<Entry<T>> = emptyList(),
        val undeliverable: List<Entry<T>> = emptyList())
    init { require(maximumPendingBytes > 0) }
    var stream = stream; private set
    private var next = 1uL
    private var exhausted = false
    private var highestSent = 0uL
    private val pending = mutableListOf<Entry<T>>()
    val entries: List<Entry<T>> get() = pending.toList()
    var pendingBytes = 0; private set
    fun enqueue(payload: T, bytes: Int): Entry<T>? {
        require(bytes >= 0)
        if (exhausted || bytes > maximumPendingBytes - pendingBytes || pending.size >= MAX_UNITS) return null
        val entry = Entry(TerminalInputDelivery(surface, stream, next), payload, bytes)
        pending += entry; pendingBytes += bytes
        if (next == ULong.MAX_VALUE) exhausted = true else next++
        return entry
    }
    fun nextUnsent(): Entry<T>? = pending.firstOrNull { !it.sent }
    fun markSent(sequence: ULong) {
        val index = pending.indexOfFirst { it.delivery.sequence == sequence }
        if (index < 0) return
        check(pending.take(index).all { it.sent }) { "Input cannot overtake an earlier unit" }
        pending[index] = pending[index].copy(sent = true, everSent = true)
        highestSent = maxOf(highestSent, sequence)
    }
    fun rewind(from: ULong = 1uL) {
        pending.indices.filter { pending[it].delivery.sequence >= from }.forEach { index -> pending[index] = pending[index].copy(sent = false) }
    }
    fun hasUnacknowledgedSend(before: ULong) = pending.any { it.sent && it.delivery.sequence < before }
    /** Rewinding a sent unit does not make its identity safe to reuse for different text. */
    fun mergeLast(bytes: Int, merge: (T) -> T?): Boolean {
        require(bytes >= 0)
        val last = pending.lastOrNull() ?: return false
        if (last.everSent || bytes > maximumPendingBytes - pendingBytes) return false
        val value = merge(last.payload) ?: return false
        pending[pending.lastIndex] = last.copy(payload = value, bytes = last.bytes + bytes)
        pendingBytes += bytes; return true
    }
    fun abandon(): List<Entry<T>> = entries.also { pending.clear(); pendingBytes = 0 }
    private fun acknowledge(through: ULong): List<Entry<T>> {
        val removed = pending.filter { it.delivery.sequence <= through }
        pending.removeAll { it.delivery.sequence <= through }; pendingBytes -= removed.sumOf { it.bytes }
        return removed
    }
    fun apply(ack: TerminalInputAcknowledgement): Result<T> {
        if (ack.stream != stream || pending.isEmpty()) return Result(Outcome.IGNORED)
        if (ack.sequence > highestSent || (ack.status == TerminalInputAcknowledgement.Status.GAP &&
                ack.expected > highestSent && (highestSent == ULong.MAX_VALUE || ack.expected != highestSent + 1uL)))
            return Result(Outcome.INVALID)
        val first = pending.first().delivery.sequence
        // Late negative acknowledgements for an already settled unit cannot rewind or
        // abandon newer input. A cumulative applied/duplicate answer can still progress.
        if (ack.sequence < first && ack.status !in setOf(TerminalInputAcknowledgement.Status.APPLIED, TerminalInputAcknowledgement.Status.DUPLICATE))
            return Result(Outcome.IGNORED)
        return when (ack.status) {
            TerminalInputAcknowledgement.Status.APPLIED, TerminalInputAcknowledgement.Status.DUPLICATE -> {
                val delivered = acknowledge(ack.sequence)
                Result(if (delivered.isEmpty()) Outcome.IGNORED else Outcome.PROGRESSED, delivered)
            }
            TerminalInputAcknowledgement.Status.REJECTED -> {
                val removed = acknowledge(ack.sequence)
                Result(Outcome.PROGRESSED, removed.filter { it.delivery.sequence != ack.sequence }, removed.filter { it.delivery.sequence == ack.sequence })
            }
            TerminalInputAcknowledgement.Status.GAP -> {
                if (ack.expected < first) {
                    rebase(); Result(Outcome.RESEND)
                } else {
                    val delivered = if (ack.expected > 1uL) acknowledge(ack.expected - 1uL) else emptyList()
                    rewind(ack.expected); Result(Outcome.RESEND, delivered)
                }
            }
            TerminalInputAcknowledgement.Status.BUSY -> {
                val delivered = if (ack.sequence > 1uL) acknowledge(ack.sequence - 1uL) else emptyList()
                rewind(ack.sequence); Result(Outcome.RETRY_LATER, delivered)
            }
            TerminalInputAcknowledgement.Status.SURFACE_MISMATCH -> { rewind(ack.sequence); Result(Outcome.RESEND) }
            TerminalInputAcknowledgement.Status.TERMINAL_UNAVAILABLE -> Result(Outcome.UNDELIVERABLE, undeliverable = abandon())
        }
    }
    private fun rebase() {
        val replacement = freshStream(); check(replacement != stream) { "Input stream identity must change" }
        stream = replacement; highestSent = 0uL; exhausted = false; next = 1uL
        pending.indices.forEach { index ->
            pending[index] = pending[index].copy(delivery = TerminalInputDelivery(surface, stream, next++), sent = false)
        }
    }
    companion object { const val MAX_UNITS = 4096 }
}
