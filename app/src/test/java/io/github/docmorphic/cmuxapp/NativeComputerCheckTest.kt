package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class NativeComputerCheckTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val mac = IrohV2Computer("record", "ab".repeat(32), "mac", "default", "Fixture Mac", emptyList())
    private val target = NativeComputerTarget.from(mac)
    private class Wire(val mac: IrohV2Computer) : MobileRpcTransport {
        val input = Channel<ByteArray>(8)
        val methods = CopyOnWriteArrayList<String>()
        val closes = AtomicInteger()
        var hostId = mac.deviceId
        var stall = false
        override suspend fun connect() { }
        override suspend fun read() = input.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(MobileFrameDecoder().feed(bytes).single().decodeToString())
            val method = request.getString("method"); methods += method
            if (stall) return
            val result = JSONObject().put("mac_device_id", hostId).put("mac_instance_tag", mac.buildTag)
            input.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", true)
                .put("result", result).toString().toByteArray()))
        }
        override fun close() { closes.incrementAndGet(); input.close() }
    }
    private inner class Backend : IrohAccountBackend {
        override val state = MutableStateFlow(IrohV2ControlState(ready = true, computers = listOf(mac), permissionExpiresAt = 2000))
        var refreshAction: suspend () -> Unit = { }
        val refreshes = AtomicInteger()
        val wires = CopyOnWriteArrayList<Wire>()
        var configure: (Wire) -> Unit = { }
        override suspend fun start() { }
        override suspend fun refresh() { refreshes.incrementAndGet(); refreshAction() }
        override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport {
            assertTrue(permits())
            return Wire(mac).also { configure(it); wires += it }
        }
        override fun close() { }
    }
    private suspend fun fixture(block: suspend (NativeIrohRuntime, Backend, MutableStateFlow<NativeAccountTeamsState>) -> Unit) {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            withTimeout(2000) { runtime.state.first { it.ready } }
            block(runtime, backend, teams)
        }
    }

    @Test fun checksUnopenedMacAndReleasesItsTemporaryLease() = runBlocking { fixture { runtime, backend, _ ->
        val report = runtime.checkComputer(team, target)
        assertTrue(report.identity); assertTrue(report.accountAccess); assertNull(report.failure)
        assertEquals(1, backend.refreshes.get())
        assertEquals(listOf("mobile.host.status", "mobile.workspace.list"), backend.wires.single().methods)
        assertEquals(1, backend.wires.single().closes.get())
    } }
    @Test fun powerBorrowsOnlyExactLiveMacAndRequiresCurrentTeamAndDirectory() = runBlocking { fixture { runtime, backend, _ ->
        assertNull(runtime.powerSession(team, target)); assertTrue(backend.wires.isEmpty())
        val pairing = PairingCodeParser.parse(PairingCodeParser.computer(mac, team)).getOrThrow() as PairingCode.Iroh
        runtime.connect(pairing).use { active ->
            assertNull(runtime.powerSession(team.copy(teamId = "other"), target))
            assertNull(runtime.powerSession(team, target.copy(buildTag = "other")))
            val session = requireNotNull(runtime.powerSession(team, target))
            val job = launch { session.run() }
            withTimeout(2000) { session.state.first { it.supported == false && !it.busy } }
            job.cancelAndJoin(); assertFalse(active.isClosed)
            assertEquals(1, backend.wires.size)
            backend.state.value = backend.state.value.copy(permissionExpiresAt = 999)
            assertNull(runtime.powerSession(team, target))
        }
    } }
    @Test fun localAppearanceWorksOfflineButRetiredAccountPageCannotEdit() = runBlocking { fixture { runtime, backend, teams ->
        backend.state.value = backend.state.value.copy(permissionExpiresAt = 999, computers = emptyList())
        assertTrue(runtime.permitsAppearance(team))
        assertFalse(runtime.permitsAppearance(team.copy(teamId = "other")))
        teams.value = NativeAccountTeamsState()
        assertFalse(runtime.permitsAppearance(team))
        assertTrue(backend.wires.isEmpty())
    } }
    @Test fun checkingAlreadyOpenMacDoesNotCloseItsOtherLease() = runBlocking { fixture { runtime, backend, _ ->
        val pairing = PairingCodeParser.parse(PairingCodeParser.computer(mac, team)).getOrThrow() as PairingCode.Iroh
        runtime.connect(pairing).use { active ->
            assertTrue(runtime.checkComputer(team, target).accountAccess)
            assertEquals(1, backend.wires.size)
            assertEquals(0, backend.wires.single().closes.get())
            assertFalse(active.isClosed)
        }
        assertEquals(1, backend.wires.single().closes.get())
    } }
    @Test fun discoveryRemovalOrDifferentBuildCannotDialStaleTarget() = runBlocking { fixture { runtime, backend, _ ->
        backend.refreshAction = { backend.state.value = backend.state.value.copy(computers = listOf(mac.copy(buildTag = "debug"))) }
        assertEquals(NativeConnectionReport.Failure.DISCOVERY, runtime.checkComputer(team, target).failure)
        assertTrue(backend.wires.isEmpty())
    } }
    @Test fun refreshedEndpointUsedOnlyForMatchingDeviceAndBuild() = runBlocking { fixture { runtime, backend, _ ->
        val rotated = mac.copy(endpointId = "cd".repeat(32), recordId = "rotated")
        backend.refreshAction = { backend.state.value = backend.state.value.copy(computers = listOf(rotated)) }
        assertTrue(runtime.checkComputer(team, target).accountAccess)
        assertEquals(rotated, backend.wires.single().mac)
    } }
    @Test fun wrongTeamAndRetiredAccountCannotCreateConnections() = runBlocking { fixture { runtime, backend, teams ->
        assertEquals(NativeConnectionReport.Failure.ACCOUNT, runtime.checkComputer(team.copy(teamId = "other"), target).failure)
        assertEquals(0, backend.refreshes.get())
        backend.refreshAction = { teams.value = NativeAccountTeamsState() }
        assertEquals(NativeConnectionReport.Failure.ACCOUNT, runtime.checkComputer(team, target).failure)
        assertTrue(backend.wires.isEmpty())
    } }
    @Test fun deadlineCoversDiscoveryAndCallerCancellationPropagates() = runBlocking { fixture { runtime, backend, _ ->
        backend.refreshAction = { awaitCancellation() }
        assertEquals(NativeConnectionReport.Failure.TIMEOUT, runtime.checkComputer(team, target, 50).failure)
        val job = async { runtime.checkComputer(team, target) }
        withTimeout(2000) { while (backend.refreshes.get() != 2) delay(1) }
        job.cancelAndJoin(); assertTrue(job.isCancelled)
        assertTrue(backend.wires.isEmpty())
    } }
    @Test fun identityMismatchClosesTemporaryLeaseBeforeWorkspaceRead() = runBlocking { fixture { runtime, backend, _ ->
        backend.configure = { it.hostId = "wrong-mac" }
        assertEquals(NativeConnectionReport.Failure.IDENTITY, runtime.checkComputer(team, target).failure)
        assertEquals(listOf("mobile.host.status"), backend.wires.single().methods)
        assertEquals(1, backend.wires.single().closes.get())
    } }
    @Test fun rpcTimeoutClosesCandidateWithoutTerminalMutation() = runBlocking { fixture { runtime, backend, _ ->
        backend.configure = { it.stall = true }
        assertEquals(NativeConnectionReport.Failure.TIMEOUT, runtime.checkComputer(team, target, 100).failure)
        assertEquals(listOf("mobile.host.status"), backend.wires.single().methods)
        assertEquals(1, backend.wires.single().closes.get())
    } }
    @Test fun savedDetailsRequireExactScopedIdentityAndNativeRoute() {
        val code = PairingCodeParser.computer(mac, team)
        val saved = NativeCredentialStore.PairedMac(code, mac.deviceId, mac.name, mac.buildTag)
        assertEquals(target, NativeComputerTarget.from(saved, team))
        assertNull(NativeComputerTarget.from(saved, team.copy(teamId = "other")))
        assertNull(NativeComputerTarget.from(saved.copy(deviceId = "other"), team))
        assertNull(NativeComputerTarget.from(saved.copy(instanceTag = "debug"), team))
        assertNull(NativeComputerTarget.from(saved.copy(code = "cmux-ios://attach?v=2&r=100.64.0.1:123"), team))
        val oldCode = "cmux-ios://attach?v=3&i=${mac.endpointId}"
        assertEquals(target, NativeComputerTarget.from(saved.copy(code = oldCode), team))
        assertNull(NativeComputerTarget.from(saved.copy(code = oldCode, instanceTag = null), team))
    }
}
