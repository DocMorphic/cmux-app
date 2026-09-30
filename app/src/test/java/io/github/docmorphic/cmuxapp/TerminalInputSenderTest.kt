package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalInputSenderTest {
    private val owner = TerminalInputSender.Owner("login-one", "account", "team", "mac", "stable")
    private val key = TerminalInputSender.Key(owner, UUID.fromString("11111111-2222-3333-4444-555555555555"))
    private fun answer(d: TerminalInputDelivery, status: TerminalInputAcknowledgement.Status = TerminalInputAcknowledgement.Status.APPLIED,
        expected: ULong = 0uL) = TerminalInputAcknowledgement(status, d.stream, d.sequence, expected)
    private fun accepted(d: TerminalInputDelivery) = TerminalInputSender.SendResult.Acknowledged(answer(d))
    private class Peer : TerminalInputSender.Transport<String> {
        override var supportsIdentifiedInput = true
        val laneWrites = mutableListOf<Pair<String, TerminalInputDelivery>>()
        val rpcWrites = mutableListOf<Pair<String, TerminalInputDelivery>>()
        var lane: suspend (String, TerminalInputDelivery) -> TerminalInputSender.SendResult = { _, _ -> TerminalInputSender.SendResult.Unavailable }
        var rpc: suspend (String, TerminalInputDelivery) -> TerminalInputSender.SendResult = { _, d ->
            TerminalInputSender.SendResult.Acknowledged(TerminalInputAcknowledgement(TerminalInputAcknowledgement.Status.APPLIED, d.stream, d.sequence))
        }
        override suspend fun sendOnLane(payload: String, delivery: TerminalInputDelivery): TerminalInputSender.SendResult {
            laneWrites += payload to delivery; return lane(payload, delivery)
        }
        override suspend fun sendOverRpc(payload: String, delivery: TerminalInputDelivery): TerminalInputSender.SendResult {
            rpcWrites += payload to delivery; return rpc(payload, delivery)
        }
    }
    private fun TestScope.sender(bytes: Int = 1024, terminals: Int = 64, merge: (String, String) -> String? = { _, _ -> null }) = TerminalInputSender<String>(this,
        now = { testScheduler.currentTime }, acknowledgementTimeoutMs = 100, unavailableTimeoutMs = 500,
        writeTimeoutMs = 100, retryDelayMs = { 10L * it }, maximumPendingBytes = bytes, maximumTerminals = terminals, merge = merge)

    @Test fun pipelinedLaneInputBlocksRpcUntilCumulativeAcknowledgement() = runTest {
        sender().use { sender ->
            val peer = Peer().apply { lane = { text, _ -> if (text == "paste") TerminalInputSender.SendResult.Unavailable else TerminalInputSender.SendResult.AwaitingAcknowledgement } }
            val binding = sender.bind(key, peer)!!
            val one = sender.submit(key, "a", 1)!!; val two = sender.submit(key, "b", 1)!!; val paste = sender.submit(key, "paste", 5)!!
            runCurrent()
            assertEquals(listOf("a", "b", "paste"), peer.laneWrites.map { it.first })
            assertTrue(peer.rpcWrites.isEmpty()); assertFalse(one.settled.isCompleted)
            binding.receive(answer(peer.laneWrites[1].second)); runCurrent()
            assertEquals(listOf("paste"), peer.rpcWrites.map { it.first })
            assertEquals(TerminalInputSender.Settlement.DELIVERED, one.settled.await())
            assertEquals(TerminalInputSender.Settlement.DELIVERED, two.settled.await())
            assertEquals(TerminalInputSender.Settlement.DELIVERED, paste.settled.await())
        }
    }

    @Test fun droppedAckRetriesSameIdentityAndDeduplicatingPeerAppliesOnce() = runTest {
        sender().use { sender ->
            val applied = mutableSetOf<TerminalInputDelivery>()
            val peer = Peer().apply { lane = { _, d ->
                if (applied.add(d)) TerminalInputSender.SendResult.AwaitingAcknowledgement
                else TerminalInputSender.SendResult.Acknowledged(answer(d, TerminalInputAcknowledgement.Status.DUPLICATE))
            } }
            sender.bind(key, peer)
            val ticket = sender.submit(key, "key", 3)!!
            advanceUntilIdle()
            assertEquals(2, peer.laneWrites.size); assertEquals(1, applied.size)
            assertEquals(peer.laneWrites[0].second, peer.laneWrites[1].second)
            assertEquals(TerminalInputSender.Settlement.DELIVERED, ticket.settled.await())
            assertTrue(peer.rpcWrites.isEmpty())
        }
    }

    @Test fun laneLossReusesIdentityOverRpcWithoutOvertakingEarlierUnits() = runTest {
        sender().use { sender ->
            var ready = true
            val applied = mutableSetOf<TerminalInputDelivery>()
            val peer = Peer().apply {
                lane = { _, d -> if (ready) { applied.add(d); TerminalInputSender.SendResult.AwaitingAcknowledgement } else TerminalInputSender.SendResult.Unavailable }
                rpc = { _, d -> assertFalse(applied.add(d)); accepted(d) }
            }
            val binding = sender.bind(key, peer)!!
            val a = sender.submit(key, "a", 1)!!; val b = sender.submit(key, "b", 1)!!
            runCurrent(); ready = false; binding.pathLost(); advanceUntilIdle()
            assertEquals(listOf("a", "b"), peer.rpcWrites.map { it.first })
            assertEquals(peer.laneWrites.take(2).map { it.second }, peer.rpcWrites.map { it.second })
            assertEquals(2, applied.size)
            assertEquals(TerminalInputSender.Settlement.DELIVERED, a.settled.await())
            assertEquals(TerminalInputSender.Settlement.DELIVERED, b.settled.await())
        }
    }

    @Test fun busyRepliesCannotResetTheirOwnBudgetAndRequireExplicitRecovery() = runTest {
        sender().use { sender ->
            val peer = Peer().apply { rpc = { _, d -> TerminalInputSender.SendResult.Acknowledged(answer(d, TerminalInputAcknowledgement.Status.BUSY)) } }
            sender.bind(key, peer)
            val ticket = sender.submit(key, "key", 3)!!
            advanceUntilIdle()
            assertEquals(3, peer.rpcWrites.size)
            assertEquals(1, peer.rpcWrites.map { it.second }.toSet().size)
            assertEquals(TerminalInputSender.Settlement.ABANDONED, ticket.settled.await())
            assertEquals(TerminalInputSender.Failure.UNCONFIRMED, sender.status.value[key]!!.failure)
            assertNull(sender.submit(key, "automatic new stream", 3))
            assertTrue(sender.resumeAfterFailure(key))
            peer.rpc = { _, d -> accepted(d) }
            val next = sender.submit(key, "explicit", 8)!!; advanceUntilIdle()
            assertEquals(TerminalInputSender.Settlement.DELIVERED, next.settled.await())
            assertNotEquals(peer.rpcWrites.first().second.stream, peer.rpcWrites.last().second.stream)
        }
    }

    @Test fun repeatedGapsAndMismatchesAreAlsoBoundedWithoutProgress() = runTest {
        for (status in listOf(TerminalInputAcknowledgement.Status.GAP, TerminalInputAcknowledgement.Status.SURFACE_MISMATCH)) {
            sender().use { sender ->
                val peer = Peer().apply { rpc = { _, d -> TerminalInputSender.SendResult.Acknowledged(answer(d, status, 1uL)) } }
                sender.bind(key, peer)
                val ticket = sender.submit(key, "x", 1)!!; advanceUntilIdle()
                assertEquals(3, peer.rpcWrites.size)
                assertEquals(TerminalInputSender.Settlement.ABANDONED, ticket.settled.await())
            }
        }
    }

    @Test fun permanentRefusalDistinguishesUnwrittenFromPreviouslyUncertainInput() = runTest {
        for (uncertainFirst in listOf(false, true)) {
            sender().use { sender ->
                var attempts = 0
                val peer = Peer().apply { rpc = { _, _ ->
                    if (attempts++ == 0 && uncertainFirst) TerminalInputSender.SendResult.Failed else TerminalInputSender.SendResult.Refused
                } }
                sender.bind(key, peer)
                val first = sender.submit(key, "first", 5)!!; val later = sender.submit(key, "later", 5)!!
                advanceUntilIdle()
                assertEquals(3, peer.rpcWrites.size)
                assertEquals(if (uncertainFirst) TerminalInputSender.Settlement.ABANDONED else TerminalInputSender.Settlement.UNDELIVERABLE, first.settled.await())
                assertEquals(TerminalInputSender.Settlement.UNDELIVERABLE, later.settled.await())
                assertEquals(listOf("first", "first", "first"), peer.rpcWrites.map { it.first })
            }
        }
    }

    @Test fun replacementFencesLateSendAndOldBindingAcknowledgements() = runTest {
        sender().use { sender ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val old = Peer().apply { lane = { _, d -> withContext(NonCancellable) { entered.complete(Unit); release.await(); accepted(d) } } }
            val oldBinding = sender.bind(key, old)!!
            val ticket = sender.submit(key, "x", 1)!!; runCurrent(); entered.await()
            val next = Peer().apply { lane = { _, _ -> TerminalInputSender.SendResult.AwaitingAcknowledgement } }
            val nextBinding = sender.bind(key, next)!!
            advanceTimeBy(10); runCurrent()
            assertEquals(old.laneWrites.single().second, next.laneWrites.single().second)
            oldBinding.receive(answer(old.laneWrites.single().second)); oldBinding.close()
            release.complete(Unit); runCurrent()
            assertFalse(ticket.settled.isCompleted)
            nextBinding.receive(answer(next.laneWrites.single().second)); runCurrent()
            assertEquals(TerminalInputSender.Settlement.DELIVERED, ticket.settled.await())
            val second = sender.submit(key, "new", 3)!!; runCurrent()
            nextBinding.receive(answer(next.laneWrites.last().second)); runCurrent()
            assertEquals(TerminalInputSender.Settlement.DELIVERED, second.settled.await())
        }
    }

    @Test fun synchronousAckDuringWriteCannotCauseRetryOrDuplicateSettlement() = runTest {
        sender().use { sender ->
            lateinit var binding: TerminalInputSender<String>.Binding
            val peer = Peer().apply { lane = { _, d -> binding.receive(answer(d)); TerminalInputSender.SendResult.Failed } }
            binding = sender.bind(key, peer)!!
            val ticket = sender.submit(key, "x", 1)!!; advanceUntilIdle()
            assertEquals(1, peer.laneWrites.size); assertTrue(peer.rpcWrites.isEmpty())
            assertEquals(TerminalInputSender.Settlement.DELIVERED, ticket.settled.await())
            assertNull(sender.status.value[key]!!.failure)
        }
    }

    @Test fun unsupportedReplacementAbandonsUncertainInputWithoutAnyLegacyWrite() = runTest {
        sender().use { sender ->
            val old = Peer().apply { lane = { _, _ -> TerminalInputSender.SendResult.AwaitingAcknowledgement } }
            sender.bind(key, old)
            val ticket = sender.submit(key, "x", 1)!!; runCurrent()
            val downgrade = Peer().apply { supportsIdentifiedInput = false }
            sender.bind(key, downgrade); advanceUntilIdle()
            assertEquals(TerminalInputSender.Settlement.ABANDONED, ticket.settled.await())
            assertNull(sender.submit(key, "new", 3)); assertTrue(downgrade.laneWrites.isEmpty()); assertTrue(downgrade.rpcWrites.isEmpty())
        }
    }

    @Test fun unidentifiedSuccessIsNeverRetriedAndBlocksLaterAutomaticDelivery() = runTest {
        sender().use { sender ->
            val peer = Peer().apply { rpc = { _, _ -> TerminalInputSender.SendResult.AppliedWithoutIdentity } }
            sender.bind(key, peer)
            val one = sender.submit(key, "one", 3)!!; val two = sender.submit(key, "two", 3)!!; advanceUntilIdle()
            assertEquals(TerminalInputSender.Settlement.DELIVERED, one.settled.await())
            assertEquals(TerminalInputSender.Settlement.ABANDONED, two.settled.await())
            assertEquals(1, peer.rpcWrites.size); assertNull(sender.submit(key, "new", 3))
            assertTrue(sender.resumeAfterFailure(key)); assertNull(sender.submit(key, "still unnegotiated", 18))
        }
    }

    @Test fun accountMacAndBuildScopesDoNotRerouteAndOldOwnerRetirementSettlesOnce() = runTest {
        sender().use { sender ->
            val old = Peer().apply { lane = { _, _ -> TerminalInputSender.SendResult.AwaitingAcknowledgement } }
            val otherKey = key.copy(owner = owner.copy(login = "new-login", device = "other-mac", build = "nightly"))
            val other = Peer()
            val binding = sender.bind(key, old)!!; sender.bind(otherKey, other)
            val a = sender.submit(key, "old", 3)!!; val b = sender.submit(otherKey, "new", 3)!!; runCurrent()
            sender.abandonWhere { it.owner == owner }
            binding.receive(answer(old.laneWrites.single().second)); binding.resume(); advanceUntilIdle()
            assertEquals(TerminalInputSender.Settlement.ABANDONED, a.settled.await())
            assertEquals(TerminalInputSender.Settlement.DELIVERED, b.settled.await())
            assertEquals(listOf("new"), other.rpcWrites.map { it.first }); assertFalse(sender.status.value.containsKey(key))
        }
    }

    @Test fun noAcknowledgementOrUnavailablePathEventuallySettlesAndNewKeysCannotExtendDeadline() = runTest {
        sender().use { sender ->
            val peer = Peer().apply { lane = { _, _ -> TerminalInputSender.SendResult.AwaitingAcknowledgement } }
            val binding = sender.bind(key, peer)!!
            val one = sender.submit(key, "one", 3)!!; runCurrent()
            repeat(4) { advanceTimeBy(20); binding.resume(); sender.submit(key, "more", 4); runCurrent() }
            advanceUntilIdle()
            assertEquals(TerminalInputSender.Settlement.ABANDONED, one.settled.await())
            assertTrue(testScheduler.currentTime <= 350)
        }
        sender().use { sender ->
            val peer = Peer().apply { rpc = { _, _ -> TerminalInputSender.SendResult.Unavailable } }
            sender.bind(key, peer)
            val ticket = sender.submit(key, "x", 1)!!; advanceUntilIdle()
            assertEquals(TerminalInputSender.Settlement.ABANDONED, ticket.settled.await())
        }
    }

    @Test fun detachedBindingRetainsIdentityForReconnectButDoesNotWaitForever() = runTest {
        sender().use { sender ->
            val peer = Peer().apply { lane = { _, _ -> TerminalInputSender.SendResult.AwaitingAcknowledgement } }
            val old = sender.bind(key, peer)!!
            val ticket = sender.submit(key, "x", 1)!!; runCurrent(); old.close(); runCurrent()
            advanceTimeBy(50)
            val next = Peer(); sender.bind(key, next); advanceUntilIdle()
            assertEquals(peer.laneWrites.single().second, next.rpcWrites.single().second)
            assertEquals(TerminalInputSender.Settlement.DELIVERED, ticket.settled.await())
            val binding = sender.bind(key, peer)!!
            val lost = sender.submit(key, "lost", 4)!!; runCurrent(); binding.close(); advanceUntilIdle()
            assertEquals(TerminalInputSender.Settlement.ABANDONED, lost.settled.await())
        }
    }

    @Test fun boundedQueuesRefuseNewInputWithoutDiscardingOldUnitsAndBoundIdleTerminals() = runTest {
        sender(bytes = 4, terminals = 1).use { sender ->
            val peer = Peer().apply { lane = { _, _ -> TerminalInputSender.SendResult.AwaitingAcknowledgement } }
            val binding = sender.bind(key, peer)!!
            val ticket = sender.submit(key, "four", 4)!!
            assertNull(sender.submit(key, "overflow", 1)); runCurrent()
            assertEquals(1, sender.status.value[key]!!.pendingUnits)
            val other = key.copy(surface = UUID.randomUUID())
            assertNull(sender.bind(other, Peer()))
            binding.receive(answer(peer.laneWrites.single().second)); runCurrent(); binding.close()
            assertNotNull(sender.bind(other, Peer()))
            assertFalse(sender.status.value.containsKey(key))
            assertEquals(TerminalInputSender.Settlement.DELIVERED, ticket.settled.await())
        }
    }

    @Test fun invalidFutureAckCannotReportUnsentInputAsDelivered() = runTest {
        sender().use { sender ->
            val peer = Peer().apply { lane = { _, _ -> TerminalInputSender.SendResult.AwaitingAcknowledgement } }
            val binding = sender.bind(key, peer)!!
            val ticket = sender.submit(key, "x", 1)!!; runCurrent()
            val sent = peer.laneWrites.single().second
            binding.receive(answer(sent.copy(sequence = 2uL))); advanceUntilIdle()
            assertEquals(TerminalInputSender.Settlement.ABANDONED, ticket.settled.await())
            assertEquals(TerminalInputSender.Failure.INVALID_ACK, sender.status.value[key]!!.failure)
        }
    }
    @Test fun hostForgotLedgerRebasesPendingPayloadAndIgnoresLateOldStreamAck() = runTest {
        sender().use { sender ->
            val peer = Peer()
            val binding = sender.bind(key, peer)!!
            val first = sender.submit(key, "first", 5)!!; runCurrent()
            assertEquals(TerminalInputSender.Settlement.DELIVERED, first.settled.await())
            peer.rpc = { _, d -> TerminalInputSender.SendResult.Acknowledged(answer(d, TerminalInputAcknowledgement.Status.GAP, 1uL)) }
            val second = sender.submit(key, "same payload", 12)!!; runCurrent()
            val old = peer.rpcWrites.last().second
            assertEquals(2uL, old.sequence)
            peer.rpc = { _, _ -> TerminalInputSender.SendResult.AwaitingAcknowledgement }
            advanceTimeBy(10); runCurrent()
            val rebased = peer.rpcWrites.last().second
            assertNotEquals(old.stream, rebased.stream); assertEquals(1uL, rebased.sequence)
            assertEquals("same payload", peer.rpcWrites.last().first)
            binding.receive(answer(old)); runCurrent(); assertFalse(second.settled.isCompleted)
            binding.receive(answer(rebased)); runCurrent()
            assertEquals(TerminalInputSender.Settlement.DELIVERED, second.settled.await())
        }
    }

    @Test fun writeTimeoutRetriesAreBoundedAndActualProgressRestoresBudget() = runTest {
        sender().use { sender ->
            var attempts = 0
            val peer = Peer().apply { rpc = { _, d ->
                if (++attempts % 3 == 0) accepted(d) else awaitCancellation()
            } }
            sender.bind(key, peer)
            val first = sender.submit(key, "first", 5)!!; val second = sender.submit(key, "second", 6)!!
            advanceUntilIdle()
            assertEquals(TerminalInputSender.Settlement.DELIVERED, first.settled.await())
            assertEquals(TerminalInputSender.Settlement.DELIVERED, second.settled.await())
            assertEquals(6, peer.rpcWrites.size)
            assertEquals(1, peer.rpcWrites.take(3).map { it.second }.toSet().size)
            assertEquals(1, peer.rpcWrites.drop(3).map { it.second }.toSet().size)
            assertNull(sender.status.value[key]!!.failure)
        }
    }

    @Test fun terminalDisappearanceSettlesAllPendingAndStopsAutomaticNewInput() = runTest {
        sender().use { sender ->
            val peer = Peer().apply { lane = { _, _ -> TerminalInputSender.SendResult.AwaitingAcknowledgement } }
            val binding = sender.bind(key, peer)!!
            val first = sender.submit(key, "a", 1)!!; val second = sender.submit(key, "b", 1)!!; runCurrent()
            binding.receive(answer(peer.laneWrites.first().second, TerminalInputAcknowledgement.Status.TERMINAL_UNAVAILABLE))
            advanceUntilIdle()
            assertEquals(TerminalInputSender.Settlement.UNDELIVERABLE, first.settled.await())
            assertEquals(TerminalInputSender.Settlement.UNDELIVERABLE, second.settled.await())
            assertNull(sender.submit(key, "automatic", 9)); assertTrue(peer.rpcWrites.isEmpty())
        }
    }

    @Test fun unsentTextCanMergeButRewindCannotMutateAnAlreadyUsedIdentity() = runTest {
        sender(merge = { a, b -> a + b }).use { sender ->
            val peer = Peer().apply { lane = { _, _ -> TerminalInputSender.SendResult.AwaitingAcknowledgement } }
            val binding = sender.bind(key, peer)!!
            val a = sender.submit(key, "a", 1)!!; val b = sender.submit(key, "b", 1)!!
            assertEquals(1, sender.status.value[key]!!.pendingUnits); runCurrent()
            assertEquals("ab", peer.laneWrites.single().first)
            binding.pathLost()
            val c = sender.submit(key, "c", 1)!!; val d = sender.submit(key, "d", 1)!!
            advanceTimeBy(10); runCurrent()
            assertEquals(listOf("ab", "ab", "cd"), peer.laneWrites.map { it.first })
            assertEquals(peer.laneWrites[0].second, peer.laneWrites[1].second)
            binding.receive(answer(peer.laneWrites.last().second)); runCurrent()
            for (ticket in listOf(a, b, c, d)) assertEquals(TerminalInputSender.Settlement.DELIVERED, ticket.settled.await())
            assertEquals(0, sender.status.value[key]!!.pendingBytes)
        }
    }

    @Test fun repeatedReconnectsDoNotRefundRetryBudget() = runTest {
        sender().use { sender ->
            val peers = mutableListOf<Peer>()
            fun peer() = Peer().apply { lane = { _, _ -> TerminalInputSender.SendResult.AwaitingAcknowledgement }; peers += this }
            sender.bind(key, peer())
            val ticket = sender.submit(key, "key", 3)!!; runCurrent()
            repeat(3) { sender.bind(key, peer()); advanceTimeBy(25); runCurrent() }
            assertEquals(3, peers.sumOf { it.laneWrites.size })
            assertEquals(TerminalInputSender.Settlement.ABANDONED, ticket.settled.await())
            assertNull(sender.submit(key, "new", 3))
        }
    }

}
