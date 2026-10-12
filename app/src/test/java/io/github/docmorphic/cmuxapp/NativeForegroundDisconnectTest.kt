package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException

class NativeForegroundDisconnectTest {
    private val team = NativeTeamScope("login", "user", "a", 1)
    private val owner = NativeConnectionRecoveryOwner("login", "route", team)
    private val client = Any()
    private fun decision(selected: NativeTeamScope? = team, current: Any? = client, login: String? = owner.login,
        code: String = owner.code, connected: String? = owner.code, retry: Int = 3,
        failure: Throwable = EOFException()) = nativeForegroundDisconnect(owner, client, current, login, code,
            connected, 3, retry, selected, failure)

    @Test fun aLostForegroundIsHandledAfterPickerSelectionAndUsesTheSelectedScope() {
        assertEquals(NativeForegroundDisconnect.SELECTED_SCOPE, decision(team.copy(teamId = "b", generation = 2)))
        assertEquals(NativeForegroundDisconnect.SELECTED_SCOPE, decision(null))
        assertEquals(NativeForegroundDisconnect.SELECTED_SCOPE, decision(team.copy(userId = "other")))
    }
    @Test fun returningToTheSameTeamUsesFreshAdmissionEvenThoughItsGenerationChanged() {
        assertEquals(NativeForegroundDisconnect.RETRY, decision(team.copy(generation = 3)))
        assertEquals(NativeForegroundDisconnect.HOLD, decision(team.copy(generation = 3),
            failure = MobileRpcException("forbidden", "Fixture", true)))
    }
    @Test fun replacedClientLoginCodeConnectedCodeAndRetryCannotApplyAnOldFailure() {
        for (result in listOf(decision(current = Any()), decision(current = null), decision(login = "other"),
            decision(code = "new"), decision(connected = "new"), decision(connected = null), decision(retry = 4)))
            assertEquals(NativeForegroundDisconnect.IGNORE, result)
    }
    @Test fun aPickerChangeOrExplicitRetryDuringTheDelayChangesTheRecoveryDecision() {
        assertEquals(NativeForegroundDisconnect.RETRY, decision())
        assertEquals(NativeForegroundDisconnect.SELECTED_SCOPE, decision(team.copy(teamId = "b", generation = 2)))
        assertEquals(NativeForegroundDisconnect.IGNORE, decision(retry = 4))
    }
}
