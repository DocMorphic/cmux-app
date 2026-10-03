package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NativeAccountDeletionTest {
    private fun client(server: MockWebServer, current: () -> Boolean = { true }, timeout: Long = 3000,
                       tokens: suspend () -> NativeDeletionCredentials? = { NativeDeletionCredentials("fixture-access", "fixture-refresh") }) =
        NativeAccountDeletionClient(tokens, current, server.url("/"), timeoutMillis = timeout)

    @Test fun exactNativeDeleteHeadersAndAcceptedResults() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for ((status, body, expected) in listOf(
                Triple(204, "", NativeAccountDeletionResult.COMPLETED),
                Triple(200, "{}", NativeAccountDeletionResult.COMPLETED),
                Triple(202, "{\"cleanupIncomplete\":true}", NativeAccountDeletionResult.CLEANUP_INCOMPLETE),
                Triple(202, "{\"deletionPending\":true,\"cleanupIncomplete\":true}", NativeAccountDeletionResult.UNKNOWN))) {
                server.enqueue(MockResponse().setResponseCode(status).apply { if (status != 204) setBody(body) })
                assertEquals(expected, client(server).delete())
                val request = server.takeRequest()
                assertEquals("DELETE", request.method); assertEquals("/api/account", request.path)
                assertEquals("Bearer fixture-access", request.getHeader("Authorization"))
                assertEquals("fixture-refresh", request.getHeader("X-Stack-Refresh-Token"))
                assertEquals(0, request.bodySize)
            }
        }
    }

    @Test fun rejectionPartialDeletionAndUnknownResultsAreNotInterchangeableOrRetried() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val cases = listOf(
                Triple(401, "{}", NativeAccountDeletionResult.UNAUTHORIZED),
                Triple(500, "{\"error\":\"account_delete_retryable\"}", NativeAccountDeletionResult.PARTIAL),
                Triple(503, "{\"error\":\"account_stack_delete_failed_after_data_delete\"}", NativeAccountDeletionResult.PARTIAL),
                Triple(500, "{\"error\":\"account_delete_failed\"}", NativeAccountDeletionResult.REJECTED),
                Triple(400, "{}", NativeAccountDeletionResult.REJECTED),
                Triple(429, "{}", NativeAccountDeletionResult.REJECTED),
                Triple(408, "{}", NativeAccountDeletionResult.UNKNOWN),
                Triple(502, "gateway failure", NativeAccountDeletionResult.UNKNOWN))
            for ((status, body, expected) in cases) {
                server.enqueue(MockResponse().setResponseCode(status).setBody(body))
                assertEquals(expected, client(server).delete())
            }
            assertEquals(cases.size, server.requestCount)
        }
    }

    @Test fun redirectsCannotLeakCredentialsOrRepeatDelete() = runBlocking<Unit> {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/api/account")))
            assertEquals(NativeAccountDeletionResult.REJECTED, client(server).delete())
            assertEquals(1, server.requestCount); assertEquals(0, other.requestCount)
        } }
    }

    @Test fun lostReplyTimeoutAndOversizeResponseNeverClaimCompletion() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertEquals(NativeAccountDeletionResult.UNKNOWN, client(server).delete())
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            assertEquals(NativeAccountDeletionResult.TIMED_OUT, client(server, timeout = 150).delete())
            server.enqueue(MockResponse().setBody("x".repeat(65537)))
            assertEquals(NativeAccountDeletionResult.UNKNOWN, client(server).delete())
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun changedAccountDuringCredentialsOrHttpCannotDeleteOrPublishForReplacement() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val current = AtomicBoolean(true)
            val before = client(server, current::get) {
                current.set(false); NativeDeletionCredentials("fixture-access", "fixture-refresh")
            }
            assertTrue(runCatching { before.delete() }.exceptionOrNull() is CancellationException)
            assertEquals(0, server.requestCount)
            current.set(true)
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(250, TimeUnit.MILLISECONDS))
            val pending = async { runCatching { client(server, current::get).delete() } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            current.set(false)
            assertTrue(pending.await().exceptionOrNull() is CancellationException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun invalidCredentialsNeverSendAndTransportCancellationDoesNotRetry() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for (tokens in listOf(null, NativeDeletionCredentials("bad\nheader", "refresh"), NativeDeletionCredentials("access", ""))) {
                assertEquals(NativeAccountDeletionResult.UNAUTHORIZED, client(server, tokens = { tokens }).delete())
            }
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = launch { client(server).delete() }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            pending.cancelAndJoin()
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun insecureOrCredentialBearingOriginsAreRejected() {
        for (origin in listOf("http://example.com/", "https://user:password@example.com/", "https://example.com/?key=value", "https://example.com/#fragment"))
            assertTrue(runCatching { NativeAccountDeletionClient({ null }, { true }, origin.toHttpUrl()) }.isFailure)
    }
}
