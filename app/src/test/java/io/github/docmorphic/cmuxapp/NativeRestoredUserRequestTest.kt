package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NativeRestoredUserRequestTest {
    @Test fun exactRejectedAccessCodeRefreshesOnceAndUsesTheReplacementToken()=runBlocking {
        MockWebServer().use { server ->
            val client=NativeOAuthClient("fixture", "public", server.url("/")); var refreshes=0
            server.enqueue(MockResponse().setResponseCode(401).setHeader("x-stack-known-error", "invalid_access_token").setBody("{}"))
            server.enqueue(MockResponse().setBody("{\"id\":\"verified\"}"))
            assertEquals("verified",client.restoredCurrentUser("old") { refreshes++;"new" }?.id)
            assertEquals(1,refreshes)
            assertEquals("old",server.takeRequest().getHeader("x-stack-access-token"))
            assertEquals("new",server.takeRequest().getHeader("x-stack-access-token"))
            repeat(2) { server.enqueue(MockResponse().setResponseCode(401).setHeader("x-stack-known-error", "invalid_access_token").setBody("{}")) }
            assertTrue(runCatching { client.restoredCurrentUser("old") { refreshes++;"new" } }.exceptionOrNull() is NativeSessionUserUnavailable)
            assertEquals(2,refreshes);assertEquals(4,server.requestCount)
        }
    }
    @Test fun sdkCollapsedFailuresStayDistinctFromNilJsonAndRateLimits()=runBlocking {
        MockWebServer().use { server ->
            val client=NativeOAuthClient("fixture", "public", server.url("/"));var refreshes=0
            for(response in listOf(MockResponse().setResponseCode(503),
                MockResponse().setResponseCode(401).setHeader("x-stack-known-error","INVALID_ACCESS_TOKEN"),
                MockResponse().setBody("{\"id\":\"anonymous\",\"is_anonymous\":true}"))) {
                server.enqueue(response)
                assertTrue(runCatching { client.restoredCurrentUser("old") { refreshes++;"new" } }.exceptionOrNull() is NativeSessionUserUnavailable)
            }
            for(raw in listOf("not-json", "null", "[]")) {
                server.enqueue(MockResponse().setBody(raw))
                assertNull(client.restoredCurrentUser("old") { error("No refresh") })
            }
            server.enqueue(MockResponse().setResponseCode(429))
            val failure=runCatching { client.restoredCurrentUser("old") { error("No refresh") } }.exceptionOrNull()
            assertEquals("RATE_LIMITED",(failure as NativeAccountRequestFailure).code)
            assertEquals(0,refreshes)
        }
    }
}
