package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class NativeNotificationDismissTest {
    private fun mac(name: String) = NativeCredentialStore.PairedMac("code-$name", "device-$name", name, "stable",
        "fixture-user", "fixture-team", pairingOrigin("code-$name", "device-$name", "stable"))
    private fun state(vararg macs: NativeCredentialStore.PairedMac) = JSONObject().put("task_session", "login")
        .put("refresh_token", "test-refresh").put("pairings", JSONArray(macs.map(NativePairingRecords::encode)))
    private fun route(mac: NativeCredentialStore.PairedMac, id: String = "same-id", login: String? = "login") =
        NotificationDestination("route-$id", mac.origin, id, "w", "s", false, login)

    @Test fun durableQueueIsBoundedDeduplicatedAndKeepsMacIdentitiesSeparate() {
        val a = mac("a"); val b = mac("b"); val state = state(a, b)
        val queue = NativeNotificationDismissOutbox(state)
        queue.enqueue(route(a)); queue.enqueue(route(a)); queue.enqueue(route(b))
        assertEquals(2, queue.pending().size)
        val restored = NativeNotificationDismissOutbox(JSONObject(state.toString()))
        restored.acknowledge(listOf(queue.pending().first()))
        assertEquals(listOf(b.origin), restored.pending().map { it.origin })
        repeat(140) { queue.enqueue(route(a, "n$it")) }
        assertEquals(128, queue.pending().size); assertEquals("n12", queue.pending().first().id)
        assertFalse(state.getJSONArray(NativeNotificationDismissOutbox.KEY).toString().contains("test-refresh"))
    }

    @Test fun newLoginNeverReusesAnOlderNotificationsPendingIntentRoute() {
        val a = mac("a"); val ledger = NativeNotificationLedger(JSONObject())
        val item = NativeNotification("id", "w", "s", "Title", "Body", false)
        val original = ledger.stage(a.origin, item, "first-login")
        assertEquals(original.routeId, ledger.stage(a.origin, item, "first-login").routeId)
        val replacement = ledger.stage(a.origin, item, "next-login")
        assertNotEquals(original.routeId, replacement.routeId)
        assertEquals("first-login", ledger.destination(original.routeId)!!.login)
        assertEquals("next-login", ledger.destination(replacement.routeId)!!.login)
    }

    @Test fun oldLoginForgottenMacAndAmbiguousAliasCannotBeReauthorized() {
        val a = mac("a"); val s = state(a); val q = NativeNotificationDismissOutbox(s)
        q.enqueue(route(a, login = null)); q.enqueue(route(a, login = "old")); assertTrue(q.pending().isEmpty())
        q.enqueue(route(a)); s.put("task_session", "next"); q.prune(); assertTrue(q.pending().isEmpty())
        s.put("task_session", "login"); q.enqueue(route(a)); s.put("pairings", JSONArray()); q.prune()
        s.put("pairings", JSONArray().put(NativePairingRecords.encode(a))); assertTrue(q.pending().isEmpty())
        q.enqueue(route(a))
        val replacement = mac("b").copy(previousOrigins = setOf(a.origin))
        s.put("pairings", JSONArray().put(NativePairingRecords.encode(replacement))); q.prune()
        assertEquals(replacement.origin, q.pending().single().origin)
        s.put("pairings", JSONArray(listOf(replacement, mac("c").copy(previousOrigins = setOf(replacement.origin))).map(NativePairingRecords::encode)))
        q.prune(); assertTrue(q.pending().isEmpty())
    }

    @Test fun confirmedRpcRemovesOnlyItsRowsAndFailedOwnerDoesNotBlockAnother() = runBlocking {
        val a = mac("a"); val b = mac("b"); val s = state(a, b); val queue = NativeNotificationDismissOutbox(s)
        queue.enqueue(route(a)); queue.enqueue(route(b))
        val requests = CopyOnWriteArrayList<JSONObject>(); val failure = AtomicReference<Throwable?>()
        ServerSocket(0).use { server ->
            val peer = Thread {
                try { server.accept().use { socket ->
                    socket.soTimeout = 5000
                    repeat(2) { index ->
                        val input = socket.getInputStream(); val header = input.readNBytes(4)
                        val size = header.fold(0) { n, byte -> (n shl 8) or (byte.toInt() and 255) }
                        val request = JSONObject(input.readNBytes(size).toString(Charsets.UTF_8)); requests += request
                        val result = if (index == 0) JSONObject().put("mac_device_id", b.deviceId).put("mac_instance_tag", b.instanceTag) else JSONObject()
                        val reply = JSONObject().put("id", request.getString("id")).put("ok", true).put("result", result)
                        socket.getOutputStream().write(MobileFrameCodec.encode(reply.toString().toByteArray())); socket.getOutputStream().flush()
                    }
                    socket.getInputStream().read() // Client closes the lease after acknowledgement.
                } } catch (problem: Throwable) { failure.set(problem) }
            }.apply { isDaemon = true; start() }
            flushNativeNotificationDismissals(queue.pending(), listOf(a,b), { _, _ -> true }, { owner ->
                if (owner == a) error("offline")
                MobileRpcClient(PairingCode.Route("127.0.0.1", server.localPort), { "fixture" }).also { it.connect() }
            }, queue::acknowledge)
            peer.join(5000); assertFalse(peer.isAlive); failure.get()?.let { throw it }
        }
        assertEquals(listOf("mobile.host.status", "notification.dismiss"), requests.map { it.getString("method") })
        assertEquals("same-id", requests.last().getJSONObject("params").getJSONArray("notification_ids").getString(0))
        assertTrue(requests.last().getJSONObject("params").getString("client_id").isNotBlank())
        assertEquals(listOf(a.origin), queue.pending().map { it.origin })
    }

    @Test fun wrongHostLostAdmissionAndUnknownOutcomeKeepPendingRowsAndCloseLeases() = runBlocking {
        val a = mac("a")
        for (scenario in listOf("wrong-host", "revoked", "unknown", "revoked-after-send")) {
            val q = NativeNotificationDismissOutbox(state(a)); q.enqueue(route(a))
            var permitted = true; var closed = false
            val requests = mutableListOf<String>()
            val responses = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val transport = object : MobileRpcTransport {
                override suspend fun connect() {}
                override suspend fun read() = responses.receiveCatching().getOrNull()
                override suspend fun write(bytes: ByteArray) {
                    val request = JSONObject(bytes.copyOfRange(4, bytes.size).toString(Charsets.UTF_8))
                    val method = request.getString("method"); requests += method
                    if (method == "notification.dismiss" && scenario == "unknown") throw java.io.IOException("fixture lost reply")
                    val result = if (method == "mobile.host.status") JSONObject()
                        .put("mac_device_id", if (scenario == "wrong-host") "other-device" else a.deviceId)
                        .put("mac_instance_tag", a.instanceTag) else JSONObject()
                    if (scenario == "revoked" || (scenario == "revoked-after-send" && method == "notification.dismiss")) permitted = false
                    responses.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", true)
                        .put("result", result).toString().toByteArray()))
                }
                override fun close() { closed = true; responses.close() }
            }
            flushNativeNotificationDismissals(q.pending(), listOf(a), { _, _ -> permitted }, {
                MobileRpcClient(transport, { "fixture" }).also { it.connect() }
            }, q::acknowledge)
            assertTrue(closed); assertEquals(1, q.pending().size)
            assertEquals(if (scenario in listOf("wrong-host", "revoked")) listOf("mobile.host.status")
                else listOf("mobile.host.status", "notification.dismiss"), requests)
        }
    }

    @Test fun unadmittedAccountDoesNotConnectOrAcknowledge() = runBlocking {
        val a = mac("a"); val q = NativeNotificationDismissOutbox(state(a)); q.enqueue(route(a))
        flushNativeNotificationDismissals(q.pending(), listOf(a), { _, _ -> false }, { error("must not connect") }, { error("must not acknowledge") })
        assertEquals(1, q.pending().size)
    }
}
