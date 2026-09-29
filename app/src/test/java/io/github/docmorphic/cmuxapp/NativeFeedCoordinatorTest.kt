package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class NativeFeedCoordinatorTest {
    private suspend fun awaitState(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }
    private fun mac(id: String) = NativeCredentialStore.PairedMac(id, id, "Mac $id")

    @Test fun keepAwakeIsSeededPerMacAndEventsNeverMutatePowerOrAnotherComputer() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            a.powerSupported = true; a.powerValue = true
            b.powerSupported = true; b.powerValue = false
            val first = mac("a").copy(instanceTag = "default")
            val second = mac("b").copy(instanceTag = "default")
            val coordinator = NativeFeedCoordinator(this, { if (it == first) a.connect() else b.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(first, second))
                awaitState { coordinator.sources.value[first.origin]?.keepAwake == true &&
                    coordinator.sources.value[second.origin]?.keepAwake == false }
                a.powerEvent(false, "unowned-stream"); a.powerEvent("false")
                delay(40)
                assertEquals(true, coordinator.sources.value[first.origin]?.keepAwake)
                a.powerEvent(false)
                awaitState { coordinator.sources.value[first.origin]?.keepAwake == false }
                assertEquals(false, coordinator.sources.value[second.origin]?.keepAwake)
                b.powerEvent(true)
                awaitState { coordinator.sources.value[second.origin]?.keepAwake == true }
                assertEquals(false, coordinator.sources.value[first.origin]?.keepAwake)
                assertTrue((a.requests + b.requests).none { it.optString("method") == "caffeine.set" })
                coordinator.workspaceAction(first, "w", "rename", "Still connected")
                assertEquals("Still connected", coordinator.sources.value[first.origin]?.workspaces?.single()?.title)
                b.disconnect()
                awaitState { coordinator.sources.value[second.origin]?.availability == NativeFeedAvailability.OFFLINE }
                assertNull(coordinator.sources.value[second.origin]?.keepAwake)
                coordinator.pause()
                assertTrue(coordinator.sources.value.values.all { it.keepAwake == null })
            } finally { coordinator.close() }
        } }
    }

    @Test fun failedPowerReadDoesNotBreakFeedAndPausedSnapshotNeverKeepsCup() = runBlocking {
        FeedPeer("a").use { peer ->
            peer.powerSupported = true; peer.powerValue = "true"
            val paired = mac("a").copy(instanceTag = "default")
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(paired))
                awaitState { coordinator.sources.value[paired.origin]?.availability == NativeFeedAvailability.CONNECTED &&
                    peer.requests.any { it.optString("method") == "caffeine.status" } }
                assertNull(coordinator.sources.value[paired.origin]?.keepAwake)
                coordinator.workspaceAction(paired, "w", "rename", "Power is optional")
                peer.powerEvent(true)
                awaitState { coordinator.sources.value[paired.origin]?.keepAwake == true }
                coordinator.pause()
                assertNull(coordinator.sources.value[paired.origin]?.keepAwake)
                val gate = java.util.concurrent.CountDownLatch(1)
                peer.hostStatusGate = gate
                try {
                    coordinator.updateMacs(listOf(paired))
                    assertNull(coordinator.sources.value[paired.origin]?.keepAwake)
                    assertEquals(NativeFeedAvailability.CONNECTING, coordinator.sources.value[paired.origin]?.availability)
                } finally { gate.countDown() }
                coordinator.updateMacs(emptyList())
                assertTrue(coordinator.sources.value.isEmpty())
            } finally { coordinator.close() }
        }
    }

    @Test fun repeatedPauseRemovesBothOwnedStreamsAndPreservesAnotherConsumersWire() = runBlocking {
        FeedPeer("a").use { peer ->
            peer.powerSupported = true; peer.powerValue = true
            val paired = mac("a").copy(instanceTag = "default")
            peer.connect().use { retainedClient ->
                val coordinator = NativeFeedCoordinator(this, { retainedClient.lease {} }, { true })
                try {
                    repeat(3) { cycle ->
                        coordinator.updateMacs(listOf(paired))
                        awaitState { coordinator.sources.value[paired.origin]?.let {
                            it.keepAwake == true && it.availability == NativeFeedAvailability.CONNECTED
                        } == true }
                        val subscribed = peer.requests.filter { it.optString("method") == "mobile.events.subscribe" }
                            .map { it.getJSONObject("params").getString("stream_id") }.toSet()
                        assertEquals((cycle + 1) * 2, subscribed.size)
                        coordinator.pause()
                        assertNull(coordinator.sources.value[paired.origin]?.keepAwake)
                        awaitState {
                            peer.requests.filter { it.optString("method") == "mobile.events.unsubscribe" }
                                .map { it.getJSONObject("params").getString("stream_id") }.toSet().containsAll(subscribed)
                        }
                        assertFalse(retainedClient.isClosed)
                        assertEquals("a", retainedClient.hostStatus().getString("mac_device_id"))
                    }
                } finally { coordinator.close() }
            }
        }
    }

    @Test fun legacyPairingNeedsARealHostBuildBeforeObservingPower() = runBlocking {
        for (build in listOf(JSONObject.NULL, 12, "", "default")) FeedPeer("a").use { peer ->
            peer.powerSupported = true; peer.powerValue = true; peer.hostBuild = build
            val paired = mac("a")
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(paired))
                awaitState { coordinator.sources.value[paired.origin]?.availability == NativeFeedAvailability.CONNECTED }
                if (build == "default") awaitState { coordinator.sources.value[paired.origin]?.keepAwake == true }
                else {
                    assertNull(coordinator.sources.value[paired.origin]?.keepAwake)
                    assertTrue(peer.requests.none { it.optString("method").startsWith("caffeine.") })
                }
            } finally { coordinator.close() }
        }
    }

    @Test fun unsupportedMacDoesNotReceivePowerSubscriptionOrRead() = runBlocking {
        FeedPeer("a").use { peer ->
            val paired = mac("a").copy(instanceTag = "default")
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(paired))
                awaitState { coordinator.sources.value[paired.origin]?.availability == NativeFeedAvailability.CONNECTED }
                assertNull(coordinator.sources.value[paired.origin]?.keepAwake)
                assertTrue(peer.requests.none { it.optString("method").startsWith("caffeine.") })
                assertTrue(peer.requests.filter { it.optString("method") == "mobile.events.subscribe" }
                    .none { it.getJSONObject("params").getJSONArray("topics").toString().contains("caffeine") })
            } finally { coordinator.close() }
        }
    }

    @Test fun workspaceActionsRefreshOnlyTheirOwnerEvenWithCollidingIdsAndRejectedWrites() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            val coordinator = NativeFeedCoordinator(this, { m -> (if (m.deviceId == "a") a else b).connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.hasWorkspaceSnapshot } == 2 }
                coordinator.workspaceAction(mac("b"), "w", "rename", "Renamed")
                assertEquals("Renamed", coordinator.sources.value[mac("b").origin]!!.workspaces.single().title)
                assertEquals("Original", coordinator.sources.value[mac("a").origin]!!.workspaces.single().title)
                assertTrue(a.requests.none { it.optString("method") == "workspace.action" })
                assertEquals("window-b", b.requests.single { it.optString("method") == "workspace.action" }
                    .getJSONObject("params").getString("window_id"))
                b.rejectWorkspaceAction = true
                b.workspaceTitle = "Changed elsewhere"
                val failure = runCatching { coordinator.workspaceAction(mac("b"), "w", "pin") }.exceptionOrNull()
                assertTrue(failure?.message?.contains("Rejected by fixture") == true)
                assertEquals("Changed elsewhere", coordinator.sources.value[mac("b").origin]!!.workspaces.single().title)
                coordinator.updateMacs(listOf(mac("a")))
                assertTrue(runCatching { coordinator.workspaceAction(mac("b"), "w", "close") }.isFailure)
                assertTrue(a.requests.none { it.optString("method") == "workspace.close" })
            } finally { coordinator.close() }
        } }
    }

    @Test fun retainedRowsCannotMutateBeforeReconnectedHostIdentityIsVerified() = runBlocking {
        FeedPeer("a").use { peer ->
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            val gate = java.util.concurrent.CountDownLatch(1)
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.items?.isNotEmpty() == true }
                coordinator.pause()
                peer.hostStatusGate = gate
                val statuses = peer.requests.count { it.optString("method") == "mobile.host.status" }
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { peer.requests.count { it.optString("method") == "mobile.host.status" } > statuses }
                assertTrue(runCatching { coordinator.workspaceAction(mac("a"), "w", "pin") }.isFailure)
                assertTrue(peer.requests.none { it.optString("method") == "workspace.action" })
                gate.countDown()
                awaitState { coordinator.sources.value.values.single().availability == NativeFeedAvailability.CONNECTED }
                coordinator.workspaceAction(mac("a"), "w", "pin")
                assertEquals(1, peer.requests.count { it.optString("method") == "workspace.action" })
            } finally { gate.countDown(); coordinator.close() }
        }
    }

    @Test fun workspaceSnapshotsAdvanceWhileNotificationRevisionIsStale() = runBlocking {
        FeedPeer("a").use { peer ->
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.items?.isNotEmpty() == true }
                peer.mutationRevision.set(5); peer.overrideFeedRevision = 1
                coordinator.setRead(aggregateNativeFeed(coordinator.sources.value.values).single(), true)
                peer.workspaceTitle = "New workspace state"
                coordinator.refresh()
                val source = coordinator.sources.value.values.single()
                assertEquals("New workspace state", source.workspaces.single().title)
                assertEquals("Group a", source.groups.single().name)
                assertTrue("workspace.group_actions.v1" in source.capabilities)
                assertTrue(source.items.single().isRead)
                assertEquals(1L, source.revision)
            } finally { coordinator.close() }
        }
    }

    @Test fun independentMacReadsOfflineRetentionAndForgetting() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            val allowed = mutableSetOf("a", "b")
            val coordinator = NativeFeedCoordinator(this, { m -> (if (m.deviceId == "a") a else b).connect() }, { it.deviceId in allowed })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.availability == NativeFeedAvailability.CONNECTED } == 2 }
                val entries = aggregateNativeFeed(coordinator.sources.value.values)
                assertEquals(2, entries.size)
                coordinator.setRead(entries.single { it.source.mac.deviceId == "b" }, true)
                assertFalse(coordinator.sources.value[mac("a").origin]!!.items.single().isRead)
                assertTrue(coordinator.sources.value[mac("b").origin]!!.items.single().isRead)
                assertTrue(a.requests.none { it.optString("method") == "notification.feed.mark_read" })
                assertEquals("shared", b.requests.single { it.optString("method") == "notification.feed.mark_read" }
                    .getJSONObject("params").getJSONArray("notification_ids").getString(0))
                b.disconnect()
                awaitState { coordinator.sources.value[mac("b").origin]?.availability == NativeFeedAvailability.OFFLINE }
                assertEquals(1, coordinator.sources.value[mac("b").origin]!!.items.size)
                allowed.remove("b")
                coordinator.updateMacs(listOf(mac("a")))
                assertNull(coordinator.sources.value[mac("b").origin])
            } finally { coordinator.close() }
        } }
    }

    @Test fun bulkReadStaysWithinCapturedComputerScopeIncludingHiddenRetainedRows() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            val coordinator = NativeFeedCoordinator(this, { m -> (if (m.deviceId == "a") a else b).connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.availability == NativeFeedAvailability.CONNECTED } == 2 }
                coordinator.markAllRead(mac("b").origin)
                assertTrue(a.requests.none { it.optString("method") == "notification.feed.mark_all_read" })
                assertEquals(1, b.requests.count { it.optString("method") == "notification.feed.mark_all_read" })
                coordinator.markAllRead("forgotten-origin")
                assertTrue(a.requests.none { it.optString("method") == "notification.feed.mark_all_read" })
                assertFalse(coordinator.sources.value[mac("a").origin]!!.items.single().isRead)
            } finally { coordinator.close() }
        } }
    }

    @Test fun replacementComputerAtSameRouteCannotAcceptOldRowActions() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            val first = mac("a").copy(code = "same-route")
            val replacement = mac("b").copy(code = "same-route")
            val coordinator = NativeFeedCoordinator(this, { m -> (if (m.deviceId == "a") a else b).connect() }, { true })
            try {
                coordinator.updateMacs(listOf(first))
                awaitState { coordinator.sources.value[first.origin]?.items?.isNotEmpty() == true }
                val old = aggregateNativeFeed(coordinator.sources.value.values).single()
                coordinator.updateMacs(listOf(replacement))
                assertNull(coordinator.sources.value[first.origin])
                awaitState { coordinator.sources.value[replacement.origin]?.items?.isNotEmpty() == true }
                assertTrue(runCatching { coordinator.setRead(old, true) }.isFailure)
                assertTrue(b.requests.none { it.optString("method") == "notification.feed.mark_read" })
                assertFalse(coordinator.sources.value[replacement.origin]!!.items.single().isRead)
            } finally { coordinator.close() }
        } }
    }

    @Test fun staleFeedCannotUndoAcknowledgedReadOrUnread() = runBlocking {
        FeedPeer("a").use { peer ->
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.items?.isNotEmpty() == true }
                peer.mutationRevision.set(3)
                peer.overrideFeedRevision = 2
                peer.forceUnread = true
                val entry = aggregateNativeFeed(coordinator.sources.value.values).single()
                coordinator.setRead(entry, true)
                coordinator.refresh()
                assertEquals(1, coordinator.sources.value.values.single().revision)
                assertTrue(coordinator.sources.value.values.single().items.single().isRead)
                peer.mutationRevision.set(4)
                coordinator.setRead(entry, false)
                coordinator.refresh()
                assertEquals(1, coordinator.sources.value.values.single().revision)
                assertFalse(coordinator.sources.value.values.single().items.single().isRead)
                assertEquals(1, peer.requests.count { it.optString("method") == "notification.feed.mark_unread" })
            } finally { coordinator.close() }
        }
    }

    @Test fun pauseRetainsSnapshotAndMutationFloorUntilFreshReconnection() = runBlocking {
        FeedPeer("a").use { peer ->
            val coordinator = NativeFeedCoordinator(this, { peer.connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { coordinator.sources.value.values.singleOrNull()?.items?.isNotEmpty() == true }
                peer.mutationRevision.set(5); peer.overrideFeedRevision = 1; peer.forceUnread = true
                coordinator.setRead(aggregateNativeFeed(coordinator.sources.value.values).single(), true)
                coordinator.pause()
                assertTrue(coordinator.sources.value.values.single().items.single().isRead)
                assertEquals(NativeFeedAvailability.OFFLINE, coordinator.sources.value.values.single().availability)
                val previousLists = peer.requests.count { it.optString("method") == "notification.feed.list" }
                coordinator.updateMacs(listOf(mac("a")))
                awaitState { peer.requests.count { it.optString("method") == "notification.feed.list" } > previousLists }
                coordinator.refresh()
                assertTrue(coordinator.sources.value.values.single().items.single().isRead)
                peer.overrideFeedRevision = 6
                coordinator.refresh()
                awaitState { coordinator.sources.value.values.single().revision == 6L }
                assertFalse(coordinator.sources.value.values.single().items.single().isRead)
                coordinator.close()
                assertTrue(coordinator.sources.value.isEmpty())
            } finally { coordinator.close() }
        }
    }

    @Test fun bulkReadReportsOfflineMacWithoutLosingSuccessfulMacUpdate() = runBlocking {
        FeedPeer("a").use { a -> FeedPeer("b").use { b ->
            val coordinator = NativeFeedCoordinator(this, { m -> (if (m.deviceId == "a") a else b).connect() }, { true })
            try {
                coordinator.updateMacs(listOf(mac("a"), mac("b")))
                awaitState { coordinator.sources.value.values.count { it.items.isNotEmpty() } == 2 }
                b.disconnect()
                awaitState { coordinator.sources.value[mac("b").origin]?.availability == NativeFeedAvailability.OFFLINE }
                val result = runCatching { coordinator.markAllRead() }
                assertTrue(result.exceptionOrNull()?.message?.contains("Mac b") == true)
                assertTrue(coordinator.sources.value[mac("a").origin]!!.items.single().isRead)
                assertFalse(coordinator.sources.value[mac("b").origin]!!.items.single().isRead)
                assertEquals(1, a.requests.count { it.optString("method") == "notification.feed.mark_all_read" })
                assertEquals(0, b.requests.count { it.optString("method") == "notification.feed.mark_all_read" })
            } finally { coordinator.close() }
        } }
    }
}

private class FeedPeer(private val id: String) : AutoCloseable {
    private val server = ServerSocket(0)
    private val sockets = CopyOnWriteArrayList<Socket>()
    val requests = CopyOnWriteArrayList<JSONObject>()
    val mutationRevision = AtomicInteger(2)
    @Volatile var overrideFeedRevision: Int? = null
    @Volatile var forceUnread = false
    @Volatile var powerSupported = false
    @Volatile var hostBuild: Any = "default"
    @Volatile var powerValue: Any = false
    private val powerStreams = CopyOnWriteArrayList<Pair<Socket, String>>()
    @Volatile var hostStatusGate: java.util.concurrent.CountDownLatch? = null
    @Volatile var workspaceTitle = "Original"
    @Volatile var rejectWorkspaceAction = false
    @Volatile private var read = false
    @Volatile private var revision = 1
    @Volatile private var closed = false
    init {
        Thread {
            while (!closed) try {
                val socket = server.accept(); sockets += socket
                Thread { serve(socket) }.apply { isDaemon = true; start() }
            } catch (_: Exception) { break }
        }.apply { isDaemon = true; start() }
    }
    suspend fun connect() = MobileRpcClient(PairingCode.Route("127.0.0.1", server.localPort), { "fixture" }).also { it.connect() }
    private fun serve(socket: Socket) {
        try { socket.use {
            val input = socket.getInputStream()
            while (!closed) {
                val header = input.readNBytes(4)
                if (header.size != 4) break
                val size = header.fold(0) { n, b -> (n shl 8) or (b.toInt() and 255) }
                val request = JSONObject(String(input.readNBytes(size), Charsets.UTF_8)); requests += request
                if (request.getString("method") == "mobile.host.status") hostStatusGate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                val result = when (request.getString("method")) {
                    "mobile.host.status" -> JSONObject().put("mac_device_id", id).put("mac_instance_tag", hostBuild)
                        .put("capabilities", JSONArray().put("workspace.group_actions.v1").also {
                            if (powerSupported) it.put("caffeine.control.v1")
                        })
                    "caffeine.status" -> JSONObject().put("enabled", powerValue)
                    "mobile.events.subscribe" -> JSONObject().also {
                        val params = request.getJSONObject("params")
                        if (params.getJSONArray("topics").toString().contains("caffeine.status.changed"))
                            powerStreams += socket to params.getString("stream_id")
                    }
                    "mobile.events.unsubscribe" -> JSONObject().also {
                        val stream = request.getJSONObject("params").getString("stream_id")
                        powerStreams.removeAll { it.first === socket && it.second == stream }
                    }
                    "mobile.workspace.list" -> JSONObject().put("workspaces", JSONArray().put(JSONObject().put("id", "w")
                        .put("window_id", "window-" + id).put("title", workspaceTitle)))
                        .put("groups", JSONArray().put(JSONObject().put("id", "g").put("name", "Group " + id)))
                    "workspace.action" -> JSONObject().also {
                        if (!rejectWorkspaceAction && request.getJSONObject("params").optString("action") == "rename")
                            workspaceTitle = request.getJSONObject("params").getString("title")
                    }
                    "notification.feed.list" -> JSONObject().put("revision", overrideFeedRevision ?: revision)
                        .put("notifications", JSONArray().put(JSONObject().put("id", "shared").put("workspace_id", "w")
                            .put("created_at", 1).put("is_read", if (forceUnread) false else read)))
                    "notification.feed.mark_read", "notification.feed.mark_unread", "notification.feed.mark_all_read" -> {
                        read = request.getString("method") != "notification.feed.mark_unread"
                        revision = mutationRevision.get()
                        JSONObject().put("revision", revision)
                    }
                    else -> JSONObject()
                }
                val rejected = request.getString("method") == "workspace.action" && rejectWorkspaceAction
                val response = JSONObject().put("id", request.getString("id")).put("ok", !rejected)
                if (rejected) response.put("error", JSONObject().put("code", "fixture_rejected").put("message", "Rejected by fixture"))
                else response.put("result", result)
                send(socket, response)
            }
        } } catch (_: Exception) { }
    }
    private fun send(socket: Socket, frame: JSONObject) = synchronized(socket) {
        socket.getOutputStream().write(MobileFrameCodec.encode(frame.toString().toByteArray()))
        socket.getOutputStream().flush()
    }
    fun powerEvent(value: Any, overrideStream: String? = null) {
        powerStreams.forEach { (socket, stream) -> if (!socket.isClosed) send(socket,
            JSONObject().put("kind", "event").put("stream_id", overrideStream ?: stream)
                .put("topic", "caffeine.status.changed").put("payload", JSONObject().put("enabled", value))) }
    }
    fun disconnect() { sockets.forEach { runCatching { it.close() } } }
    override fun close() { closed = true; disconnect(); server.close() }
}
