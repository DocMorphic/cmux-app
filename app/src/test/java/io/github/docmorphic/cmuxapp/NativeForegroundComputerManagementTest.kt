package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativeForegroundComputerManagementTest {
    private val original = NativeTeamScope("login", "user", "original", 1)
    private val selected = original.copy(teamId = "picker", generation = 2)
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(
        "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user", "device", "Original", "default"), original)
    private class Control(private val current: () -> Boolean) : IrohAccountBackend {
        override val state = MutableStateFlow(IrohV2ControlState())
        var managementStarts = 0
        var normalStarts = 0
        var closed = false
        var gate: CompletableDeferred<Unit>? = null
        var failed = false
        val targets = CopyOnWriteArrayList<NativeComputerTarget>()
        override suspend fun start() { normalStarts++ }
        override suspend fun startManagement() { managementStarts++; gate?.await(); check(current()) }
        override suspend fun refresh() {}
        override suspend fun revokeComputer(target: NativeComputerTarget) {
            check(current()); targets += target; if (failed) error("Fixture lost acknowledgement")
        }
        override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport = error("Management must never dial a Mac")
        override fun close() { closed = true }
    }
    private class Created(val owner: NativeTeamScope, val control: Control)

    @Test fun onlyLiveRootCanBeginButConfirmedTargetCanContinueAfterItsRootStops() {
        var live = true; var member = true
        val management = NativeForegroundComputerManagement(original, mac, { live }, { member })
        val permit = management.beginRevoke()
        live = false
        assertFalse(management.available()); assertTrue(runCatching { management.beginRevoke() }.isFailure)
        assertTrue(permit.current()); assertEquals(original, permit.team); assertEquals("device", permit.target.deviceId)
        member = false; assertFalse(permit.current()); assertTrue(runCatching { permit.begin() }.isFailure)
        member = true; permit.close(); assertFalse(permit.current())
    }

    @Test fun originalOwnerControlDoesNotChangeSelectionOpenTransportOrRepeatAcknowledgedRevoke() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = selected)); val created = CopyOnWriteArrayList<Created>()
        val runtime = NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { owner, current ->
            Control(current).also { created += Created(owner, it) }
        })
        val permit = NativeForegroundComputerManagement(original, mac, { true }, { true }).beginRevoke()
        try {
            runtime.forgetForegroundComputer(permit)
            val control = created.single { it.owner == original }.control
            assertEquals(1, control.managementStarts); assertEquals(0, control.normalStarts)
            assertEquals(listOf(NativeComputerTarget("device", "default", "Original")), control.targets)
            assertTrue(control.closed); assertEquals(selected, teams.value.scope)
            runtime.forgetForegroundComputer(permit)
            assertEquals(1, created.count { it.owner == original })
            assertTrue(runCatching { runtime.connect(PairingCodeParser.parse(PairingCodeParser.computer(
                IrohV2Computer("record", "ab".repeat(32), "device", "default", "Original", emptyList()), original)).getOrThrow() as PairingCode.Iroh) }.isFailure)
        } finally { permit.close(); runtime.close() }
    }

    @Test fun permissionLossDuringControlSetupStopsBeforeRevokeAndClosesControl() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = selected)); val created = CopyOnWriteArrayList<Created>()
        val gate = CompletableDeferred<Unit>(); var member = true
        val runtime = NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { owner, current ->
            Control(current).also { if (owner == original) it.gate = gate; created += Created(owner, it) }
        })
        val permit = NativeForegroundComputerManagement(original, mac, { true }, { member }).beginRevoke()
        try {
            val result = async { runCatching { runtime.forgetForegroundComputer(permit) } }
            withTimeout(3000) { while (created.none { it.owner == original && it.control.managementStarts == 1 }) delay(5) }
            member = false; gate.complete(Unit); assertTrue(result.await().isFailure)
            val control = created.single { it.owner == original }.control
            assertTrue(control.targets.isEmpty()); assertTrue(control.closed)
        } finally { gate.cancel(); permit.close(); runtime.close() }
    }

    @Test fun explicitRetryAfterUnconfirmedRevokeUsesSameTargetAndClosesEachControl() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = selected)); val created = CopyOnWriteArrayList<Created>(); var attempts = 0
        val runtime = NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { owner, current ->
            Control(current).also { if (owner == original) it.failed = ++attempts == 1; created += Created(owner, it) }
        })
        val permit = NativeForegroundComputerManagement(original, mac, { true }, { true }).beginRevoke()
        try {
            assertTrue(runCatching { runtime.forgetForegroundComputer(permit) }.isFailure)
            runtime.forgetForegroundComputer(permit); runtime.forgetForegroundComputer(permit)
            val controls = created.filter { it.owner == original }.map { it.control }
            assertEquals(2, controls.size); assertTrue(controls.all { it.closed && it.targets.single().deviceId == "device" })
        } finally { permit.close(); runtime.close() }
    }

    @Test fun concurrentDuplicateCannotCreateSecondControlAndClosedRuntimeCannotStartOne() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = selected)); val created = CopyOnWriteArrayList<Created>()
        val gate = CompletableDeferred<Unit>()
        val runtime = NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture" }, { owner, current ->
            Control(current).also { if (owner == original) it.gate = gate; created += Created(owner, it) }
        })
        val permit = NativeForegroundComputerManagement(original, mac, { true }, { true }).beginRevoke()
        try {
            val first = async { runtime.forgetForegroundComputer(permit) }
            withTimeout(3000) { while (created.none { it.owner == original && it.control.managementStarts == 1 }) delay(5) }
            assertTrue(runCatching { runtime.forgetForegroundComputer(permit) }.isFailure)
            assertEquals(1, created.count { it.owner == original }); gate.complete(Unit); first.await()
            runtime.close()
            val next = NativeForegroundComputerManagement(original, mac, { true }, { true }).beginRevoke()
            try { assertTrue(runCatching { runtime.forgetForegroundComputer(next) }.isFailure) } finally { next.close() }
            assertEquals(1, created.count { it.owner == original })
        } finally { gate.cancel(); permit.close(); runtime.close() }
    }
}
