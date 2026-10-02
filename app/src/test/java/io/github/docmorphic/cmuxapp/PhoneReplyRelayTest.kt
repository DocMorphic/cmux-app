package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

class PhoneReplyRelayTest {
    private val team = NativeTeamScope("login", "test-account", "test-team", 1)
    private val phone = PhonePushIdentity("test-phone", "test-sender-key", ByteArray(32) { (it + 32).toByte() })
    private val mac = PhonePushIdentity("test-mac-installation", "test-recipient-key", ByteArray(32) { it.toByte() })
    private val tuple = PhonePushTuple(team.userId, null, "io.github.docmorphic.cmuxapp", phone.installationID,
        "test-mac", "test-instance", "dev.cmux.app")
    private val peer = PhonePushPeer(tuple, mac.descriptor())
    private val time = 1_800_000_000_000L
    private fun prepared(text: String = "Fixture reply λ 中", replyID: String = "fixture-reply") =
        PreparedPhoneReply.prepare(replyID, team, "origin", peer, phone, "workspace", "surface", false, text, time)

    @Test fun directFenceCannotReachHttpOrRequestAnAccountToken() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            PhoneReplyRelay(server.url("/"), { error("Must not fetch a token") }, { true }, now = { time }).use { relay ->
                assertEquals(PhoneReplyRelayResult.Retired, relay.send(prepared().withDirectFence(true)))
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun replyTargetsMacAndPreservesUnicodeWithoutChangingIncomingPushLimits() {
        val messages = listOf("Fixture reply λ 中", "中".repeat(8192))
        val records = messages.mapIndexed { index, text ->
            val reply = prepared(text, "fixture-$index")
            val body = JSONObject(reply.body); val envelope = body.getJSONObject("encryptedPayload")
            assertEquals("test-mac-installation", envelope.getString("installationID"))
            assertEquals("test-phone", envelope.getJSONObject("tuple").getString("iosInstallationID"))
            assertFalse(reply.body.contains(text)); assertFalse(body.has("text")); assertFalse(body.has("surfaceId"))
            if (index == 1) assertTrue(runCatching { PhonePushEnvelope.parse(envelope) }.isFailure)
            JSONObject().put("request", body).put("expectedText", text).put("issuedAt", time / 1000.0)
        }
        System.getenv("CMUX_REPLY_CROSS_OUTPUT")?.let { path ->
            val output = File(path); output.parentFile?.mkdirs()
            output.writeText(JSONArray(records).toString(2))
        }
    }

    @Test fun incompatibleIdentityMissingTargetAndOversizeTextAreRejectedBeforeEncryption() {
        assertTrue(runCatching { prepared("x".repeat(8193)) }.isFailure)
        assertTrue(runCatching { prepared(" ") }.isFailure)
        assertTrue(runCatching { prepared("\u0001".repeat(8192)) }.isFailure)
        assertTrue(runCatching { PreparedPhoneReply.prepare("r", team, "origin", peer, phone,
            null, "surface", false, "hello", time) }.isFailure)
        assertTrue(runCatching { PreparedPhoneReply.prepare("r", team.copy(userId = "other"), "origin", peer, phone,
            "workspace", "surface", false, "hello", time) }.isFailure)
        assertTrue(runCatching { PreparedPhoneReply.prepare("r", team, "origin", peer,
            PhonePushIdentity("other-phone", phone.keyID, phone.privateKey), "workspace", "surface", false, "hello", time) }.isFailure)
        assertFalse(prepared().isFresh(time - 1)); assertFalse(prepared().isFresh(time + 120_000))
    }

    @Test fun transientRetryReusesIdenticalCiphertextAndReportsInboxAcceptance() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setResponseCode(202))
            var now = time; val reply = prepared()
            PhoneReplyRelay(server.url("/prefix/"), { "test-token" }, { true }, now = { now }).use { relay ->
                assertEquals(PhoneReplyRelayResult.Retry(time + 5000), relay.send(reply))
                now += 5000
                assertEquals(PhoneReplyRelayResult.Retry(now + 5000), relay.send(reply))
                now += 5000
                assertEquals(PhoneReplyRelayResult.Accepted, relay.send(reply))
                val first = server.takeRequest(2, TimeUnit.SECONDS)!!; val second = server.takeRequest(2, TimeUnit.SECONDS)!!
                val third = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/prefix/v1/replies/e2e", first.path); assertEquals("POST", first.method)
                assertEquals("Bearer test-token", first.getHeader("Authorization"))
                assertEquals(reply.body, first.body.readUtf8()); assertEquals(reply.body, second.body.readUtf8())
                assertEquals(reply.body, third.body.readUtf8()); assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun rateLimitGateStopsEveryReplyUntilServerDeadlineWithoutRefreshingCiphertext() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "15"))
            server.enqueue(MockResponse().setResponseCode(200))
            var now = time
            PhoneReplyRelay(server.url("/"), { "token" }, { true }, now = { now }).use { relay ->
                val first = prepared()
                assertEquals(PhoneReplyRelayResult.Retry(time + 15_000), relay.send(first))
                assertEquals(PhoneReplyRelayResult.Retry(time + 15_000), relay.send(prepared(replyID = "another")))
                assertEquals(1, server.requestCount)
                now += 15_000
                assertEquals(PhoneReplyRelayResult.Accepted, relay.send(first)); assertEquals(2, server.requestCount)
            }
        }
        assertEquals(time + 60_000, PhoneReplyRelay.retryDeadline("garbage", time))
        assertEquals(time + 10_000, PhoneReplyRelay.retryDeadline(
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(time + 10_000).atZone(java.time.ZoneOffset.UTC)), time))
        assertEquals(Long.MAX_VALUE - (Long.MAX_VALUE - time) % 1000,
            PhoneReplyRelay.retryDeadline(Long.MAX_VALUE.toString(), time))
    }

    @Test fun revokedBeforeOrDuringTokenRequestAndExpiredReplySendNothing() = runBlocking {
        MockWebServer().use { server ->
            PhoneReplyRelay(server.url("/"), { error("must not request token") }, { false }, now = { time }).use {
                assertEquals(PhoneReplyRelayResult.Retired, it.send(prepared()))
            }
            var allowed = true
            PhoneReplyRelay(server.url("/"), { allowed = false; "token" }, { allowed }, now = { time }).use {
                assertEquals(PhoneReplyRelayResult.Retired, it.send(prepared()))
            }
            PhoneReplyRelay(server.url("/"), { error("expired") }, { true }, now = { time + 120_000 }).use {
                assertEquals(PhoneReplyRelayResult.Expired, it.send(prepared()))
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun lateSuccessCannotAcknowledgeRetiredOwnerAndCloseCancelsPendingHttp() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(200, TimeUnit.MILLISECONDS))
            val allowed = AtomicBoolean(true)
            PhoneReplyRelay(server.url("/"), { "token" }, { allowed.get() }, now = { time }).use { relay ->
                val sent = async(Dispatchers.IO) { relay.send(prepared()) }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); allowed.set(false)
                assertEquals(PhoneReplyRelayResult.Retired, withTimeout(3000) { sent.await() })
            }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val relay = PhoneReplyRelay(server.url("/"), { "token" }, { true }, now = { time })
            val sent = async(Dispatchers.IO) { relay.send(prepared()) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); relay.close()
            assertEquals(PhoneReplyRelayResult.Retired, withTimeout(3000) { sent.await() })
        }
    }

    @Test fun redirectsDoNotForwardCredentialsAndConflictIsNotRetriedOrReencrypted() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { foreign ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", foreign.url("/capture")))
            server.enqueue(MockResponse().setResponseCode(409))
            PhoneReplyRelay(server.url("/"), { "token" }, { true }, now = { time }).use { relay ->
                val reply = prepared()
                assertEquals(PhoneReplyRelayResult.Rejected(307), relay.send(reply))
                assertEquals(PhoneReplyRelayResult.Rejected(409), relay.send(reply))
                assertEquals(0, foreign.requestCount); assertEquals(2, server.requestCount)
            }
        } }
        assertTrue(runCatching { PhoneReplyRelay("http://example.com/".toHttpUrl(), { "token" }, { true }) }.isFailure)
        assertTrue(runCatching { PhoneReplyRelay("https://user:secret@example.com/".toHttpUrl(), { "token" }, { true }) }.isFailure)
    }

    @Test fun coroutineCancellationStopsItsHttpAttempt() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            PhoneReplyRelay(server.url("/"), { "token" }, { true }, now = { time }).use { relay ->
                val sent = launch(Dispatchers.IO) { relay.send(prepared()) }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); sent.cancelAndJoin(); assertTrue(sent.isCancelled)
                assertEquals(1, server.requestCount)
            }
        }
    }
}
