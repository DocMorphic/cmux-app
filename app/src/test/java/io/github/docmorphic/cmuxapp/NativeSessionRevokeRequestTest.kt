package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class NativeSessionRevokeRequestTest {
    private fun fresh()="header."+Base64.getUrlEncoder().withoutPadding().encodeToString("{\"exp\":${System.currentTimeMillis()/1000+3600}}".toByteArray())+".signature"
    @Test fun freshCapturedPairUsesExactDeleteHeadersAndAcceptsEmptyOrNonJsonSuccess()=runBlocking {
        MockWebServer().use { server ->
            val client=NativeOAuthClient("fixture-project","fixture-key",server.url("/"));val access=fresh()
            assertEquals(access,client.freshCapturedAccess(access,"captured-refresh"));assertEquals(0,server.requestCount)
            for(response in listOf(MockResponse().setResponseCode(204),MockResponse().setBody("not-json"))) {
                server.enqueue(response);client.revokeCapturedSession(NativeCapturedSession(access,"captured-refresh"))
                val request=server.takeRequest();assertEquals("DELETE",request.method);assertEquals("/api/v1/auth/sessions/current",request.path)
                assertEquals(access,request.getHeader("x-hexclave-access-token"));assertEquals("captured-refresh",request.getHeader("x-hexclave-refresh-token"))
                assertEquals("fixture-project",request.getHeader("x-hexclave-project-id"));assertEquals("fixture-key",request.getHeader("x-hexclave-publishable-client-key"))
                assertNull(request.getHeader("Authorization"));assertEquals(0L,request.bodySize)
            }
            client.revokeCapturedSession(NativeCapturedSession(null,null));assertEquals(2,server.requestCount)
        }
    }
    @Test fun refreshOnlyMintAndRejectedAccessRetryUseOnlyCapturedRefreshAndRetryOnce()=runBlocking {
        MockWebServer().use { server ->
            val client=NativeOAuthClient("fixture","public",server.url("/"))
            server.enqueue(MockResponse().setBody("{\"access_token\":\"minted\"}"))
            assertEquals("minted",client.freshCapturedAccess(null,"captured-refresh"))
            assertTrue(server.takeRequest().body.readUtf8().contains("refresh_token=captured-refresh"))
            server.enqueue(MockResponse().setResponseCode(401).setHeader("x-hexclave-known-error","invalid_access_token"))
            server.enqueue(MockResponse().setBody("{\"access_token\":\"replacement\"}"))
            server.enqueue(MockResponse().setResponseCode(204))
            client.revokeCapturedSession(NativeCapturedSession("old","captured-refresh"))
            val old=server.takeRequest();assertEquals("old",old.getHeader("x-hexclave-access-token"))
            assertTrue(server.takeRequest().body.readUtf8().contains("refresh_token=captured-refresh"))
            val replacement=server.takeRequest();assertEquals("replacement",replacement.getHeader("x-hexclave-access-token"))
            assertEquals(old.getHeader("x-hexclave-random-nonce"),replacement.getHeader("x-hexclave-random-nonce"))
            repeat(2) { server.enqueue(MockResponse().setResponseCode(401).setHeader("x-stack-known-error","invalid_access_token"));if(it==0)server.enqueue(MockResponse().setBody("{\"access_token\":\"new\"}")) }
            assertTrue(runCatching {client.revokeCapturedSession(NativeCapturedSession("old","captured-refresh"))}.isFailure)
            assertEquals(7,server.requestCount)
        }
    }
    @Test fun networkRetryKeepsTheCapturedPairAndCancellationEndsAHangingDeletePromptly()=runBlocking {
        MockWebServer().use { server ->
            val errors=java.util.concurrent.CopyOnWriteArrayList<String>()
            val http=okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
                try { chain.proceed(chain.request()) } catch(f:java.io.IOException) { errors+=f.toString();throw f }
            }.build()
            val client=NativeOAuthClient("fixture","public",server.url("/").newBuilder().host("127.0.0.1").build(),http)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));server.enqueue(MockResponse().setResponseCode(204))
            try { withTimeout(4000) { client.revokeCapturedSession(NativeCapturedSession("captured","refresh")) } }
            catch(f:Throwable) { throw AssertionError("Requests=${server.requestCount}; failures=$errors",f) }
            repeat(2) { assertEquals("captured",server.takeRequest().getHeader("x-hexclave-access-token")) }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request=async { runCatching { client.revokeCapturedSession(NativeCapturedSession("captured","refresh")) } }
            assertNotNull(withContext(Dispatchers.IO){server.takeRequest(2,TimeUnit.SECONDS)})
            request.cancel();withTimeout(1000) { request.join() };assertEquals(3,server.requestCount)
        }
    }
}
