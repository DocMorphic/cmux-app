package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeWorkspaceMovesTest {
    private suspend fun awaitState(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }
    private fun ids(source: NativeFeedSource) = source.workspaces.map { it.id }

    private suspend fun withMoves(block: suspend (MovePeer, NativeFeedCoordinator, NativeWorkspaceMoves) -> Unit) = coroutineScope {
        MovePeer().use { peer ->
            val job = SupervisorJob(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + job)
            val coordinator = NativeFeedCoordinator(scope, { peer.connect() }, { true })
            val moves = NativeWorkspaceMoves(scope, coordinator)
            try {
                coordinator.updateMacs(listOf(peer.mac))
                awaitState { moves.sources.value[peer.mac.origin]?.canReorderWorkspaces() == true }
                block(peer, coordinator, moves)
            } finally { peer.gate?.countDown(); moves.clear(); coordinator.close(); job.cancelAndJoin() }
        }
    }

    @Test fun rapidMovesShowFinalPredictionButSerializeThreeRequestsAgainstIntermediateSnapshots() = runBlocking {
        withMoves { peer, _, moves ->
            val gate = CountDownLatch(1); peer.gate = gate
            fun source() = moves.sources.value.getValue(peer.mac.origin)
            assertTrue(moves.enqueue(source(), "c", NativeWorkspaceMove(null, "a")))
            awaitState { peer.moves.size == 1 }
            assertTrue(moves.enqueue(source(), "b", NativeWorkspaceMove(null, "c")))
            assertTrue(moves.enqueue(source(), "d", NativeWorkspaceMove(null, "a")))
            assertEquals(listOf("b", "c", "d", "a"), ids(source()))
            assertEquals(3, moves.status.value.getValue(peer.mac.origin).pending)
            assertFalse(moves.enqueue(source(), "a", NativeWorkspaceMove(null, "b")))
            assertEquals(1, peer.moves.size)
            gate.countDown()
            awaitState { moves.status.value.getValue(peer.mac.origin).pending == 0 }
            assertEquals(3, peer.moves.size)
            assertEquals(listOf("b", "c", "d", "a"), ids(source()))
            assertEquals(listOf("c", "b", "d"), peer.moves.map { it.getString("workspace_id") })
            assertTrue(peer.moves.all { it.getString("window_id") == "window" })
            assertNull(moves.status.value.getValue(peer.mac.origin).error)
        }
    }

    @Test fun rejectionRollsBackAndDropsDependentWritesButAllowsFreshMove() = runBlocking {
        withMoves { peer, _, moves ->
            val gate = CountDownLatch(1); peer.gate = gate; peer.rejectNext = true
            fun source() = moves.sources.value.getValue(peer.mac.origin)
            assertTrue(moves.enqueue(source(), "c", NativeWorkspaceMove(null, "a")))
            awaitState { peer.moves.size == 1 }
            assertTrue(moves.enqueue(source(), "b", NativeWorkspaceMove(null, "c")))
            gate.countDown()
            awaitState { moves.status.value.getValue(peer.mac.origin).pending == 0 }
            assertEquals(1, peer.moves.size)
            assertEquals(listOf("a", "b", "c", "d"), ids(source()))
            assertTrue(moves.status.value.getValue(peer.mac.origin).error!!.contains("Move rejected"))
            assertTrue(moves.enqueue(source(), "d", NativeWorkspaceMove(null, "a")))
            awaitState { moves.status.value.getValue(peer.mac.origin).pending == 0 }
            assertEquals(2, peer.moves.size)
            assertEquals(listOf("d", "a", "b", "c"), ids(source()))
            assertNull(moves.status.value.getValue(peer.mac.origin).error)
        }
    }

    @Test fun externalPinChangeClearsPredictionAndCancelsDependentMove() = runBlocking {
        withMoves { peer, _, moves ->
            val gate = CountDownLatch(1); peer.gate = gate; peer.pinOnNextMove = true
            fun source() = moves.sources.value.getValue(peer.mac.origin)
            assertTrue(moves.enqueue(source(), "c", NativeWorkspaceMove(null, "a")))
            awaitState { peer.moves.size == 1 }
            assertTrue(moves.enqueue(source(), "b", NativeWorkspaceMove(null, "c")))
            gate.countDown()
            awaitState { moves.status.value.getValue(peer.mac.origin).pending == 0 }
            assertEquals(1, peer.moves.size)
            assertEquals(listOf("a", "b", "c", "d"), ids(source()))
            assertTrue(source().workspaces.first().isPinned)
        }
    }

    @Test fun forgettingOwnerClearsPredictionAndPreventsQueuedWrites() = runBlocking {
        withMoves { peer, coordinator, moves ->
            val gate = CountDownLatch(1); peer.gate = gate
            fun source() = moves.sources.value.getValue(peer.mac.origin)
            assertTrue(moves.enqueue(source(), "c", NativeWorkspaceMove(null, "a")))
            awaitState { peer.moves.size == 1 }
            val captured = source()
            assertTrue(moves.enqueue(captured, "b", NativeWorkspaceMove(null, "c")))
            coordinator.updateMacs(emptyList())
            awaitState { moves.sources.value.isEmpty() }
            assertFalse(moves.enqueue(captured, "d", NativeWorkspaceMove(null, "a")))
            gate.countDown()
            assertEquals(1, peer.moves.size)
            assertTrue(moves.status.value.isEmpty())
        }
    }

    @Test fun staleSnapshotWindowAndCapabilityGuardsRejectBeforeWireWrite() = runBlocking {
        withMoves { peer, coordinator, moves ->
            val original = moves.sources.value.getValue(peer.mac.origin)
            peer.order = listOf("d", "a", "b", "c")
            coordinator.refresh()
            awaitState { ids(moves.sources.value.getValue(peer.mac.origin)).first() == "d" }
            assertFalse(moves.enqueue(original, "c", NativeWorkspaceMove(null, "a")))
            val current = moves.sources.value.getValue(peer.mac.origin)
            val base = NativeWorkspaceOrder(current.workspaces, current.groups)
            assertTrue(runCatching { coordinator.moveWorkspace(peer.mac, "a", NativeWorkspaceMove(null, "missing"), base) { true } }.isFailure)
            assertTrue(runCatching { coordinator.moveWorkspace(peer.mac.copy(deviceId = "replacement"), "a", NativeWorkspaceMove(null, "b"), base) { true } }.isFailure)
            assertTrue(runCatching { coordinator.moveWorkspace(peer.mac, "a", NativeWorkspaceMove(null, "b"), base) { false } }.isFailure)
            peer.mixedWindows = true
            coordinator.refresh()
            awaitState { moves.sources.value.getValue(peer.mac.origin).workspaces.last().windowId == "another-window" }
            assertFalse(moves.enqueue(moves.sources.value.getValue(peer.mac.origin), "c", NativeWorkspaceMove(null, "a")))
            assertTrue(peer.moves.isEmpty())
            assertFalse(current.copy(capabilities = emptySet()).canReorderWorkspaces())
            assertFalse(current.copy(availability = NativeFeedAvailability.OFFLINE).canReorderWorkspaces())
            assertFalse(current.copy(workspaces = current.workspaces.map { it.copy(windowId = null) }).canReorderWorkspaces())
        }
    }

    @Test fun submittedMoveWaitsForMacAcknowledgementAndRefresh() = runBlocking {
        withMoves { peer, coordinator, moves ->
            val gate = CountDownLatch(1); peer.gate = gate
            val submitted = async { moves.submit(moves.sources.value.getValue(peer.mac.origin),
                "c", NativeWorkspaceMove(null, "a")) { true } }
            awaitState { peer.moves.size == 1 }
            assertFalse(submitted.isCompleted)
            assertEquals(listOf("c", "a", "b", "d"), ids(moves.sources.value.getValue(peer.mac.origin)))
            assertEquals(listOf("a", "b", "c", "d"), ids(coordinator.sources.value.getValue(peer.mac.origin)))
            gate.countDown()
            submitted.await()
            assertEquals(listOf("c", "a", "b", "d"), ids(coordinator.sources.value.getValue(peer.mac.origin)))
            assertEquals(0, moves.status.value.getValue(peer.mac.origin).pending)
        }
    }

    @Test fun submittedMoveReportsItsOwnRejectionAndAllowsExplicitRetry() = runBlocking {
        withMoves { peer, _, moves ->
            peer.rejectNext = true
            fun source() = moves.sources.value.getValue(peer.mac.origin)
            val failure = runCatching { moves.submit(source(), "c", NativeWorkspaceMove(null, "a")) { true } }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("Move rejected by fixture"))
            assertEquals(listOf("a", "b", "c", "d"), ids(source()))
            assertEquals(1, peer.moves.size)
            moves.submit(source(), "c", NativeWorkspaceMove(null, "a")) { true }
            assertEquals(2, peer.moves.size)
            assertEquals(listOf("c", "a", "b", "d"), ids(source()))
        }
    }

    @Test fun submittedMoveRechecksPresentationAfterWaitingForEarlierMove() = runBlocking {
        withMoves { peer, _, moves ->
            val gate = CountDownLatch(1); peer.gate = gate
            fun source() = moves.sources.value.getValue(peer.mac.origin)
            assertTrue(moves.enqueue(source(), "c", NativeWorkspaceMove(null, "a")))
            awaitState { peer.moves.size == 1 }
            var visible = true
            val submitted = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { moves.submit(source(), "b", NativeWorkspaceMove(null, "c")) { visible } }
            }
            assertEquals(2, moves.status.value.getValue(peer.mac.origin).pending)
            visible = false
            gate.countDown()
            assertTrue(submitted.await().isFailure)
            awaitState { moves.status.value.getValue(peer.mac.origin).pending == 0 }
            assertEquals(1, peer.moves.size)
            assertEquals(listOf("c", "a", "b", "d"), ids(source()))
        }
    }

    @Test fun cancellingSubmittedMoveImmediatelyReleasesSlotAndPreventsQueuedWrite() = runBlocking {
        withMoves { peer, _, moves ->
            val gate = CountDownLatch(1); peer.gate = gate
            fun source() = moves.sources.value.getValue(peer.mac.origin)
            assertTrue(moves.enqueue(source(), "c", NativeWorkspaceMove(null, "a")))
            awaitState { peer.moves.size == 1 }
            val submitted = launch(start = CoroutineStart.UNDISPATCHED) {
                moves.submit(source(), "b", NativeWorkspaceMove(null, "c")) { true }
            }
            assertEquals(2, moves.status.value.getValue(peer.mac.origin).pending)
            // No yield between admission and cancellation: cleanup must not depend on dispatch.
            submitted.cancelAndJoin()
            awaitState { moves.status.value.getValue(peer.mac.origin).pending == 1 }
            gate.countDown()
            awaitState { moves.status.value.getValue(peer.mac.origin).pending == 0 }
            assertEquals(1, peer.moves.size)
            assertEquals(listOf("c", "a", "b", "d"), ids(source()))
            assertNull(moves.status.value.getValue(peer.mac.origin).error)
            moves.submit(source(), "d", NativeWorkspaceMove(null, "c")) { true }
            assertEquals(2, peer.moves.size)
        }
    }

    @Test fun submittedMovePropagatesPredecessorFailureWithoutWritingOrRetrying() = runBlocking {
        withMoves { peer, _, moves ->
            val gate = CountDownLatch(1); peer.gate = gate; peer.rejectNext = true
            fun source() = moves.sources.value.getValue(peer.mac.origin)
            assertTrue(moves.enqueue(source(), "c", NativeWorkspaceMove(null, "a")))
            awaitState { peer.moves.size == 1 }
            val submitted = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { moves.submit(source(), "b", NativeWorkspaceMove(null, "c")) { true } }
            }
            gate.countDown()
            assertTrue(submitted.await().exceptionOrNull()?.message.orEmpty().contains("Move rejected by fixture"))
            assertEquals(1, peer.moves.size)
            assertEquals(listOf("a", "b", "c", "d"), ids(source()))
            assertEquals(0, moves.status.value.getValue(peer.mac.origin).pending)
        }
    }

    @Test fun submittedMoveRejectsInvalidPresentationOrFullQueueBeforeAdmission() = runBlocking {
        withMoves { peer, _, moves ->
            fun source() = moves.sources.value.getValue(peer.mac.origin)
            assertTrue(runCatching { moves.submit(source(), "c", NativeWorkspaceMove(null, "a")) { false } }.isFailure)
            assertTrue(peer.moves.isEmpty())
            assertTrue(moves.status.value.isEmpty())
            val gate = CountDownLatch(1); peer.gate = gate
            assertTrue(moves.enqueue(source(), "c", NativeWorkspaceMove(null, "a")))
            awaitState { peer.moves.size == 1 }
            assertTrue(moves.enqueue(source(), "b", NativeWorkspaceMove(null, "c")))
            assertTrue(moves.enqueue(source(), "d", NativeWorkspaceMove(null, "a")))
            val failure = runCatching { moves.submit(source(), "a", NativeWorkspaceMove(null, "b")) { true } }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("queue is full"))
            assertEquals(3, moves.status.value.getValue(peer.mac.origin).pending)
            gate.countDown()
            awaitState { moves.status.value.getValue(peer.mac.origin).pending == 0 }
            assertEquals(3, peer.moves.size)
        }
    }

    @Test fun groupAnchorChangeWhileQueuedRejectsMoveEvenWhenMembershipAndOrderStillMatch() = runBlocking {
        withMoves { peer, coordinator, moves ->
            peer.grouped = true
            coordinator.refresh()
            fun source() = moves.sources.value.getValue(peer.mac.origin)
            awaitState { source().groups.singleOrNull()?.liveAnchorWorkspaceId == "a" }
            val gate = CountDownLatch(1); peer.gate = gate; peer.replaceAnchorOnNextMove = true
            assertTrue(moves.enqueue(source(), "d", NativeWorkspaceMove(null, "c")))
            awaitState { peer.moves.size == 1 }
            val submitted = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { moves.submit(source(), "c", NativeWorkspaceMove(null, "d")) { true } }
            }
            gate.countDown()
            assertTrue(submitted.await().exceptionOrNull()?.message.orEmpty().contains("group anchors changed"))
            assertEquals(1, peer.moves.size)
            assertEquals(listOf("a", "b", "d", "c"), ids(source()))
            assertEquals("b", source().groups.single().liveAnchorWorkspaceId)
            assertEquals(0, moves.status.value.getValue(peer.mac.origin).pending)
        }
    }

    @Test fun predictionPreservesLiveContentAndReconcilesCreatedDeletedRowsAndIntermediateBases() {
        fun rows(vararg ids: String) = parseWorkspaces(JSONObject().put("workspaces", JSONArray(ids.map { JSONObject().put("id", it).put("title", it) })))
        val original = rows("a", "b", "c")
        val intermediate = rows("c", "a", "b")
        val predicted = rows("b", "c", "a")
        val source = NativeFeedSource(NativeCredentialStore.PairedMac("test", "test", "test"), workspaces = original)
        val optimism = NativeWorkspaceOptimism(NativeWorkspaceOrder(predicted, emptyList()),
            listOf(NativeWorkspaceOrder(original, emptyList()), NativeWorkspaceOrder(intermediate, emptyList())))
        val updated = rows("c", "new", "a", "b").map { it.copy(title = "Live ${it.id}", hasUnread = true) }
        val reconciled = optimism.reconcile(source.copy(workspaces = updated))
        assertEquals(1, reconciled.bases.size)
        val live = reconciled.order!!.materialize(updated)
        assertEquals(listOf("b", "c", "new", "a"), live.map { it.id })
        assertTrue(live.all { it.hasUnread && it.title == "Live ${it.id}" })
        assertNull(reconciled.reconcile(source.copy(workspaces = live)).order)
        assertEquals(listOf("c", "a"), NativeWorkspaceOrder(predicted, emptyList()).materialize(rows("a", "c")).map { it.id })
        assertNull(optimism.reconcile(source.copy(workspaces = rows("b", "a", "c"))).order)
        val group = NativeGroup("group", "Group", false, false, "a")
        val order = NativeWorkspaceOrder(original, listOf(group))
        assertFalse(order.matches(original, listOf(group.copy(isPinned = true))))
    }
}

/** Independent flat-list host: deliberately does not use Android move policy to produce expected state. */
private class MovePeer : AutoCloseable {
    val mac = NativeCredentialStore.PairedMac("fixture-route", "fixture-device", "Fixture Mac")
    private val server = ServerSocket(0)
    private val sockets = CopyOnWriteArrayList<Socket>()
    val moves = CopyOnWriteArrayList<JSONObject>()
    @Volatile var order = listOf("a", "b", "c", "d")
    @Volatile var gate: CountDownLatch? = null
    @Volatile var rejectNext = false
    @Volatile var pinOnNextMove = false
    @Volatile var mixedWindows = false
    @Volatile var grouped = false
    @Volatile var replaceAnchorOnNextMove = false
    @Volatile private var anchor = "a"
    @Volatile private var pinned = false
    @Volatile private var closed = false
    init { Thread {
        while (!closed) try {
            val socket = server.accept(); sockets += socket
            Thread { serve(socket) }.apply { isDaemon = true; start() }
        } catch (_: Exception) { break }
    }.apply { isDaemon = true; start() } }
    suspend fun connect() = MobileRpcClient(PairingCode.Route("127.0.0.1", server.localPort), { "fixture-token" }).also { it.connect() }
    private fun serve(socket: Socket) {
        try { socket.use {
            val input = socket.getInputStream()
            while (!closed) {
                val header = input.readNBytes(4)
                if (header.size != 4) break
                val count = header.fold(0) { n, b -> (n shl 8) or (b.toInt() and 255) }
                val request = JSONObject(String(input.readNBytes(count), Charsets.UTF_8))
                val method = request.getString("method")
                var reject = false
                val result = when (method) {
                    "mobile.host.status" -> JSONObject().put("mac_device_id", mac.deviceId).put("capabilities", JSONArray().put("workspace.move.v1").put(WORKSPACE_ACCOUNT_MUTATIONS_CAPABILITY))
                    "mobile.workspace.list" -> JSONObject().put("groups", JSONArray().also {
                        if (grouped) it.put(JSONObject().put("id", "g").put("name", "Group").put("anchor_workspace_id", anchor))
                    }).put("workspaces", JSONArray(order.map { id ->
                        JSONObject().put("id", id).put("title", id).put("is_pinned", pinned && id == "a")
                            .put("group_id", "g".takeIf { grouped && id in listOf("a", "b") })
                            .put("window_id", if (mixedWindows && id == order.last()) "another-window" else "window")
                    }))
                    "notification.feed.list" -> JSONObject().put("revision", 1).put("notifications", JSONArray())
                    "workspace.move" -> {
                        val params = request.getJSONObject("params"); moves += params
                        gate?.await(10, TimeUnit.SECONDS)
                        if (replaceAnchorOnNextMove) { anchor = "b"; replaceAnchorOnNextMove = false }
                        reject = rejectNext; rejectNext = false
                        if (pinOnNextMove) { pinned = true; pinOnNextMove = false }
                        else if (!reject) {
                            val id = params.getString("workspace_id")
                            val rows = order.filter { it != id }.toMutableList()
                            val before = params.optString("before_workspace_id")
                            rows.add(rows.indexOf(before).let { if (it < 0) rows.size else it }, id)
                            order = rows
                        }
                        JSONObject()
                    }
                    else -> JSONObject()
                }
                val response = JSONObject().put("id", request.getString("id")).put("ok", !reject)
                if (reject) response.put("error", JSONObject().put("code", "rejected").put("message", "Move rejected by fixture"))
                else response.put("result", result)
                socket.getOutputStream().write(MobileFrameCodec.encode(response.toString().toByteArray()))
                socket.getOutputStream().flush()
            }
        } } catch (_: Exception) { }
    }
    override fun close() { closed = true; gate?.countDown(); sockets.forEach { runCatching { it.close() } }; server.close() }
}
