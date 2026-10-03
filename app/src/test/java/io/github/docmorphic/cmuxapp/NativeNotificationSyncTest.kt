package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeNotificationSyncTest {
    @Test fun wireIdsAreStrictBoundedTrimmedAndDeduplicated() {
        assertEquals(listOf("one", "two"), notificationSyncIDs(JSONObject()
            .put("ids", JSONArray(listOf(" one ", "", "one", "two"))), "ids"))
        for (array in listOf(JSONArray().put(1), JSONArray().put(JSONObject()), JSONArray().put(JSONObject.NULL),
            JSONArray().put("x".repeat(1025)))) {
            assertNull(notificationSyncIDs(JSONObject().put("ids", array), "ids"))
        }
        assertNull(notificationSyncIDs(JSONObject(), "ids"))
        assertNull(notificationSyncIDs(JSONObject().put("ids", JSONArray(listOf("a", "b"))), "ids", 1))
    }

    @Test fun reconciliationBatchesHostLimitAndClearsOnlyRequestedIds() = runBlocking {
        val ids = List(512) { UUID.randomUUID().toString().uppercase() }
        val batches = mutableListOf<List<String>>(); val cleared = mutableListOf<String>()
        reconcileNativeNotifications(NativeNotificationSync({ ids + ids.first() }, { cleared += it })) { batch ->
            batches += batch
            JSONObject().put("handled_ids", JSONArray(listOf(batch.first(), "foreign-id")))
        }
        assertEquals(listOf(256, 256), batches.map { it.size })
        assertEquals(listOf(ids[0], ids[256]), cleared)
        reconcileNativeNotifications(NativeNotificationSync({ emptyList() }, { error("nothing to clear") })) {
            error("empty actual banners must not send RPC")
        }
    }

    @Test fun malformedUnsupportedAndCancelledReconciliationNeverInventAcknowledgement() = runBlocking {
        val sync = NativeNotificationSync({ listOf("id") }, { error("must not clear") })
        reconcileNativeNotifications(sync) { JSONObject().put("handled_ids", JSONArray().put(7)) }
        reconcileNativeNotifications(sync) { throw java.io.IOException("older host") }
        val started = CompletableDeferred<Unit>()
        val task = launch { reconcileNativeNotifications(sync) { started.complete(Unit); awaitCancellation() } }
        started.await(); task.cancelAndJoin(); assertTrue(task.isCancelled)
    }

    @Test fun subscribedLiveEventAndReconnectRpcReachCallbacksWithoutLosingFeed() = runBlocking {
        // Repeat with a new transport/monitor: catch-up must run on every connection.
        repeat(2) { connection ->
            val responses = Channel<ByteArray>(Channel.UNLIMITED)
            val requests = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
            val handled = Channel<List<String>>(Channel.UNLIMITED)
            val feed = CompletableDeferred<Unit>()
            val id = "01234567-89AB-4CDE-8FAB-012345678901"
            suspend fun send(value: JSONObject) = responses.send(MobileFrameCodec.encode(value.toString().toByteArray()))
            val transport = object : MobileRpcTransport {
                override suspend fun connect() {}
                override suspend fun read() = responses.receiveCatching().getOrNull()
                override fun close() { responses.close() }
                override suspend fun write(bytes: ByteArray) {
                    val request = JSONObject(bytes.copyOfRange(4, bytes.size).toString(Charsets.UTF_8)); requests += request
                    val method = request.getString("method")
                    val result = when (method) {
                        "mobile.events.subscribe" -> {
                            val topics = request.getJSONObject("params").getJSONArray("topics")
                            assertEquals(listOf("notification.feed.changed", "notification.dismissed"),
                                (0 until topics.length()).map(topics::getString))
                            send(JSONObject().put("kind", "event").put("topic", "notification.dismissed")
                                .put("payload", JSONObject().put("ids", JSONArray().put(id))))
                            JSONObject()
                        }
                        "notification.reconcile" -> {
                            val params = request.getJSONObject("params")
                            assertEquals(id, params.getJSONArray("delivered_ids").getString(0))
                            assertTrue(params.getString("client_id").isNotBlank())
                            JSONObject().put("handled_ids", JSONArray().put(id))
                        }
                        "notification.feed.list" -> JSONObject().put("revision", connection).put("notifications", JSONArray())
                        else -> error("Unexpected method $method")
                    }
                    send(JSONObject().put("id", request.getString("id")).put("ok", true).put("result", result))
                }
            }
            val client = MobileRpcClient(transport, { "fixture" })
            try {
                client.connect()
                val monitor = launch { monitorNativeNotificationFeed(client,
                    NativeNotificationSync({ listOf(id) }, { handled.trySend(it) })) { feed.complete(Unit) } }
                withTimeout(5000) {
                    assertEquals(listOf(id), handled.receive())
                    assertEquals(listOf(id), handled.receive())
                    feed.await()
                }
                monitor.cancelAndJoin()
                assertEquals(setOf("mobile.events.subscribe", "notification.feed.list", "notification.reconcile"),
                    requests.map { it.getString("method") }.toSet())
            } finally { client.close() }
        }
    }
}
