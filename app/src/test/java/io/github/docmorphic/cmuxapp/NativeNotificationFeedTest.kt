package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NativeNotificationFeedTest {
    @Test fun invalidationRefreshesWithoutPollDelayAndDisconnectEndsWorker() = runBlocking {
        ServerSocket(0).use { server ->
            val closePeer = CountDownLatch(1)
            val peerFailure = AtomicReference<Throwable?>()
            val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
            val peer = Thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        fun send(value: JSONObject) {
                            output.write(MobileFrameCodec.encode(value.toString().toByteArray())); output.flush()
                        }
                        repeat(3) { index ->
                            val length = input.readNBytes(4).fold(0) { n, b -> (n shl 8) or (b.toInt() and 0xff) }
                            val request = JSONObject(String(input.readNBytes(length), Charsets.UTF_8))
                            methods += request.getString("method")
                            if (index == 0) assertEquals("notification.feed.changed", request.getJSONObject("params").getJSONArray("topics").getString(0))
                            val result = if (index == 2) JSONObject().put("notifications", JSONArray().put(JSONObject()
                                .put("id", "new").put("workspace_id", "w").put("surface_id", "s").put("title", "Ready")))
                                else JSONObject().put("notifications", JSONArray())
                            send(JSONObject().put("id", request.getString("id")).put("ok", true).put("result", result))
                            if (index == 1) repeat(4) {
                                send(JSONObject().put("kind", "event").put("topic", "notification.feed.changed").put("payload", JSONObject()))
                            }
                        }
                        check(closePeer.await(5, TimeUnit.SECONDS))
                    }
                } catch (failure: Throwable) { peerFailure.set(failure) }
            }.apply { isDaemon = true; start() }
            val client = MobileRpcClient(PairingCode.Route("127.0.0.1", server.localPort), { "fixture" })
            try {
                client.connect()
                val received = CompletableDeferred<List<NativeNotification>>()
                val task = async {
                    runCatching { monitorNativeNotificationFeed(client) { if (it.isNotEmpty()) received.complete(it) } }
                }
                val feed = withTimeout(5_000) { received.await() }
                assertEquals("new", feed.single().id)
                closePeer.countDown()
                assertTrue(withTimeout(5_000) { task.await() }.isFailure)
                assertEquals(listOf("mobile.events.subscribe", "notification.feed.list", "notification.feed.list"), methods.toList())
                peer.join(2_000)
                assertNull(peerFailure.get())
            } finally { closePeer.countDown(); client.close(); peer.join(2_000) }
        }
    }
}
