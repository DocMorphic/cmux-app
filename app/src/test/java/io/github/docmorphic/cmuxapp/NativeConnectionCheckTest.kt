package io.github.docmorphic.cmuxapp

import computer.iroh.PathSnapshot
import computer.iroh.PathStatsRecord
import io.github.docmorphic.cmuxapp.iroh.IrxConnectionDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeConnectionCheckTest {
    private val mac = NativeCredentialStore.PairedMac("private-pairing", "mac-id", "Private Mac name", "instance")
    private class Wire : MobileRpcTransport {
        val input = Channel<ByteArray>(8)
        val methods = mutableListOf<String>()
        var host = "mac-id"
        var authFailure = false
        var stall = false
        var diagnostics: MobileTransportDiagnostics? = MobileTransportDiagnostics.fromIroh(IrxConnectionDiagnostics(IrxConnectionDiagnostics.Route.RELAY, 42))
        override suspend fun connect() { }
        override suspend fun read() = input.receiveCatching().getOrNull()
        override fun diagnostics() = diagnostics
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(MobileFrameDecoder().feed(bytes).single().decodeToString())
            val method = request.getString("method"); methods += method
            if (stall) return
            val failure = authFailure && method == "mobile.workspace.list"
            val reply = JSONObject().put("id", request.getString("id")).put("ok", !failure)
            if (failure) reply.put("error", JSONObject().put("code", "forbidden").put("message", "SECRET bearer token and 100.90.1.2"))
            else reply.put("result", if (method == "mobile.host.status") JSONObject().put("mac_device_id", host)
                .put("mac_instance_tag", "instance") else JSONObject().put("private_terminal_content", "SECRET"))
            input.send(MobileFrameCodec.encode(reply.toString().toByteArray()))
        }
        override fun close() { input.close() }
    }

    @Test fun verifiesHostAndAuthenticatedReadOnBorrowedConnectionAndExportsOnlySafeFields() = runBlocking<Unit> {
        val wire = Wire()
        MobileRpcClient(wire, { "secret-token" }).use { base ->
            base.connect()
            base.lease {}.use { lease ->
                val result = NativeConnectionCheck.run(lease, mac)
                assertTrue(result.identity); assertTrue(result.accountAccess); assertNull(result.failure)
                assertEquals("Relay", result.route); assertEquals(42L, result.transport?.roundTripMillis)
                assertEquals(listOf("mobile.host.status", "mobile.workspace.list"), wire.methods)
                val report = result.shareText()
                for (secret in listOf("private-pairing", "Private Mac name", "mac-id", "instance", "SECRET", "secret-token"))
                    assertFalse(report, report.contains(secret))
            }
            assertFalse(base.isClosed)
        }
    }

    @Test fun tailscaleReportSeparatesVpnManagedEncryptionFromAuthenticatedMacAccess() = runBlocking<Unit> {
        val wire = Wire().apply { diagnostics = MobileTransportDiagnostics.tailscale() }
        MobileRpcClient(wire, { "fixture-token" }).use { client ->
            client.connect(); val report = NativeConnectionCheck.run(client, mac)
            assertEquals("Tailscale VPN (TCP)", report.route)
            assertEquals("Managed by VPN", report.encryption)
            assertTrue(report.identity); assertTrue(report.accountAccess)
            assertNull(report.transport?.roundTripMillis); assertNotNull(report.responseMillis)
            val text = report.shareText()
            assertFalse(text.contains("Iroh")); assertFalse(text.contains("Transport RTT"))
            for (secret in listOf("private-pairing", "Private Mac name", "mac-id", "instance", "SECRET", "fixture-token"))
                assertFalse(text, text.contains(secret))
        }
    }

    @Test fun transportProjectionRetainsIrohRoutesWithoutClaimingEncryptionForUnavailablePaths() {
        for (route in IrxConnectionDiagnostics.Route.entries) {
            val value = MobileTransportDiagnostics.fromIroh(IrxConnectionDiagnostics(route, 12))
            if (route == IrxConnectionDiagnostics.Route.UNAVAILABLE) {
                assertEquals(MobileTransportDiagnostics.Encryption.UNAVAILABLE, value.encryption)
                assertNull(value.roundTripMillis)
            } else {
                assertEquals(MobileTransportDiagnostics.Encryption.IROH_QUIC, value.encryption)
                assertEquals(12L, value.roundTripMillis)
            }
        }
        assertNull(MobileTransportDiagnostics.fromIroh(IrxConnectionDiagnostics(IrxConnectionDiagnostics.Route.RELAY, -1)).roundTripMillis)
    }

    @Test fun mismatchedMacStopsBeforeAuthenticatedRead() = runBlocking<Unit> {
        val wire = Wire().apply { host = "another-mac" }
        MobileRpcClient(wire, { "token" }).use { client ->
            client.connect(); val result = NativeConnectionCheck.run(client, mac)
            assertEquals(NativeConnectionReport.Failure.IDENTITY, result.failure)
            assertFalse(result.identity); assertFalse(result.accountAccess)
            assertEquals(listOf("mobile.host.status"), wire.methods)
        }
    }

    @Test fun rejectedAccountResponseDoesNotEnterSharedReport() = runBlocking<Unit> {
        val wire = Wire().apply { authFailure = true }
        MobileRpcClient(wire, { "token" }).use { client ->
            client.connect(); val result = NativeConnectionCheck.run(client, mac)
            assertTrue(result.identity); assertFalse(result.accountAccess)
            assertEquals(NativeConnectionReport.Failure.ACCOUNT, result.failure)
            assertFalse(result.shareText().contains("SECRET")); assertFalse(result.shareText().contains("100.90.1.2"))
        }
    }

    @Test fun legacyTransportDoesNotClaimQuicEncryptionOrKnownRoute() = runBlocking<Unit> {
        val wire = Wire().apply { diagnostics = null }
        MobileRpcClient(wire, { "token" }).use { client ->
            client.connect(); val result = NativeConnectionCheck.run(client, mac)
            assertTrue(result.accountAccess); assertEquals("Not Reported", result.encryption)
            assertEquals("Not Reported", result.route)
        }
    }

    @Test fun timeoutIsReportedButCallerCancellationPropagates() = runBlocking<Unit> {
        val wire = Wire().apply { stall = true }
        MobileRpcClient(wire, { "token" }).use { client ->
            client.connect()
            assertEquals(NativeConnectionReport.Failure.TIMEOUT, NativeConnectionCheck.run(client, mac, 50).failure)
            val job = async { NativeConnectionCheck.run(client, mac) }
            yield(); job.cancelAndJoin(); assertTrue(job.isCancelled)
        }
    }

    @Test fun releasedLeaseCannotReadNativeDiagnostics() = runBlocking<Unit> {
        MobileRpcClient(Wire(), { "token" }).use { base ->
            base.connect(); val lease = base.lease {}; lease.close()
            assertTrue(runCatching { lease.transportDiagnostics() }.isFailure)
            assertFalse(base.isClosed)
        }
    }

    private fun path(address: String, relay: Boolean = false, selected: Boolean = true, rtt: ULong = 25uL) =
        PathSnapshot("private-path-id", selected, address, !relay, relay, rtt,
            PathStatsRecord(0uL, 0uL, 0uL, 0uL, 0uL, 0uL, 0uL, 0uL, 0uL, 0u))

    @Test fun selectedPathProjectionClassifiesPrivateRangesAndNeverExportsCoordinates() {
        for (address in listOf("127.0.0.1:42", "10.1.2.3:42", "172.16.0.1:42", "192.168.0.1:42",
            "169.254.1.1:42", "100.64.0.1:42", "100.127.255.255:42", "[::1]:42", "[fe80::1]:42", "[fd7a:115c:a1e0::1]:42")) {
            val result = IrxConnectionDiagnostics.fromPaths(listOf(path(address)))
            assertEquals(address, IrxConnectionDiagnostics.Route.PRIVATE_NETWORK, result.route)
            assertFalse(result.toString().contains(address))
        }
        for (address in listOf("8.8.8.8:42", "100.128.0.1:42", "172.32.0.1:42", "[2001:4860:4860::8888]:42"))
            assertEquals(IrxConnectionDiagnostics.Route.DIRECT, IrxConnectionDiagnostics.fromPaths(listOf(path(address))).route)
        val relay = IrxConnectionDiagnostics.fromPaths(listOf(path("https://secret-relay.example", relay = true)))
        assertEquals(IrxConnectionDiagnostics.Route.RELAY, relay.route); assertEquals(25L, relay.roundTripMillis)
        assertFalse(relay.toString().contains("secret-relay"))
    }

    @Test fun absentAmbiguousOrMalformedSelectedPathNeverClaimsAWorkingRoute() {
        for (paths in listOf(emptyList(), listOf(path("8.8.8.8:42", selected = false)),
            listOf(path("8.8.8.8:42"), path("https://relay.example", relay = true)),
            listOf(path("do-not-resolve.example:42")), listOf(path("999.1.1.1:42")))) {
            val result = IrxConnectionDiagnostics.fromPaths(paths)
            assertEquals(IrxConnectionDiagnostics.Route.UNAVAILABLE, result.route); assertNull(result.roundTripMillis)
        }
        assertNull(IrxConnectionDiagnostics.fromPaths(listOf(path("8.8.8.8:42", rtt = ULong.MAX_VALUE))).roundTripMillis)
    }
}
