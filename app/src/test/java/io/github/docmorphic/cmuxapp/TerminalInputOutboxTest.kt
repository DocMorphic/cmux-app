package io.github.docmorphic.cmuxapp

import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class TerminalInputOutboxTest {
    private val surface = UUID.randomUUID()
    private fun box(cap: Int = 100) = TerminalInputOutbox<String>(surface, maximumPendingBytes = cap)
    private fun ack(box: TerminalInputOutbox<String>, status: TerminalInputAcknowledgement.Status, seq: Int, expected: Int = 0) =
        TerminalInputAcknowledgement(status, box.stream, seq.toULong(), expected.toULong())
    private fun sent(box: TerminalInputOutbox<String>, text: String): TerminalInputOutbox.Entry<String> =
        box.enqueue(text, text.length)!!.also { box.markSent(it.delivery.sequence) }
    @Test fun byteAndUnitCapsRefuseNewInputWithoutDiscardingOlderUnits() {
        val box = box(3); box.enqueue("ab", 2)
        assertNull(box.enqueue("cd", 2)); assertEquals(listOf("ab"), box.entries.map { it.payload }); assertEquals(2, box.pendingBytes)
        assertFalse(box.mergeLast(2) { it + "cd" }); assertTrue(box.mergeLast(1) { it + "c" })
        assertEquals(3, box.pendingBytes)
        val units = box()
        repeat(TerminalInputOutbox.MAX_UNITS) { assertNotNull(units.enqueue("", 0)) }
        assertNull(units.enqueue("", 0))
        assertThrows(IllegalArgumentException::class.java) { box.enqueue("", -1) }
    }
    @Test fun sendDoesNotSettleAndRewindKeepsTheExactPayloadAndIdentity() {
        val box = box(); val first = sent(box, "one"); sent(box, "two")
        assertNull(box.nextUnsent()); assertEquals(2, box.entries.size)
        box.rewind(); assertEquals(first.delivery, box.nextUnsent()!!.delivery)
        assertFalse(box.mergeLast(3) { it + "NEW" })
        assertEquals(listOf("one", "two"), box.entries.map { it.payload })
    }
    @Test fun cumulativeAckSettlesOnceAndFreesCapacity() {
        val box = box(); sent(box, "a"); sent(box, "b"); box.enqueue("c", 1)
        val ack = ack(box, TerminalInputAcknowledgement.Status.APPLIED, 2)
        assertEquals(listOf("a", "b"), box.apply(ack).delivered.map { it.payload }); assertEquals(1, box.pendingBytes)
        assertEquals(TerminalInputOutbox.Outcome.IGNORED, box.apply(ack).outcome)
        assertEquals("c", box.nextUnsent()!!.payload)
    }
    @Test fun busyAndGapConfirmOnlyEarlierUnitsAndKeepLaterOnesOrdered() {
        val box = box(); sent(box, "a"); sent(box, "b"); sent(box, "c")
        val busy = box.apply(ack(box, TerminalInputAcknowledgement.Status.BUSY, 2))
        assertEquals(TerminalInputOutbox.Outcome.RETRY_LATER, busy.outcome)
        assertEquals(listOf("a"), busy.delivered.map { it.payload }); assertEquals(2uL, box.nextUnsent()!!.delivery.sequence)
        assertThrows(IllegalStateException::class.java) { box.markSent(3uL) }
        box.markSent(2uL); box.markSent(3uL)
        val gap = box.apply(ack(box, TerminalInputAcknowledgement.Status.GAP, 3, 3))
        assertEquals(listOf("b"), gap.delivered.map { it.payload }); assertEquals("c", box.nextUnsent()!!.payload)
    }
    @Test fun anotherStreamAndImpossibleFutureAcknowledgementsCannotDiscardInput() {
        val box = box(); sent(box, "a"); box.enqueue("b", 1)
        val applied = ack(box, TerminalInputAcknowledgement.Status.APPLIED, 1)
        assertEquals(TerminalInputOutbox.Outcome.IGNORED, box.apply(applied.copy(stream = UUID.randomUUID())).outcome)
        assertEquals(TerminalInputOutbox.Outcome.INVALID, box.apply(applied.copy(sequence = 2uL)).outcome)
        assertEquals(TerminalInputOutbox.Outcome.INVALID, box.apply(ack(box, TerminalInputAcknowledgement.Status.GAP, 1, 9)).outcome)
        assertEquals(2, box.entries.size)
    }
    @Test fun rejectedUnitIsReportedUndeliveredWhileEarlierUnitsAreConfirmed() {
        val box = box(); sent(box, "a"); sent(box, "image"); sent(box, "c")
        val result = box.apply(ack(box, TerminalInputAcknowledgement.Status.REJECTED, 2))
        assertEquals(listOf("a"), result.delivered.map { it.payload })
        assertEquals(listOf("image"), result.undeliverable.map { it.payload })
        assertEquals(listOf("c"), box.entries.map { it.payload })
    }
    @Test fun terminalRemovalAbandonsTheWholeStreamWithoutRedirectingIt() {
        val box = box(); sent(box, "a"); sent(box, "b")
        val result = box.apply(ack(box, TerminalInputAcknowledgement.Status.TERMINAL_UNAVAILABLE, 1))
        assertEquals(TerminalInputOutbox.Outcome.UNDELIVERABLE, result.outcome)
        assertEquals(listOf("a", "b"), result.undeliverable.map { it.payload }); assertEquals(0, box.pendingBytes)
    }
    @Test fun forgottenHostLedgerRebasesOnlyPendingUnitsAndRejectsOldStreamAcks() {
        val box = box(); sent(box, "a"); sent(box, "b"); sent(box, "c")
        box.apply(ack(box, TerminalInputAcknowledgement.Status.APPLIED, 2))
        val original = box.entries.single().delivery
        val result = box.apply(ack(box, TerminalInputAcknowledgement.Status.GAP, 3, 1))
        assertEquals(TerminalInputOutbox.Outcome.RESEND, result.outcome)
        val replacement = box.nextUnsent()!!
        assertNotEquals(original.stream, replacement.delivery.stream); assertEquals(surface, replacement.delivery.surface)
        assertEquals(1uL, replacement.delivery.sequence); assertEquals("c", replacement.payload)
        assertEquals(TerminalInputOutbox.Outcome.IGNORED, box.apply(TerminalInputAcknowledgement(TerminalInputAcknowledgement.Status.APPLIED, original.stream, 3uL)).outcome)
        assertFalse(box.mergeLast(1) { it + "d" })
    }
    @Test fun staleNegativeReplyCannotRebaseOrAbandonNewerPendingInput() {
        val box = box(); sent(box, "a"); sent(box, "b")
        box.apply(ack(box, TerminalInputAcknowledgement.Status.APPLIED, 1))
        val original = box.entries.single().delivery
        for (status in listOf(TerminalInputAcknowledgement.Status.GAP, TerminalInputAcknowledgement.Status.TERMINAL_UNAVAILABLE,
            TerminalInputAcknowledgement.Status.BUSY, TerminalInputAcknowledgement.Status.SURFACE_MISMATCH)) {
            assertEquals(TerminalInputOutbox.Outcome.IGNORED, box.apply(ack(box, status, 1, 1)).outcome)
            assertEquals(original, box.entries.single().delivery)
        }
    }
    @Test fun rpcMustWaitForEarlierUnacknowledgedLaneInput() {
        val box = box(); sent(box, "lane"); val request = box.enqueue("paste", 5)!!
        assertTrue(box.hasUnacknowledgedSend(request.delivery.sequence))
        box.apply(ack(box, TerminalInputAcknowledgement.Status.DUPLICATE, 1))
        assertFalse(box.hasUnacknowledgedSend(request.delivery.sequence)); assertEquals("paste", box.nextUnsent()!!.payload)
    }
    @Test fun wrongSurfaceReplyKeepsItsOriginalTerminalIdentity() {
        val box = box(); val first = sent(box, "a")
        assertEquals(TerminalInputOutbox.Outcome.RESEND, box.apply(ack(box, TerminalInputAcknowledgement.Status.SURFACE_MISMATCH, 1)).outcome)
        assertEquals(first.delivery, box.nextUnsent()!!.delivery)
    }
}
