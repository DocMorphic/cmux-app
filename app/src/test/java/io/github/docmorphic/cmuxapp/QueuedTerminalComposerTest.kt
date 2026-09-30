package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class QueuedTerminalComposerTest {
    private val target = TerminalDrafts.Target("mac", "workspace", "terminal")

    @Test fun observerCancellationDoesNotCancelSettlementOrClearNewerEdits() = runBlocking<Unit> {
        val drafts = TerminalDrafts().apply { edit(target, "original") }
        val send = drafts.begin(target)!!
        val entered = CompletableDeferred<Unit>(); val reply = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        TerminalInputQueue(this) { calls += "key:${it.text}" }.use { queue ->
            queue.offer("before")
            assertTrue(queueTerminalComposer(queue, drafts, send, { calls += "persist" }) {
                calls += "send:${send.text}"; entered.complete(Unit); reply.await(); emptySet()
            })
            withTimeout(3000) { entered.await() }
            val observer = launch { queue.awaitIdle() }
            observer.cancelAndJoin()
            assertEquals(send.operation, drafts.state.value[target]?.operation)
            drafts.edit(target, "newer draft")
            queue.offer("after")
            reply.complete(Unit)
            withTimeout(3000) { queue.awaitIdle() }
            assertEquals(listOf("key:before", "persist", "send:original", "persist", "key:after"), calls)
            assertEquals("newer draft", drafts.state.value[target]?.text)
            assertNull(drafts.state.value[target]?.operation); assertNull(drafts.state.value[target]?.error)
        }
    }

    @Test fun ownerRetirementReleasesPendingComposerWithoutSendingIt() = runBlocking<Unit> {
        val drafts = TerminalDrafts().apply { edit(target, "pending") }
        val send = drafts.begin(target)!!
        val started = CompletableDeferred<Unit>()
        val queue = TerminalInputQueue(this) { started.complete(Unit); awaitCancellation() }
        queue.offer("earlier")
        withTimeout(3000) { started.await() }
        var sent = false
        assertTrue(queueTerminalComposer(queue, drafts, send, {}) { sent = true; emptySet() })
        queue.close(); yield()
        assertFalse(sent)
        assertNull(drafts.state.value[target]?.operation)
        assertEquals(TerminalDrafts.DELIVERY_UNCONFIRMED, drafts.state.value[target]?.error)
        assertEquals("pending", drafts.state.value[target]?.text)
    }

    @Test fun failedPersistencePreventsTransmissionAndReleasesDraftReservation() = runBlocking<Unit> {
        val drafts = TerminalDrafts().apply { edit(target, "unsaved") }
        var sent = false
        TerminalInputQueue(this) {}.use { queue ->
            queueTerminalComposer(queue, drafts, drafts.begin(target)!!, { error("disk unavailable") }) {
                sent = true; emptySet()
            }
            withTimeout(3000) { queue.status.first { it.error != null } }
            assertFalse(sent); assertNull(drafts.state.value[target]?.operation)
            assertEquals(TerminalDrafts.DELIVERY_UNCONFIRMED, drafts.state.value[target]?.error)
        }
    }

    @Test fun accountClearDuringLateCompletionCannotClearReplacementDraft() = runBlocking<Unit> {
        val drafts = TerminalDrafts().apply { edit(target, "old account") }
        val entered = CompletableDeferred<Unit>(); val reply = CompletableDeferred<Unit>()
        TerminalInputQueue(this) {}.use { queue ->
            queueTerminalComposer(queue, drafts, drafts.begin(target)!!, {}) {
                entered.complete(Unit); reply.await(); emptySet()
            }
            withTimeout(3000) { entered.await() }
            drafts.clear(); drafts.edit(target, "new account"); val replacement = drafts.begin(target)!!
            reply.complete(Unit)
            withTimeout(3000) { queue.awaitIdle() }
            assertEquals(replacement.operation, drafts.state.value[target]?.operation)
            assertEquals("new account", drafts.state.value[target]?.text)
        }
    }
}
