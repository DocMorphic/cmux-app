package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import io.github.docmorphic.cmuxapp.iroh.IrxWire

class NativeConnectionRecoveryGateTest {
    private val owner = NativeConnectionRecoveryOwner("login", "route", NativeTeamScope("login", "user", "team", 1))
    private fun denied() = MobileRpcException("forbidden", "Rejected", true)

    @Test fun sameAttemptRemainsHeldAcrossRepeatedDirectoryAndLifecycleEntry() {
        val gate = NativeConnectionRecoveryGate()
        assertTrue(gate.begin(owner, 3)); gate.failed(owner, 3, denied())
        repeat(10) { assertFalse(gate.begin(owner, 3)) }
        assertTrue(gate.state.value?.requiresReauth == true)
    }
    @Test fun explicitNewGenerationAllowsRecoveryAndLateOldFailureCannotReplaceIt() {
        val gate = NativeConnectionRecoveryGate()
        gate.begin(owner, 3); gate.failed(owner, 3, denied())
        assertTrue(gate.begin(owner, 4)); gate.failed(owner, 3, denied()); assertNull(gate.state.value)
        gate.failed(owner, 4, denied()); gate.connected(owner, 3)
        assertFalse(gate.begin(owner, 4))
        gate.connected(owner, 4); assertNull(gate.state.value)
    }
    @Test fun routeLoginAndTeamChangesCannotInheritAnotherOwnersFailure() {
        for (other in listOf(owner.copy(code = "other"), owner.copy(login = "new-login"),
            owner.copy(team = owner.team!!.copy(teamId = "other-team")))) {
            val gate = NativeConnectionRecoveryGate()
            gate.begin(owner, 0); gate.failed(owner, 0, denied())
            assertTrue(gate.begin(other, 0)); gate.failed(owner, 0, denied())
            assertNull(gate.state.value)
        }
    }
    @Test fun aFreshGenerationOfTheSameTeamCannotEraseARejectedForegroundAttempt() {
        val gate = NativeConnectionRecoveryGate()
        val current = owner.copy(team = owner.team!!.copy(generation = 3))
        gate.begin(owner, 3); gate.failed(owner, 3, denied())
        assertFalse(gate.begin(current, 3)); assertEquals(current, gate.state.value!!.owner)
        gate.failed(owner, 3, denied()); assertEquals(current, gate.state.value!!.owner)
        assertTrue(gate.begin(current, 4)); gate.failed(owner, 3, denied()); assertNull(gate.state.value)
    }
    @Test fun networkFailuresRetryButTerminalAdmissionFailureWaitsWithoutClaimingAccountMismatch() {
        val gate = NativeConnectionRecoveryGate()
        gate.begin(owner, 0); gate.failed(owner, 0, SocketTimeoutException("unreachable"))
        assertTrue(gate.begin(owner, 0))
        gate.failed(owner, 0, IrxWire.AdmissionRejected(IrxWire.CloseCode.REVOKED))
        assertFalse(gate.begin(owner, 0)); assertFalse(gate.state.value!!.requiresReauth)
        gate.clear(); assertTrue(gate.begin(owner, 0))
    }
}
