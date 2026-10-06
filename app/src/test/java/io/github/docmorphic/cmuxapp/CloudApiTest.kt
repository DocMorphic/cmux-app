package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CloudApiTest {
    private val owner = CloudAccountScope("login", "user", "team", 7)
    private fun credentials(scope: CloudAccountScope = owner) = CloudCredentials(scope, "fixture-access", "fixture-refresh")

    @Test fun requestsEncodeOneMachineSegmentAndCarryCoherentAccountHeaders() {
        val builder = CloudApiRequests()
        val request = builder.request(builder.attach(" vm/a%2Fb?# ", "fingerprint"), credentials())
        assertEquals("/api/vm/vm%2Fa%252Fb%3F%23/attach-endpoint", request.url.encodedPath)
        assertEquals("Bearer fixture-access", request.header("Authorization"))
        assertEquals("fixture-refresh", request.header("X-Stack-Refresh-Token"))
        assertEquals("team", request.header("X-Cmux-Team-Id"))
        val personal = builder.request(builder.list(), credentials(owner.copy(team = null)))
        assertNull(personal.header("X-Cmux-Team-Id"))
        assertFalse(credentials().toString().contains("fixture-access"))
        for (id in listOf(" ", ".", "..")) assertTrue(runCatching { builder.delete(id) }.isFailure)
        for (origin in listOf("http://cmux.com", "https://cmux.com/path", "https://user@cmux.com", "https://cmux.com?x=1", "https://cmux.com#x"))
            assertTrue(runCatching { CloudApiRequests(origin) }.isFailure)
    }

    @Test fun operationShapesMatchCreateLifecycleAndSeparateTunnelRoles() {
        val builder = CloudApiRequests()
        val options = CloudMachineCreateOptions(CloudMachineKind.DESKTOP, " provider ", " image ", true, true, 8192)
        val create = builder.create(options, " fixed-operation ")
        assertEquals(960L, create.deadlineSeconds); assertEquals("fixed-operation", create.idempotencyKey)
        val body = JSONObject(create.body!!)
        assertEquals("desktop", body.getString("kind")); assertEquals("provider", body.getString("provider"))
        assertEquals(8192, body.getInt("memoryMb")); assertTrue(body.getBoolean("persistentHome")); assertTrue(body.getBoolean("perMachineHome"))
        assertEquals(setOf("kind"), JSONObject(builder.create(CloudMachineCreateOptions(), "op").body!!).keys().asSequence().toSet())
        assertEquals("POST", builder.pause("vm").method); assertEquals("pause", builder.pause("vm").segments.last())
        assertEquals(960L, builder.resume("vm").deadlineSeconds)
        assertEquals("DELETE", builder.delete("vm").method)
        for (purpose in CloudTunnelPurpose.entries) {
            val enrollment = JSONObject(builder.enroll("public", "device", "fingerprint", purpose, "Pixel").body!!)
            assertEquals("device", enrollment.getString("deviceId")); assertEquals(purpose.wire, enrollment.getString("tunnelPurpose"))
            val revoke = builder.revoke("fingerprint", purpose)
            assertEquals("DELETE", revoke.method); assertEquals(purpose.wire, JSONObject(revoke.body!!).getString("tunnelPurpose"))
        }
        val attach = builder.attach("vm", "f", listOf(" a ", "a", "UPPER", "../bad") + (1..30).map { "cap-$it" })
        val capabilities = JSONObject(attach.body!!).getJSONArray("clientCapabilities")
        assertEquals(16, capabilities.length()); assertEquals("a", capabilities.getString(0)); assertEquals(90L, attach.deadlineSeconds)
        assertEquals("claim", JSONObject(builder.approve("vm", "claim").body!!).getString("invitationId"))
    }

    @Test fun catalogUsesCapturedTeamAndNeverRetainsCookies() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"vms\":[]}").addHeader("Set-Cookie", "session=private; Path=/"))
            server.enqueue(MockResponse().setBody("{\"vms\":[]}"))
            CloudApi(owner, { credentials() }, { true }, CloudApiRequests(server.url("/").toString())).use { api ->
                assertTrue(api.catalog().machines.isEmpty()); api.catalog()
                val first = server.takeRequest(); val second = server.takeRequest()
                assertEquals("team", first.getHeader("X-Cmux-Team-Id"))
                assertEquals("Bearer fixture-access", first.getHeader("Authorization"))
                assertEquals("fixture-refresh", first.getHeader("X-Stack-Refresh-Token"))
                assertNull(second.getHeader("Cookie")); assertEquals("/api/vm", second.path)
            }
        }
    }

    @Test fun redirectsCannotForwardTokensAndMutationsAreNotAutomaticallyRetried() = runBlocking {
        MockWebServer().use { source -> MockWebServer().use { destination ->
            source.enqueue(MockResponse().setResponseCode(307).addHeader("Location", destination.url("/stolen")))
            source.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"Try later","action":"retry"}"""))
            CloudApi(owner, { credentials() }, { true }, CloudApiRequests(source.url("/").toString())).use { api ->
                assertEquals(307, (runCatching { api.catalog() }.exceptionOrNull() as CloudApiFailure).status)
                val failure = runCatching { api.create(CloudMachineCreateOptions(), "persisted-op") }.exceptionOrNull() as CloudApiFailure
                assertEquals("retry", failure.action); assertEquals("Try later", failure.message)
                assertEquals(0, destination.requestCount); assertEquals(2, source.requestCount)
                source.takeRequest()
                assertEquals("persisted-op", source.takeRequest().getHeader("Idempotency-Key"))
            }
        } }
    }

    @Test fun teamChangeDuringTokenAcquisitionPreventsTransmission() = runBlocking {
        MockWebServer().use { server ->
            val gate = CompletableDeferred<Unit>(); var current = true
            CloudApi(owner, { gate.await(); credentials() }, { current }, CloudApiRequests(server.url("/").toString())).use { api ->
                val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { api.catalog() } }
                current = false; gate.complete(Unit)
                assertTrue(pending.await().exceptionOrNull() is CancellationException)
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun mismatchedCredentialOwnerNeverReachesTheWire() = runBlocking {
        MockWebServer().use { server ->
            CloudApi(owner, { credentials(owner.copy(generation = 8)) }, { true }, CloudApiRequests(server.url("/").toString())).use { api ->
                assertTrue(runCatching { api.pause("vm") }.exceptionOrNull() is CancellationException)
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun retiredAccountCannotPublishAnOldTokenRefreshError() = runBlocking {
        MockWebServer().use { server ->
            val gate = CompletableDeferred<Unit>(); var current = true
            CloudApi(owner, { gate.await(); throw java.io.IOException("Old session refresh failed") },
                { current }, CloudApiRequests(server.url("/").toString())).use { api ->
                val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { api.catalog() } }
                current = false; gate.complete(Unit)
                assertTrue(pending.await().exceptionOrNull() is CancellationException)
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun lateSuccessAndFailureCannotPublishIntoReplacementAccount() = runBlocking {
        for (status in listOf(200, 401)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("{\"vms\":[]}").setBodyDelay(150, TimeUnit.MILLISECONDS))
            var current = true
            CloudApi(owner, { credentials() }, { current }, CloudApiRequests(server.url("/").toString())).use { api ->
                val pending = async { runCatching { api.catalog() } }
                withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
                current = false
                assertTrue(withTimeout(3000) { pending.await() }.exceptionOrNull() is CancellationException)
            }
        }
    }

    @Test fun closingOwnerCancelsPendingCallsAndRejectsFutureCalls() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val api = CloudApi(owner, { credentials() }, { true }, CloudApiRequests(server.url("/").toString()))
            val pending = async { runCatching { api.catalog() } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            api.close()
            assertTrue(withTimeout(3000) { pending.await() }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { api.catalog() }.exceptionOrNull() is CancellationException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun oversizedChunkedResponsesAreBounded() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("x".repeat(2 * 1024 * 1024 + 1), 8192))
            CloudApi(owner, { credentials() }, { true }, CloudApiRequests(server.url("/").toString())).use { api ->
                assertEquals("Cloud response is too large", runCatching { api.catalog() }.exceptionOrNull()?.message)
            }
        }
    }
}
