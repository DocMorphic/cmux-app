package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NativeWebSessionBrokerTest {
    private val tokens = NativeWebSessionSnapshot("fixture-login", "fixture-access+&= x", "fixture-refresh&= x")
    private fun broker(server: MockWebServer, current: () -> Boolean = { true }, timeout: Long = 2000,
                       snapshot: suspend () -> NativeWebSessionSnapshot? = { tokens }) =
        NativeWebSessionBroker(WhatsNewWebPolicy(server.url("/").toString()), "project", snapshot, { current() }, timeout)
    private fun ready(vararg cookies: String) = MockResponse().setResponseCode(204)
        .addHeader("X-Cmux-App-Session-Handoff", "ready").apply { cookies.forEach { addHeader("Set-Cookie", it) } }
    private fun pair() = ready("stack-access=fixture-a; Path=/; HttpOnly", "stack-refresh-project=fixture-r; Path=/; HttpOnly")
    @Test fun exactOwnOriginPostEncodesTokensAndAfterButNoFragmentAndNoAuthorizationHeader() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(pair())
            val cookies = broker(server).cookies(server.url("/news?q=x%26y#secret-fragment").toString())
            assertEquals(setOf("stack-access", "stack-refresh-project"), cookies.map { it.name }.toSet())
            val request = server.takeRequest()
            assertEquals("POST", request.method); assertEquals(NativeWebSessionBroker.HANDOFF_PATH, request.path)
            assertEquals("1", request.getHeader("X-Cmux-App-Session-Handoff")); assertEquals("cookies", request.getHeader("X-Cmux-App-Session-Response"))
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
            val values = request.body.readUtf8().split('&').associate { field ->
                val (name, value) = field.split('=', limit = 2); name to URLDecoder.decode(value, "UTF-8")
            }
            assertEquals(mapOf("access_token" to tokens.accessToken, "refresh_token" to tokens.refreshToken, "after" to "/news?q=x%26y"), values)
        }
    }
    @Test fun redirectsNeverSendCredentialsToAnotherEndpoint() = runBlocking<Unit> {
        MockWebServer().use { server -> MockWebServer().use { destination ->
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", destination.url("/collect")))
            assertTrue(broker(server).cookies(server.url("/news").toString()).isEmpty())
            assertEquals(1, server.requestCount); assertEquals(0, destination.requestCount)
        } }
    }
    @Test fun offAllowlistAndHandoffPageNeverEvenAcquireTokens() = runBlocking<Unit> {
        MockWebServer().use { server ->
            var snapshots = 0
            val b = broker(server, snapshot = { snapshots++; tokens })
            for (url in listOf("https://cmux.com.evil.test/news", "http://cmux.com/news", "file:///tmp/news", server.url(NativeWebSessionBroker.HANDOFF_PATH).toString()))
                assertTrue(b.cookies(url).isEmpty())
            assertEquals(0, snapshots); assertEquals(0, server.requestCount)
        }
    }
    @Test fun absentOrBlankSessionIsAnonymousAndTokensAreRedacted() = runBlocking<Unit> {
        MockWebServer().use { server ->
            assertTrue(broker(server, snapshot = { null }).cookies(server.url("/news").toString()).isEmpty())
            assertTrue(broker(server, snapshot = { NativeWebSessionSnapshot("login", "", "r") }).cookies(server.url("/news").toString()).isEmpty())
            assertEquals(0, server.requestCount)
            assertEquals("NativeWebSessionSnapshot(redacted)", tokens.toString())
        }
    }
    @Test fun onlyExpectedStatusAndReadyMarkerAreAccepted() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for (response in listOf(pair().setResponseCode(200), pair().setResponseCode(401), pair().setHeader("X-Cmux-App-Session-Handoff", "wrong"))) {
                server.enqueue(response)
                assertTrue(broker(server).cookies(server.url("/news").toString()).isEmpty())
            }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun exactDestinationCookieScopeAndCompleteProjectPairRequired() {
        val target = "https://cmux.com/news".toHttpUrl()
        fun accepted(vararg cookies: String): List<Cookie> {
            val headers = Headers.Builder().add("X-Cmux-App-Session-Handoff", "ready")
            cookies.forEach { headers.add("Set-Cookie", it) }
            return NativeWebSessionBroker.acceptedCookies(Response.Builder().request(Request.Builder().url(target).build())
                .protocol(Protocol.HTTP_1_1).code(204).message("ok").headers(headers.build()).build(), target, "project")
        }
        assertTrue(accepted("stack-access=a; Path=/").isEmpty())
        assertTrue(accepted("stack-access=a; Path=/", "stack-refresh-other=r; Path=/").isEmpty())
        assertTrue(accepted("stack-access=a; Domain=evil.test", "stack-refresh-project=r").isEmpty())
        assertTrue(accepted("stack-access=a; Max-Age=0", "stack-refresh-project=r").isEmpty())
        val names = accepted("stack-access=a; Domain=.cmux.com", "stack-refresh-project--0=r0", "stack-refresh-project--1=r1",
            "unrelated=discard", "stack-refresh-other=discard").map { it.name }
        assertEquals(listOf("stack-access", "stack-refresh-project--0", "stack-refresh-project--1"), names)
        assertEquals(2, accepted("__Host-hexclave-access=a; Path=/; Secure", "__Secure-hexclave-refresh-project=r; Secure").size)
    }
    @Test fun responseOriginMustMatchSchemeHostAndEffectivePort() {
        val target = "https://cmux.com/news".toHttpUrl()
        for (origin in listOf("http://cmux.com", "https://www.cmux.com", "https://cmux.com:8443")) {
            val response = Response.Builder().request(Request.Builder().url(origin).build()).protocol(Protocol.HTTP_1_1).code(204).message("ok")
                .header("X-Cmux-App-Session-Handoff", "ready").addHeader("Set-Cookie", "stack-access=a")
                .addHeader("Set-Cookie", "stack-refresh-project=r").build()
            assertTrue(NativeWebSessionBroker.acceptedCookies(response, target, "project").isEmpty())
        }
    }
    @Test fun accountReplacementDuringExchangeDropsCookiesAndNeverRetries() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val current = AtomicBoolean(true)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
                current.set(false); return pair()
            } }
            assertTrue(broker(server, current = current::get).cookies(server.url("/news").toString()).isEmpty())
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun cancelledExchangeDoesNotBecomeAnonymousSuccessAndTimeoutHasNoRetry() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val task = async { broker(server, timeout = 10_000).cookies(server.url("/news").toString()) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            task.cancelAndJoin(); assertTrue(task.isCancelled)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            assertTrue(broker(server, timeout = 50).cookies(server.url("/news").toString()).isEmpty())
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun previousHandoffCookiesAreNeverSentOnAnotherExchange() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val b = broker(server)
            repeat(2) { server.enqueue(pair()); assertEquals(2, b.cookies(server.url("/news").toString()).size) }
            repeat(2) { assertNull(server.takeRequest().getHeader("Cookie")) }
        }
    }
}
