package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMacCompatibilityGateTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private fun host(version: String? = "0.64.25", tag: String = "default", device: String = "mac") =
        JSONObject().put("mac_device_id", device).put("mac_instance_tag", tag).put("mac_app_version", version)
    private fun stricter() = checkNotNull(NativeMacCompatibilityPolicy.decode(
        """{"entries":[{"minIOSVersion":"1.0.6","stableMinVersion":"0.65.0"}]}"""))

    @Test fun outdatedAdmissionClosesWireBeforePoolCanPublishAndRecordsGuidance() = runBlocking<Unit> {
        val gate = NativeMacCompatibilityGate({ it == team })
        val transport = PoolTestTransport()
        MobileRpcConnections().use { pool ->
            val result = runCatching { pool.acquire("mac", { true }, validate = {
                gate.admit(team, it, host("0.64.24"))
            }) { MobileRpcClient(transport, { "fixture" }) } }
            assertTrue(result.exceptionOrNull() is MacUpdateRequired)
            assertNull(pool.borrowIfConnected("mac", { true }))
            assertEquals(1, transport.closes.get())
            assertEquals("0.64.25", gate.warnings.value.values.single().required)
        }
    }
    @Test fun stricterPolicyRetiresAllBorrowersOnlyForAffectedMacAndBuild() = runBlocking<Unit> {
        val gate = NativeMacCompatibilityGate({ it == team })
        MobileRpcConnections().use { pool ->
            suspend fun connect(key: String, version: String, tag: String = "default") = pool.acquire(key, { true },
                validate = { gate.admit(team, it, host(version, tag)) }) { MobileRpcClient(PoolTestTransport(), { "fixture" }) }
            val ui = connect("stable", "0.64.25")
            val service = connect("stable", "0.64.25")
            val nightly = connect("nightly", "0.64.25-nightly.3522337919701", "nightly")
            val newer = connect("newer", "0.65.0")
            gate.replace(stricter())
            assertTrue(withTimeout(1000) { ui.disconnected.first() } is MacUpdateRequired)
            assertTrue(withTimeout(1000) { service.disconnected.first() } is MacUpdateRequired)
            assertTrue(ui.isClosed); assertTrue(service.isClosed)
            assertFalse(nightly.isClosed); assertFalse(newer.isClosed)
            assertNull(pool.borrowIfConnected("stable", { true }))
            ui.close(); service.close(); nightly.close(); newer.close()
        }
    }
    @Test fun emptyRefreshClearsWarningsAndAllowsRetryButDoesNotReviveRetiredWire() = runBlocking<Unit> {
        val gate = NativeMacCompatibilityGate({ it == team })
        val client = MobileRpcClient(PoolTestTransport(), { "fixture" }); client.connect()
        assertTrue(runCatching { gate.admit(team, client, host("0.1")) }.isFailure)
        gate.replace(NativeMacCompatibilityPolicy(emptyList()))
        assertTrue(gate.warnings.value.isEmpty()); assertTrue(client.isClosed)
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { fresh ->
            fresh.connect(); gate.admit(team, fresh, host(null)); assertFalse(fresh.isClosed)
        }
    }
    @Test fun staleOwnerCannotAdmitAndWarningsNeverCrossLoginIncarnations() = runBlocking<Unit> {
        var owner = team
        val gate = NativeMacCompatibilityGate({ it == owner })
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect()
            assertTrue(runCatching { gate.admit(team, client, host("0.1")) }.isFailure)
        }
        assertEquals(1, gate.warnings.value.size)
        owner = team.copy(login = "new-login", generation = 2)
        gate.reconcile(); assertTrue(gate.warnings.value.isEmpty())
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect()
            assertTrue(runCatching { gate.admit(team, client, host()) }.isFailure)
            assertTrue(gate.warnings.value.isEmpty())
        }
    }
    @Test fun latestPolicyWinsWhenHandshakeWasAlreadyInProgress() = runBlocking<Unit> {
        val gate = NativeMacCompatibilityGate({ it == team })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        MobileRpcConnections().use { pool ->
            val pending = async { runCatching { pool.acquire("mac", { true }, validate = {
                entered.complete(Unit); release.await(); gate.admit(team, it, host())
            }) { MobileRpcClient(PoolTestTransport(), { "fixture" }) } } }
            withTimeout(1000) { entered.await() }
            gate.replace(stricter()); release.complete(Unit)
            assertTrue(withTimeout(1000) { pending.await() }.exceptionOrNull() is MacUpdateRequired)
            assertNull(pool.borrowIfConnected("mac", { true }))
        }
    }
    @Test fun upgradedHostClearsWarningAndIdentityIsRequired() = runBlocking<Unit> {
        val gate = NativeMacCompatibilityGate({ it == team })
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect(); runCatching { gate.admit(team, client, host("0.1")) }
        }
        MobileRpcClient(PoolTestTransport(), { "fixture" }).use { client ->
            client.connect(); gate.admit(team, client, host()); assertTrue(gate.warnings.value.isEmpty())
            assertTrue(runCatching { gate.admit(team, client, JSONObject()) }.isFailure)
        }
    }
}
