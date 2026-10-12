package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeForegroundConnectionHistoryTest {
    private val a = NativeTeamScope("login", "user", "a", 1)
    private val b = a.copy(teamId = "b", generation = 2)
    private fun mac(team: NativeTeamScope) = NativeCredentialStore.PairedMac("secret-pair-code-${team.teamId}", "device", "Mac",
        "default", team.userId, team.teamId, "origin-${team.teamId}")
    private class Fixture {
        val state = JSONObject()
        val history = NativeForegroundConnectionHistory({ state }) { change -> change(state) }
    }
    @Test fun perTeamHistoryChoosesOnlyThatTeamsCurrentPermittedSavedRecord() {
        val f = Fixture(); val first = mac(a); val second = mac(b)
        f.history.record(a, first) { true }; f.history.record(b, second) { true }
        assertEquals(second, f.history.candidate(b, listOf(first, second)) { true })
        assertEquals(first, f.history.candidate(a.copy(generation = 3), listOf(first, second)) { true })
        assertNull(f.history.candidate(b, listOf(first, second)) { false })
        assertNull(f.history.candidate(b.copy(userId = "other"), listOf(first, second)) { true })
        assertNull(f.history.candidate(null, listOf(first, second)) { true })
    }
    @Test fun removalReplacementAndAmbiguousSavedRecordsCannotReconnectFromHistory() {
        val f = Fixture(); val row = mac(a); f.history.record(a, row) { true }
        assertNull(f.history.candidate(a, emptyList()) { true })
        for (replacement in listOf(row.copy(deviceId = "other"), row.copy(instanceTag = "nightly"),
            row.copy(accountTeamId = "other"), row.copy(stableOrigin = "other")))
            assertNull(f.history.candidate(a, listOf(replacement)) { true })
        assertNull(f.history.candidate(a, listOf(row, row.copy(name = "Duplicate"))) { true })
    }
    @Test fun renamedRecordAndFreshRouteKeepTheCandidateWithoutSavingCredentials() {
        val f = Fixture(); val row = mac(a); f.history.record(a, row) { true }
        val latest = row.copy(name = "New name", code = "fresh-private-route")
        assertEquals(latest, f.history.candidate(a, listOf(latest)) { true })
        assertFalse(f.state.toString().contains(row.code)); assertFalse(f.state.toString().contains(latest.code))
    }
    @Test fun admissionLossAndWrongMacScopeCannotRecordAForegroundCandidate() {
        val f = Fixture()
        assertTrue(runCatching { f.history.record(a, mac(a)) { false } }.isFailure)
        assertTrue(runCatching { f.history.record(a, mac(b)) { true } }.isFailure)
        assertFalse(f.state.has("foreground_connection_history"))
    }
}
