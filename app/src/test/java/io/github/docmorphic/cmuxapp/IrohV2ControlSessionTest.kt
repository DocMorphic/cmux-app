package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class IrohV2ControlSessionTest {
    private class Fixture(val httpOnly: Boolean = false, val enroll: Boolean = false) : AutoCloseable {
        val server = MockWebServer()
        val time = AtomicLong(1000)
        val current = AtomicBoolean(true)
        val peer = CompletableDeferred<WebSocket>()
        val seen = CopyOnWriteArrayList<JSONObject>()
        val setups = CopyOnWriteArrayList<JSONObject>()
        val auth = CopyOnWriteArrayList<String>()
        val blocked = Channel<JSONObject>(Channel.UNLIMITED)
        @Volatile var intercept: ((WebSocket, JSONObject) -> Boolean)? = null
        @Volatile var page: (JSONObject) -> JSONObject = { directory() }
        val identity = JSONObject().put("environment", "test").put("projectId", "project").put("teamId", "team")
            .put("userId", "user").put("deviceId", "phone").put("appNamespace", "io.github.docmorphic.cmuxapp.debug").put("buildTag", "test")
        val device = IrohV2AndroidDevice.descriptor(identity, "a".repeat(64), "test", "Pixel 6a", IrohMobileWireProfile.IOS_COMPATIBILITY)
        val signed = IrohV2SignedRequests(device, { ByteArray(64) }, { time.get() })
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    auth += request.getHeader("Authorization").orEmpty()
                    if (request.path == "/v2/control/socket") {
                        val setup = JSONObject(String(Base64.getUrlDecoder().decode(request.getHeader("x-cmux-v2-setup"))))
                        setups += setup
                        if (httpOnly) return MockResponse().setResponseCode(426)
                        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                peer.complete(webSocket)
                                webSocket.send(ready(setup).toString())
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val body = JSONObject(text)
                                seen += body
                                if (intercept?.invoke(webSocket, body) == true) return
                                webSocket.send(reply(body).toString())
                            }
                        })
                    }
                    val body = JSONObject(request.body.readUtf8())
                    if (request.path == "/v2/control/session") {
                        setups += body
                        return MockResponse().setBody(ready(body).toString())
                    }
                    seen += body
                    return MockResponse().setBody(reply(body).toString())
                }
            }
        }
        fun record(id: String = "phone-record", descriptor: JSONObject = device) = JSONObject()
            .put("deviceRecordId", id).put("revision", 1).put("revoked", false).put("descriptor", descriptor)
        fun mac(id: String = "mac-record", key: String = "b"): JSONObject {
            val descriptor = JSONObject(device.toString()).put("endpointId", key.repeat(64))
            descriptor.getJSONObject("identity").put("deviceId", id).put("appNamespace", "dev.cmux.app")
            descriptor.getJSONObject("metadata").put("platform", "mac").put("displayName", id)
                .put("relayURLs", JSONArray().put("https://relay.example.com/"))
            return record(id, descriptor)
        }
        fun directory(revision: Long = 1, devices: List<JSONObject> = listOf(mac()), next: String? = null) = JSONObject()
            .put("teamId", "team").put("revision", revision).put("devices", JSONArray(devices))
            .put("issuedAt", 1000).put("permissionExpiresAt", 1600).put("relayURLs", JSONArray())
            .put("nextCursor", next ?: JSONObject.NULL)
        fun ready(setup: JSONObject): JSONObject {
            val value = JSONObject().put("schemaId", "session.ready.v1").put("requestId", setup.getString("requestId"))
                .put("sessionId", "fixture").put("teamRevision", 1)
                .put("ticket", JSONObject().put("token", "fixture-ticket").put("expiresAt", 1600).put("refreshAfter", 1500))
            if (enroll) value.put("challenge", JSONObject().put("challengeId", "challenge").put("nonce", "A".repeat(43))
                .put("expiresAt", 1100).put("payloadHash", MessageDigest.getInstance("SHA-256")
                    .digest(IrohV2SigningCodec.encode(device)).joinToString("") { "%02x".format(it) }))
            else value.put("device", record())
            return value
        }
        fun reply(body: JSONObject): JSONObject {
            val schema = body.getString("schemaId")
            val result = JSONObject().put("schemaId", IrohV2Wire.requestResponses[schema]).put("requestId", body.getString("requestId"))
            return when (schema) {
                "device.register.v1" -> result.put("device", record())
                "directory.request.v1" -> result.put("directory", page(body))
                "relay.request.v1" -> result.put("credentials", JSONArray().put(JSONObject().put("relayURL", "https://relay.example.com/")
                    .put("token", "fixture-relay-secret").put("expiresAt", 1600).put("refreshAfter", 1500)))
                else -> error("Unexpected fixture operation: $schema")
            }
        }
        fun session(maintain: Boolean = false, token: suspend (Boolean) -> String = { "fixture-access-token" }) = IrohV2ControlSession(
            signed, token, { current.get() }, server.url("/"), now = { time.get() }, maintainAutomatically = maintain)
        suspend fun event(schema: String, revision: Long, id: String? = null) {
            peer.await().send(JSONObject().put("schemaId", schema).put("teamId", "team").put("revision", revision)
                .apply { id?.let { put("deviceRecordId", it) } }.toString())
        }
        override fun close() { server.close() }
    }

    @Test fun enrollsThenPublishesMacDirectoryAndRelayCredentialsTogether() = runBlocking<Unit> {
        Fixture(enroll = true).use { fixture ->
            fixture.page = { fixture.directory().put("relayURLs", JSONArray().put("https://fallback.example.com/")) }
            fixture.session().use { session ->
                val ready = withTimeout(3000) { session.connect() }
                assertTrue(ready.ready)
                assertEquals("websocket", ready.mode)
                assertEquals(listOf("https://fallback.example.com/"), ready.directoryRelays)
                assertEquals(listOf("mac-record"), ready.computers.map { it.recordId })
                assertEquals("fixture-relay-secret", ready.relays.single().token)
                assertFalse(ready.toString().contains("fixture-relay-secret"))
                assertEquals(listOf("device.register.v1", "directory.request.v1", "relay.request.v1"), fixture.seen.map { it.getString("schemaId") })
                assertEquals("Pixel 6a (Android)", fixture.seen.first().getJSONObject("device").getJSONObject("metadata").getString("displayName"))
            }
        }
    }

    @Test fun upgradeFailureUsesFreshHttpProofAndCachedTicket() = runBlocking<Unit> {
        Fixture(httpOnly = true).use { fixture ->
            fixture.session().use { session ->
                assertEquals("http", withTimeout(3000) { session.connect() }.mode)
                assertEquals(2, fixture.setups.size)
                assertNotEquals(fixture.setups[0].getString("requestId"), fixture.setups[1].getString("requestId"))
                assertNotEquals(fixture.setups[0].getJSONObject("proof").getString("nonce"), fixture.setups[1].getJSONObject("proof").getString("nonce"))
                assertEquals(listOf("Bearer fixture-access-token", "Bearer fixture-access-token", "IrohTicket fixture-ticket", "IrohTicket fixture-ticket"), fixture.auth)
            }
        }
    }

    @Test fun authenticationRejectionDoesNotFallBackOrEnroll() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
            }
            fixture.session().use { session ->
                val failure = runCatching { withTimeout(3000) { session.connect() } }.exceptionOrNull()
                assertTrue(failure is IrohV2HttpFailure && failure.status == 401)
                assertEquals(1, fixture.server.requestCount)
                assertFalse(session.state.value.ready)
            }
        }
    }

    @Test fun lostSocketMovesSubsequentReadsToHttpWithoutReenrollment() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.session().use { session ->
                session.connect()
                fixture.peer.await().close(1000, "fixture disconnect")
                withTimeout(3000) { session.state.first { it.mode == "http" } }
                fixture.page = { fixture.directory(2, listOf(fixture.mac("after-recovery"))) }
                val refreshed = withTimeout(3000) { session.refreshDirectory() }
                assertTrue(refreshed.ready)
                assertEquals(listOf("after-recovery"), refreshed.computers.map { it.recordId })
                assertEquals(1, fixture.setups.size)
                assertEquals(0, fixture.seen.count { it.getString("schemaId") == "device.register.v1" })
                assertEquals("IrohTicket fixture-ticket", fixture.auth.last())
            }
        }
    }

    @Test fun ticketRenewalFailureDoesNotPreventRelayAndDirectoryRenewal() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.session(maintain = true).use { session ->
                session.connect()
                fixture.page = { fixture.directory(2).put("permissionExpiresAt", 2200) }
                fixture.intercept = { socket, request ->
                    when (request.getString("schemaId")) {
                        "ticket.request.v1" -> {
                            socket.send(JSONObject().put("schemaId", "error.v1").put("requestId", request.getString("requestId"))
                                .put("code", "internal_error").put("retryable", true).toString())
                            true
                        }
                        "relay.request.v1" -> {
                            val response = fixture.reply(request)
                            response.getJSONArray("credentials").getJSONObject(0).put("expiresAt", 2200).put("refreshAfter", 2100)
                            socket.send(response.toString())
                            true
                        }
                        else -> false
                    }
                }
                fixture.time.set(1501)
                val renewed = withTimeout(4000) { session.state.first { it.directoryRevision == 2L } }
                assertTrue(renewed.ready)
                assertEquals(2200L, renewed.permissionExpiresAt)
                assertEquals(2200L, renewed.relays.single().expiresAt)
                assertEquals(1, fixture.seen.count { it.getString("schemaId") == "ticket.request.v1" })
            }
        }
    }

    @Test fun paginationRestartsInsteadOfMixingRevisions() = runBlocking<Unit> {
        Fixture().use { fixture ->
            var count = 0
            fixture.page = { request ->
                when (++count) {
                    1 -> fixture.directory(1, listOf(fixture.mac("discarded")), "next")
                    2 -> {
                        assertEquals(1L, request.getLong("haveRevision"))
                        fixture.directory(2, listOf(fixture.mac("partial", "c")))
                    }
                    3 -> { assertFalse(request.has("cursor")); fixture.directory(2, listOf(fixture.mac("current")), "next-new") }
                    else -> fixture.directory(2, listOf(fixture.mac("current-two", "c")))
                }
            }
            fixture.session().use { session ->
                val ready = withTimeout(3000) { session.connect() }
                assertEquals(listOf("current", "current-two"), ready.computers.map { it.recordId })
                assertEquals(2L, ready.directoryRevision)
                assertEquals(4, count)
            }
        }
    }

    @Test fun paginationCursorCycleFailsWithoutPublishingPartialComputers() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.page = { request -> fixture.directory(1, if (request.has("cursor")) emptyList() else listOf(fixture.mac()), "cycle") }
            fixture.session().use { session ->
                val failure = runCatching { withTimeout(3000) { session.connect() } }.exceptionOrNull()
                assertTrue(failure is IOException && failure.message.orEmpty().contains("cursor cycle"))
                assertTrue(session.state.value.computers.isEmpty())
                assertFalse(session.state.value.ready)
            }
        }
    }

    @Test fun ownRevocationDuringEnrollmentCannotPublishLateSuccess() = runBlocking<Unit> {
        Fixture(enroll = true).use { fixture ->
            fixture.intercept = { _, request ->
                if (request.getString("schemaId") == "device.register.v1") { fixture.blocked.trySend(request); true } else false
            }
            fixture.session().use { session ->
                val pending = async { runCatching { session.connect() } }
                val request = withTimeout(3000) { fixture.blocked.receive() }
                fixture.event("device.revoked.v1", 2, "phone-record")
                // A directory change after the revoke shares its ordered event stream. The delayed
                // register reply cannot resurrect the record even though its ID was unknown at push time.
                delay(100)
                fixture.peer.await().send(fixture.reply(request).toString())
                assertTrue(withTimeout(3000) { pending.await() }.isFailure)
                assertEquals("device_revoked", session.state.value.failure)
                assertFalse(session.state.value.ready)
                assertTrue(session.state.value.relays.isEmpty())
            }
        }
    }

    @Test fun peerRevocationPrunesImmediatelyAndRefreshesToNewestPushedRevision() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.session().use { session ->
                session.connect()
                fixture.intercept = { _, request ->
                    if (request.getString("schemaId") == "directory.request.v1") { fixture.blocked.trySend(request); true } else false
                }
                fixture.event("device.revoked.v1", 2, "mac-record")
                withTimeout(3000) { session.state.first { it.ready && it.computers.isEmpty() } }
                val first = withTimeout(3000) { fixture.blocked.receive() }
                // Burst advances the target while one directory request is in flight.
                for (revision in 3L..20L) fixture.event("directory.changed.v1", revision)
                delay(100)
                fixture.peer.await().send(fixture.reply(first).put("directory", fixture.directory(2)).toString())
                val latest = withTimeout(3000) { fixture.blocked.receive() }
                fixture.peer.await().send(fixture.reply(latest).put("directory", fixture.directory(20, listOf(fixture.mac("new-mac", "c")))).toString())
                val ready = withTimeout(3000) { session.state.first { it.directoryRevision == 20L } }
                assertEquals(listOf("new-mac"), ready.computers.map { it.recordId })
                assertEquals(3, fixture.seen.count { it.getString("schemaId") == "directory.request.v1" })
            }
        }
    }

    @Test fun expiredAuthorityIsRemovedEvenWhileRefreshRequestIsStalled() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.session().use { session ->
                session.connect()
                fixture.intercept = { _, request -> fixture.blocked.trySend(request); true }
                val pending = async { runCatching { session.refreshDirectory() } }
                withTimeout(3000) { fixture.blocked.receive() }
                fixture.time.set(1601)
                val expired = withTimeout(3000) { session.state.first { it.computers.isEmpty() && it.relays.isEmpty() } }
                assertTrue(expired.ready) // Control is alive, but it offers no expired connection authority.
                session.close()
                assertTrue(withTimeout(3000) { pending.await() }.isFailure)
            }
        }
    }

    @Test fun accountChangeDuringTokenFetchCannotSendSetup() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val fetched = CompletableDeferred<Unit>()
            val token = CompletableDeferred<String>()
            fixture.session { fetched.complete(Unit); token.await() }.use { session ->
                val pending = async { runCatching { session.connect() } }
                withTimeout(3000) { fetched.await() }
                fixture.current.set(false)
                token.complete("old-account-token")
                assertTrue(withTimeout(3000) { pending.await() }.isFailure)
                assertEquals(0, fixture.server.requestCount)
                assertFalse(session.state.value.ready)
            }
        }
    }

    @Test fun accountChangeCancelsOutstandingNetworkAndClearsAuthority() = runBlocking<Unit> {
        Fixture().use { fixture ->
            fixture.session().use { session ->
                session.connect()
                fixture.intercept = { _, request -> fixture.blocked.trySend(request); true }
                val pending = async { runCatching { session.refreshDirectory() } }
                withTimeout(3000) { fixture.blocked.receive() }
                fixture.current.set(false)
                assertTrue(withTimeout(3000) { pending.await() }.isFailure)
                assertFalse(session.state.value.ready)
                assertTrue(session.state.value.computers.isEmpty())
                assertTrue(session.state.value.relays.isEmpty())
            }
        }
    }
}
