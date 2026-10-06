package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PhoneMacPushRpcTest {
    @Test fun authenticatedSettingsUseOfficialMethodsWithoutSelectionTicket() = runBlocking {
        ServerSocket(0).use { server ->
            val observed = mutableListOf<JSONObject>(); val failure = AtomicReference<Throwable>()
            val finish = CountDownLatch(1)
            val peer = Thread {
                try { server.accept().use { socket ->
                    socket.soTimeout = 5000
                    repeat(4) {
                        val input = socket.getInputStream()
                        val header = input.readNBytes(4)
                        check(header.size == 4)
                        val length = header.fold(0) { n, byte -> (n shl 8) or (byte.toInt() and 255) }
                        val request = JSONObject(String(input.readNBytes(length), Charsets.UTF_8)); observed += request
                        val result = JSONObject().put("id", request.getString("id")).put("ok", true).put("result", JSONObject())
                        socket.getOutputStream().write(MobileFrameCodec.encode(result.toString().toByteArray()))
                        socket.getOutputStream().flush()
                    }
                    finish.await(5, TimeUnit.SECONDS)
                } } catch (error: Throwable) { failure.set(error) }
            }.apply { start() }
            val client = MobileRpcClient(PairingCode.Route("127.0.0.1", server.localPort), { "fixture-token" },
                MobileAttachTicketContext("workspace", "surface", "fixture-ticket", null))
            try {
                client.connect()
                client.phonePushHostStatus { true }
                client.changePhonePushSettings(PhoneMacPushChange.Enabled(false)) { true }
                client.changePhonePushSettings(PhoneMacPushChange.Mode(PhoneMacPushMode.ALWAYS)) { true }
                client.changePhonePushSettings(PhoneMacPushChange.HideContent(true)) { true }
            } finally { finish.countDown(); client.close(); peer.join(6000) }
            failure.get()?.let { throw AssertionError(it) }; assertFalse(peer.isAlive)
            assertEquals(listOf("mobile.host.status") + List(3) { "phone_push.settings.update" }, observed.map { it.getString("method") })
            observed.forEach { request ->
                assertEquals("fixture-token", request.getJSONObject("auth").getString("stack_access_token"))
                assertFalse(request.getJSONObject("auth").has("attach_token"))
            }
            assertEquals(0, observed[0].getJSONObject("params").length())
            assertEquals(false, observed[1].getJSONObject("params").get("forwarding_enabled"))
            assertEquals("always", observed[2].getJSONObject("params").get("mode"))
            assertEquals(true, observed[3].getJSONObject("params").get("hide_content"))
            observed.drop(1).forEach { assertEquals(1, it.getJSONObject("params").length()) }
        }
    }

    @Test fun accountChangeDuringTokenLookupSendsNoMutationBytes() = runBlocking {
        ServerSocket(0).use { server ->
            val byte = CompletableDeferred<Int>()
            val peer = Thread { server.accept().use { socket ->
                socket.soTimeout = 3000
                try { byte.complete(socket.getInputStream().read()) }
                catch (_: SocketTimeoutException) { byte.complete(-2) }
            } }.apply { start() }
            var current = true
            val lookup = CompletableDeferred<Unit>(); val token = CompletableDeferred<String>()
            val client = MobileRpcClient(PairingCode.Route("127.0.0.1", server.localPort), { lookup.complete(Unit); token.await() })
            try {
                client.connect()
                val result = async { runCatching { client.changePhonePushSettings(PhoneMacPushChange.Enabled(false)) { current } } }
                lookup.await(); current = false; token.complete("fixture-token")
                assertTrue(result.await().isFailure)
            } finally { client.close(); peer.join(4000) }
            assertFalse(peer.isAlive); assertEquals(-1, byte.await())
        }
    }
}
