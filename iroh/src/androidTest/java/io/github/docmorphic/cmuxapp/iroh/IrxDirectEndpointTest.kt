package io.github.docmorphic.cmuxapp.iroh

import androidx.test.platform.app.InstrumentationRegistry
import computer.iroh.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.security.SecureRandom

/** Exercises the production endpoint wrapper and admission with real local QUIC sockets. */
class IrxDirectEndpointTest {
    @Before fun initialize() = IrohRuntime.initialize(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun directBindsWithoutRelayReadinessAndCredentialRotationCannotEnableRelays() = runBlocking<Unit> {
        withKey { key ->
            val runtime = withTimeout(5000) {
                IrxEndpointRuntime.bind(key, emptyList(), pathMode = IrxEndpointPathMode.DIRECT_ONLY)
            }
            try {
                assertEquals(IrxEndpointPathMode.DIRECT_ONLY, runtime.pathMode)
                assertEquals(IrxEndpointStatus(true, null), runtime.status())
                runtime.updateCredentials(listOf(IrxRelayCredential("https://must-not-contact.invalid/", "fixture", Long.MAX_VALUE)))
                assertEquals(IrxEndpointStatus(true, null), runtime.status())
                runtime.updateCredentials(emptyList())
                assertEquals(IrxEndpointStatus(true, null), runtime.status())
            } finally { stop(runtime) }
            assertEquals(IrxEndpointStatus(false, null), runtime.status())
        }
    }

    @Test fun automaticModeStillRequiresUsableRelayCredentials() = runBlocking<Unit> {
        withKey { key ->
            assertTrue(runCatching { IrxEndpointRuntime.bind(key, emptyList()) }.exceptionOrNull() is IOException)
            val expired = IrxRelayCredential("https://must-not-contact.invalid/", "fixture", 1)
            assertTrue(runCatching { IrxEndpointRuntime.bind(key, listOf(expired), now = { 2 }) }.exceptionOrNull() is IOException)
        }
    }

    @Test fun directIgnoresRelayHintAndAdmitsAuthenticatedPeerWithBidirectionalData() = runBlocking<Unit> {
        fixture { phone, key, host, peer, addresses -> coroutineScope {
            val done = CompletableDeferred<Unit>()
            val server = async {
                accept(host) { connection ->
                    connection.remoteId().use { id -> assertEquals(key.endpointId, id.toBytes().hex()) }
                    IrxDuplexLane(connection.acceptBi()).use { lane ->
                        admit(lane)
                        assertEquals("ping", raw(lane, 4))
                        lane.write("pong".toByteArray())
                        done.await()
                    }
                }
            }
            try {
                phone.dial(peer, "https://must-not-contact.invalid/", { true }, addresses).use { session ->
                    assertEquals("direct-fixture", session.admission.session)
                    session.control.write("ping".toByteArray())
                    assertEquals("pong", raw(session.control, 4))
                    assertEquals(IrxConnectionDiagnostics.Route.PRIVATE_NETWORK, session.diagnostics().route)
                    assertNull(phone.status().homeRelayUrl)
                    phone.updateCredentials(listOf(IrxRelayCredential("https://must-not-contact.invalid/", "fixture", Long.MAX_VALUE)))
                    assertFalse(session.connectionIsClosed())
                    assertNull(phone.status().homeRelayUrl)
                }
            } finally { done.complete(Unit) }
            server.await()
        } }
    }

    @Test fun directRefusesMissingExcessiveOrMalformedCandidatesAndRetiredAuthority() = runBlocking<Unit> {
        fixture { phone, _, _, peer, addresses ->
            assertTrue(runCatching { phone.dial(peer, "https://must-not-contact.invalid/", { true }) }
                .exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { phone.dial(peer, null, { true }, List(17) { addresses.single() }) }
                .exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { phone.dial(peer, null, { true }, listOf("not-an-address")) }.isFailure)
            assertTrue(runCatching { phone.dial(peer, null, { false }, addresses) }
                .exceptionOrNull() is CancellationException)
            assertEquals(IrxEndpointStatus(true, null), phone.status())
        }
    }

    @Test fun authorityLostDuringAdmissionClosesCandidateBeforeReturningIt() = runBlocking<Unit> {
        fixture { phone, _, host, peer, addresses -> coroutineScope {
            var permitted = true
            val server = async {
                accept(host) { connection ->
                    IrxDuplexLane(connection.acceptBi()).use { lane ->
                        assertEquals("control", lane.readFrame()!!.getString("lane")); lane.readFrame()
                        permitted = false
                        lane.writeFrame(admission())
                        assertTrue(connection.closed().contains("irx:user-requested"))
                    }
                }
            }
            assertTrue(runCatching { phone.dial(peer, null, { permitted }, addresses) }.exceptionOrNull() is CancellationException)
            server.await()
            assertEquals(IrxEndpointStatus(true, null), phone.status())
        } }
    }

    @Test fun endpointClosureRetiresSessionsAndPreventsNewDialsOrCredentialChanges() = runBlocking<Unit> {
        fixture { phone, _, host, peer, addresses -> coroutineScope {
            val server = async {
                accept(host) { connection ->
                    IrxDuplexLane(connection.acceptBi()).use { lane -> admit(lane); connection.closed() }
                }
            }
            val session = phone.dial(peer, null, { true }, addresses)
            phone.close(); phone.awaitClosed()
            assertTrue(session.connectionIsClosed())
            assertEquals(IrxEndpointStatus(false, null), phone.status())
            assertTrue(runCatching { phone.dial(peer, null, { true }, addresses) }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { phone.updateCredentials(emptyList()) }.exceptionOrNull() is IllegalStateException)
            server.await()
        } }
    }

    @Test fun cancelledAdmissionClosesNativeCandidateAndEndpointCanDialAgain() = runBlocking<Unit> {
        fixture { phone, _, host, peer, addresses -> coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val server = async {
                accept(host) { connection ->
                    IrxDuplexLane(connection.acceptBi()).use { lane ->
                        lane.readFrame(); lane.readFrame(); entered.complete(Unit)
                        connection.closed()
                    }
                }
                accept(host) { connection ->
                    IrxDuplexLane(connection.acceptBi()).use { lane -> admit(lane); connection.closed() }
                }
            }
            val first = async { phone.dial(peer, null, { true }, addresses) }
            entered.await(); first.cancelAndJoin()
            assertTrue(first.isCancelled)
            phone.dial(peer, null, { true }, addresses).use { assertEquals("direct-fixture", it.admission.session) }
            server.await()
        } }
    }

    private suspend fun withKey(block: suspend (IrohInstallationKey) -> Unit) {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val key = try { IrohInstallationKey(IrohAccountScope("test", "fixture", "team", "user", "fixture.app", "debug"),
            "fixture-device", SecretKey.fromBytes(seed)) } finally { seed.fill(0) }
        key.use { block(it) }
    }

    private suspend fun fixture(block: suspend (IrxEndpointRuntime, IrohInstallationKey, Endpoint, String, List<String>) -> Unit) =
        withTimeout(15_000) { withKey { key ->
            val options = EndpointOptions(preset = presetMinimal(), bindAddr = "127.0.0.1:0",
                alpns = listOf(IrxWire.ALPN.toByteArray()), relayMode = RelayMode.disabled(), portMappingEnabled = false,
                deferNatTraversalUntilAuthorized = true, initialMaxConcurrentBiStreams = 1uL, initialMaxConcurrentUniStreams = 0uL)
            val host = Endpoint.bind(options)
            try {
                val phone = IrxEndpointRuntime.bind(key, emptyList(), pathMode = IrxEndpointPathMode.DIRECT_ONLY)
                try { block(phone, key, host, host.id().use { it.toBytes().hex() }, host.boundSockets()) }
                finally { stop(phone) }
            } finally {
                try { withContext(NonCancellable) { withTimeout(5000) { host.shutdown() } } }
                finally { host.close(); options.destroy() }
            }
        } }

    private suspend fun accept(host: Endpoint, action: suspend (Connection) -> Unit) {
        checkNotNull(host.acceptNext()).use { incoming -> incoming.accept().use { accepting ->
            accepting.connect().use { connection -> action(connection) }
        } }
    }
    private fun admission() = JSONObject().put("v", 1).put("session", "direct-fixture")
        .put("keepaliveIntervalMs", 5000).put("keepaliveDeadlineMs", 2000)
    private suspend fun admit(lane: IrxDuplexLane) {
        assertEquals("control", lane.readFrame()!!.getString("lane"))
        assertEquals(IrxWire.ALPN, lane.readFrame()!!.getString("proto"))
        lane.writeFrame(admission())
    }
    private suspend fun raw(lane: IrxDuplexLane, size: Int): String {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < size) out.write(lane.read(size - out.size()).also { check(it.isNotEmpty()) })
        return out.toString("UTF-8")
    }
    private suspend fun stop(runtime: IrxEndpointRuntime) {
        runtime.close()
        withContext(NonCancellable) { withTimeout(5000) { runtime.awaitClosed() } }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
