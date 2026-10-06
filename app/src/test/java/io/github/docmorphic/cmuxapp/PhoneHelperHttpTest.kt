package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PhoneHelperHttpTest {
    private class Fixture : AutoCloseable {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        private val serverKeys = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientKeys = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverKeys.sslSocketFactory(), false); start() }
        val base = OkHttpClient.Builder().sslSocketFactory(clientKeys.sslSocketFactory(), clientKeys.trustManager).build()
        val endpoint get() = server.url("/v1/push/enroll").toString()
        override fun close() { server.close(); base.connectionPool.evictAll(); base.dispatcher.executorService.shutdown() }
    }
    private fun json(value: String = "{\"fixture\":true}") = MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody(value)
    @Test fun exactTlsEndpointAndJsonBodyDoNotCarryAmbientCredentialsOrCookies() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(json())
            val ambient = f.base.newBuilder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "ambient-token").build()) }
                .cookieJar(object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
                    override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("ambient").value("cookie").domain(url.host).build())
                }).build()
            PhoneHelperHttp(f.endpoint, { true }, ambient).use { http ->
                assertTrue(http.send("begin", JSONObject().put("token", "fixture-token/λ中")) is PhoneHelperHttpResult.Success)
                val request = f.server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/v1/push/enroll", request.path); assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
                val sent = JSONObject(request.body.readUtf8()); assertEquals("begin", sent.getString("step"))
                assertEquals("fixture-token/λ中", sent.getJSONObject("request").getString("token"))
            }
        }
    }
    @Test fun redirectsNeverForwardProofOrTokenEvenWhenBaseClientFollowsThem() = runBlocking {
        Fixture().use { f -> Fixture().use { other ->
            f.server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.endpoint))
            PhoneHelperHttp(f.endpoint, { true }, f.base.newBuilder().followRedirects(true).build()).use { http ->
                assertEquals(PhoneHelperHttpResult.Rejected(307), http.send("begin", JSONObject().put("token", "private-fixture")))
                assertEquals(1, f.server.requestCount); assertEquals(0, other.server.requestCount)
            }
        } }
    }
    @Test fun retryAfterStopsRequestsThenRetriesTheSameProof() = runBlocking {
        Fixture().use { f ->
            f.server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "20")); f.server.enqueue(json())
            var time = 1_800_000_000_000L; val payload = JSONObject().put("proof", "fixture-proof")
            PhoneHelperHttp(f.endpoint, { true }, f.base, { time }).use { http ->
                val expected = PhoneHelperHttpResult.Retry(time + 20_000)
                assertEquals(expected, http.send("finish", payload)); assertEquals(expected, http.send("finish", payload)); assertEquals(1, f.server.requestCount)
                time += 20_000; assertTrue(http.send("finish", payload) is PhoneHelperHttpResult.Success)
                assertEquals(f.server.takeRequest().body.readUtf8(), f.server.takeRequest().body.readUtf8())
            }
        }
    }
    @Test fun revocationBeforeBodyWriteAndDuringResponseNeverReturnsUsableSuccess() = runBlocking {
        Fixture().use { f ->
            val allowed = AtomicBoolean(false)
            PhoneHelperHttp(f.endpoint, allowed::get, f.base).use { http ->
                assertEquals(PhoneHelperHttpResult.Retired, http.send("begin", JSONObject())); assertEquals(0, f.server.requestCount)
            }
            allowed.set(true)
            val lateRevoke = f.base.newBuilder().eventListener(object : EventListener() {
                override fun requestHeadersEnd(call: Call, request: Request) { allowed.set(false) }
            }).build()
            PhoneHelperHttp(f.endpoint, allowed::get, lateRevoke).use { http ->
                assertEquals(PhoneHelperHttpResult.Retired, http.send("begin", JSONObject().put("token", "private-fixture")))
                f.server.takeRequest(1, TimeUnit.SECONDS)?.let { assertEquals(0, it.body.size) }
            }
        }
        Fixture().use { f ->
            val allowed = AtomicBoolean(true); f.server.enqueue(json().setBodyDelay(200, TimeUnit.MILLISECONDS))
            PhoneHelperHttp(f.endpoint, allowed::get, f.base).use { http ->
                val result = async(Dispatchers.IO) { http.send("begin", JSONObject()) }
                assertNotNull(withContext(Dispatchers.IO) { f.server.takeRequest(2, TimeUnit.SECONDS) }); allowed.set(false)
                assertEquals(PhoneHelperHttpResult.Retired, result.await())
            }
        }
    }
    @Test fun oversizedOrMalformedResponsesAreRejectedAndCloseCancelsInFlight() = runBlocking {
        Fixture().use { f ->
            for (reply in listOf(json("x".repeat(PhoneHelperHttp.MAX_BODY + 1)), json("{bad"), MockResponse().setBody("{}"))) {
                f.server.enqueue(reply)
                PhoneHelperHttp(f.endpoint, { true }, f.base).use { http -> assertEquals(PhoneHelperHttpResult.Rejected(200), http.send("begin", JSONObject())) }
            }
            f.server.enqueue(json().setBodyDelay(2, TimeUnit.SECONDS))
            val http = PhoneHelperHttp(f.endpoint, { true }, f.base)
            val result = async(Dispatchers.IO) { http.send("begin", JSONObject()) }
            repeat(3) { f.server.takeRequest() }
            assertNotNull(withContext(Dispatchers.IO) { f.server.takeRequest(2, TimeUnit.SECONDS) }); http.close()
            assertEquals(PhoneHelperHttpResult.Retired, withTimeout(1000) { result.await() })
        }
    }
    @Test fun cleartextEndpointsAreRejectedAndUntrustedCertificatesCannotCompleteTls() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { PhoneHelperHttp("http://localhost/v1/push/enroll", { true }) }
        Fixture().use { f ->
            PhoneHelperHttp(f.endpoint, { true }).use { http ->
                assertTrue(http.send("begin", JSONObject()) is PhoneHelperHttpResult.Retry); assertEquals(0, f.server.requestCount)
            }
        }
    }
}
