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
                assertEquals(3, coordinator.sources.value.values.single().revision)
                assertTrue(coordinator.sources.value.values.single().items.single().isRead)
                peer.mutationRevision.set(4)
                coordinator.setRead(entry, false)
                coordinator.refresh()
                assertEquals(4, coordinator.sources.value.values.single().revision)
                assertFalse(coordinator.sources.value.values.single().items.single().isRead)
                assertEquals(1, peer.requests.count { it.optString("method") == "notification.feed.mark_unread" })
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
                val result = when (request.getString("method")) {
                    "mobile.host.status" -> JSONObject().put("mac_device_id", id)
                    "mobile.workspace.list" -> JSONObject().put("workspaces", JSONArray())
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
                val response = JSONObject().put("id", request.getString("id")).put("ok", true).put("result", result)
                socket.getOutputStream().write(MobileFrameCodec.encode(response.toString().toByteArray()))
                socket.getOutputStream().flush()
            }
        } } catch (_: Exception) { }
    }
    fun disconnect() { sockets.forEach { runCatching { it.close() } } }
    override fun close() { closed = true; disconnect(); server.close() }
}
