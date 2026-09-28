package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class IrohV2ControlTransportTest {
    private val client = OkHttpClient()
    private fun request(id: String) = JSONObject().put("schemaId", "directory.request.v1").put("requestId", id)
    private fun ready() = JSONObject().put("schemaId", "session.ready.v1").put("requestId", "open")
        .put("sessionId", "fixture").put("teamRevision", 0)

    @Test fun websocketMatchesOutOfOrderRepliesAcknowledgesReceiptsAndForwardsChanges() = runBlocking {
        MockWebServer().use { server ->
            val acknowledgement = CompletableDeferred<JSONObject>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                private val requests = mutableListOf<String>()
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(ready().put("deliveryReceipt", JSONObject().put("sequence", 8)
                        .put("token", "AAAAAAAAAAAAAAAAAAAAAA")).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = JSONObject(text)
                    if (message.getString("schemaId") == "session.ack.v1") {
                        acknowledgement.complete(message); return
                    }
                    requests += message.getString("requestId")
                    if (requests.size == 2) {
                        for (id in requests.reversed()) webSocket.send(JSONObject().put("schemaId", "directory.result.v1")
                            .put("requestId", id).put("directory", JSONObject().put("fixture", id)).toString())
                        webSocket.send("""{"schemaId":"directory.changed.v1","teamId":"team","revision":9}""")
                    }
                }
            }))
            val (socket, opened) = IrohV2ControlSocket.open(client,
                Request.Builder().url(server.url("/v2/control/socket")).header("x-cmux-v2-setup", "fixture-setup").build(), "open")
            socket.use {
                assertEquals("fixture", opened.getString("sessionId"))
                assertEquals(8L, withTimeout(2_000) { acknowledgement.await() }.getLong("sequence"))
                val one = async { socket.request(request("one")) }
                val two = async { socket.request(request("two")) }
                assertEquals("one", one.await().getJSONObject("directory").getString("fixture"))
                assertEquals("two", two.await().getJSONObject("directory").getString("fixture"))
                assertEquals(9L, withTimeout(2_000) { socket.events.first() }.getLong("revision"))
                assertEquals("fixture-setup", server.takeRequest().getHeader("x-cmux-v2-setup"))
            }
        }
    }

    @Test fun lateRepliesNeverSatisfyAnotherCallAndMutationsAreSentOnce() = runBlocking {
        MockWebServer().use { server ->
            val peer = CompletableDeferred<WebSocket>()
            val ids = mutableListOf<String>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { peer.complete(webSocket); webSocket.send(ready().toString()) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val id = JSONObject(text).getString("requestId")
                    synchronized(ids) { ids += id }
                    if (id == "next") {
                        webSocket.send("""{"schemaId":"operation.completed.v1","requestId":"old","revision":1}""")
                        webSocket.send("""{"schemaId":"directory.result.v1","requestId":"next","directory":{}}""")
                    }
                }
            }))
            val (socket, _) = IrohV2ControlSocket.open(client, Request.Builder().url(server.url("/v2/control/socket")).build(), "open", 300)
            socket.use {
                try { socket.request(JSONObject().put("schemaId", "device.metadata.v1").put("requestId", "old")); fail("Expected timeout") }
                catch (_: TimeoutCancellationException) { }
                assertEquals("next", socket.request(request("next")).getString("requestId"))
                assertEquals(listOf("old", "next"), synchronized(ids) { ids.toList() })
            }
        }
    }

    @Test fun revocationFailsPendingCallsAndCloseStopsFutureCalls() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(ready().toString()) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val id = JSONObject(text).getString("requestId")
                    webSocket.send(JSONObject().put("schemaId", "error.v1").put("requestId", id)
                        .put("code", "team_access_revoked").put("retryable", false).toString())
                }
            }))
            val (socket, _) = IrohV2ControlSocket.open(client, Request.Builder().url(server.url("/v2/control/socket")).build(), "open")
            socket.use {
                try { socket.request(request("one")); fail("Revocation accepted") }
                catch (failure: IrohV2ServerFailure) { assertEquals("team_access_revoked", failure.code) }
                try { socket.request(request("two")); fail("Revoked socket reused") }
                catch (failure: IrohV2ServerFailure) { assertFalse(failure.retryable) }
            }
        }
    }

    @Test fun httpPreservesTypedErrorsRefusesRedirectsAndCorrelatesReplies() = runBlocking {
        MockWebServer().use { server ->
            IrohV2ControlHttp(client).use { http ->
                server.enqueue(MockResponse().setResponseCode(429).setBody("""{"schemaId":"error.v1","requestId":"limited","code":"rate_limited","retryable":true,"retryAfterMs":1234}"""))
                try { http.exchange(Request.Builder().url(server.url("/v2/requests")).build(), "limited", "directory.result.v1"); fail() }
                catch (failure: IrohV2ServerFailure) { assertEquals(1234L, failure.retryAfterMs); assertTrue(failure.retryable) }
                server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/should-not-follow")))
                try { http.exchange(Request.Builder().url(server.url("/v2/requests")).build(), "redirect", "directory.result.v1"); fail() }
                catch (failure: IrohV2HttpFailure) { assertEquals(307, failure.status) }
                server.enqueue(MockResponse().setBody("""{"schemaId":"directory.result.v1","requestId":"wrong","directory":{}}"""))
                try { http.exchange(Request.Builder().url(server.url("/v2/requests")).build(), "correct", "directory.result.v1"); fail() }
                catch (_: IOException) { }
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun closingHttpCancelsBlockedResponseAndOversizeResponsesAreRejected() = runBlocking {
        MockWebServer().use { server ->
            IrohV2ControlHttp(client).use { http ->
                server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", IrohV2Wire.MAX_REPLY + 1))
                try { http.exchange(Request.Builder().url(server.url("/large")).build(), "large", "directory.result.v1"); fail() }
                catch (failure: IOException) { assertTrue(failure.message.orEmpty().contains("too large")) }
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val pending = async {
                    runCatching { http.exchange(Request.Builder().url(server.url("/blocked")).build(), "blocked", "directory.result.v1") }
                }
                withContext(Dispatchers.IO) {
                    server.takeRequest(2, TimeUnit.SECONDS)
                    checkNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                }
                http.close()
                assertTrue(withTimeout(2_000) { pending.await() }.isFailure)
            }
        }
    }
}
