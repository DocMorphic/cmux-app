package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class NativeFeedbackClientTest {
    private val stamp = NativeFeedbackStamp("0.2", "411", "io.github.docmorphic.cmuxapp", "prod", "Android 17", "Pixel 6a", "en-US")
    @Test fun publicSubmissionUsesOnlyTheDocumentedFieldsAndStamp() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201)); server.start()
            NativeFeedbackClient(server.url("/")).submit(" user@example.test ", " Unicode 你好\nsecond line ", stamp)
            val request = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("POST", request.method); assertEquals("/api/feedback", request.path)
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie"))
            val body = request.body.readUtf8()
            val fields = Regex("name=\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
            assertEquals(listOf("email", "message", "appVersion", "appBuild", "bundleIdentifier", "buildType", "osVersion", "hardwareModel", "locale"), fields)
            assertTrue(body.contains("Unicode 你好\nsecond line\r\n")); assertTrue(body.contains("Pixel 6a"))
            assertFalse(body.contains("terminal_text")); assertFalse(body.contains("diagnostic"))
        }
    }
    @Test fun validationRejectsMissingEmailBlankMessageAndOversizedMessageBeforeNetwork() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = NativeFeedbackClient(server.url("/"))
            for ((email, message) in listOf("invalid" to "hello", "user@example.test" to " \n", "user@example.test" to "x".repeat(4001))) {
                try { client.submit(email, message, stamp); fail("Invalid form sent") } catch (_: IllegalArgumentException) { }
            }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun redirectAndRejectionNeverResubmitAutomatically() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = NativeFeedbackClient(server.url("/"))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/elsewhere")))
            try { client.submit("user@example.test", "hello", stamp); fail("Redirect accepted") } catch (_: java.io.IOException) { }
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(429))
            try { client.submit("user@example.test", "hello", stamp); fail("Rejection accepted") }
            catch (e: java.io.IOException) { assertTrue(e.message!!.contains("Try again later")) }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun cancellationStopsThePendingCallWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val call = launch { NativeFeedbackClient(server.url("/")).submit("user@example.test", "hello", stamp) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            call.cancelAndJoin(); assertTrue(call.isCancelled); assertEquals(1, server.requestCount)
        }
    }
}
