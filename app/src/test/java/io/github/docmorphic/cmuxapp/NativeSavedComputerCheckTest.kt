package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSavedComputerCheckTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
        "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user", "mac", "Mac", "default"), team)
    private val target = NativeComputerTarget("mac", "default", "Mac")
    private class Wire : MobileRpcTransport {
        val input = Channel<ByteArray>(8)
        var closed = false
        var host = "mac"
        var hook: (String) -> Unit = {}
        override suspend fun connect() {}
        override suspend fun read() = input.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(MobileFrameDecoder().feed(bytes).single().decodeToString())
            val method = request.getString("method"); hook(method)
            val result = if (method == "mobile.host.status") JSONObject().put("mac_device_id", host).put("mac_instance_tag", "default") else JSONObject()
            input.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", true).put("result", result).toString().toByteArray()))
        }
        override fun close() { closed = true; input.close() }
    }
    @Test fun rawDetailsRequiresPersistedScopeAndBuildRatherThanAnAddressHint() {
        assertEquals(target, NativeComputerTarget.from(mac, team))
        for (row in listOf(mac.copy(accountUserId = null, accountTeamId = null), mac.copy(stableOrigin = null),
            mac.copy(instanceTag = null), mac.copy(accountTeamId = "other"), mac.copy(code = mac.code.replace("ub=user", "ub=other"))))
            assertNull(NativeComputerTarget.from(row, team))
        assertNull(NativeComputerTarget.from(mac, team.copy(userId = "other")))
        assertEquals(listOf(mac), NativeComputerForgetLocal.capture(listOf(mac, mac.copy(instanceTag = "nightly")), team, target))
    }
    @Test fun checksThroughSavedConnectorWithoutDirectoryAndClosesItsOwnLease() = runBlocking {
        val wire = Wire(); var connects = 0
        val check = NativeSavedComputerCheck(team, target, { true }, { listOf(mac) }, { true }) {
            assertEquals(mac, it); connects++; MobileRpcClient(wire, { "fixture" }).also { it.connect() }
        }
        assertTrue(check.available())
        val result = check.run()
        assertTrue(result.identity); assertTrue(result.accountAccess); assertNull(result.failure)
        assertEquals(1, connects); assertTrue(wire.closed)
    }
    @Test fun deniedAmbiguousAndOtherBuildRowsNeverDial() = runBlocking {
        for (rows in listOf(emptyList(), listOf(mac, mac), listOf(mac.copy(instanceTag = "nightly")), listOf(mac.copy(accountTeamId = "other")))) {
            val check = NativeSavedComputerCheck(team, target, { true }, { rows }, { true }) { error("must not dial") }
            assertFalse(check.available()); assertNotNull(check.run().failure)
        }
        val denied = NativeSavedComputerCheck(team, target, { true }, { listOf(mac) }, { false }) { error("must not dial") }
        assertFalse(denied.available()); assertNotNull(denied.run().failure)
    }
    @Test fun accountRetirementDuringConnectClosesWithoutReportingSuccess() = runBlocking {
        val wire = Wire(); var current = true
        val check = NativeSavedComputerCheck(team, target, { current }, { listOf(mac) }, { true }) {
            MobileRpcClient(wire, { "fixture" }).also { it.connect(); current = false }
        }
        assertEquals(NativeConnectionReport.Failure.ACCOUNT, check.run().failure)
        assertTrue(wire.closed)
    }
    @Test fun routeRemovalDuringReportCannotPublishStaleSuccess() = runBlocking {
        val wire = Wire(); var rows = listOf(mac)
        wire.hook = { if (it == "mobile.workspace.list") rows = emptyList() }
        val check = NativeSavedComputerCheck(team, target, { true }, { rows }, { true }) {
            MobileRpcClient(wire, { "fixture" }).also { it.connect() }
        }
        val result = check.run(); assertNotNull(result.failure); assertFalse(result.accountAccess); assertTrue(wire.closed)
    }
    @Test fun mismatchedHostIsReportedAndItsLeaseIsClosed() = runBlocking {
        val wire = Wire(); wire.host = "other"
        val check = NativeSavedComputerCheck(team, target, { true }, { listOf(mac) }, { true }) {
            MobileRpcClient(wire, { "fixture" }).also { it.connect() }
        }
        assertEquals(NativeConnectionReport.Failure.IDENTITY, check.run().failure); assertTrue(wire.closed)
    }
}
