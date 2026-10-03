package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMacVersionHistoryTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val stable = NativeMacIdentity("mac", "default")
    private val nightly = NativeMacIdentity("mac", "nightly")
    private fun mac(id: NativeMacIdentity = stable, owner: NativeTeamScope = team, route: String = "100.64.0.1") =
        NativePairingRecords.scoped(NativeCredentialStore.PairedMac("cmux-ios://attach?v=2&r=$route:58465", id.deviceId, "Studio", id.buildTag), owner)
    private fun state(vararg rows: NativeCredentialStore.PairedMac) = JSONObject().put("task_session", team.login)
        .put("refresh_token", "fixture").put("pairings", JSONArray(rows.map(NativePairingRecords::encode)))

    @Test fun versionSurvivesSerializationOutsidePairingAndRoutesAndSuppressesUnchangedWrites() {
        val state = state(mac()); val original = state.getJSONArray("pairings").toString()
        assertTrue(NativeMacVersionHistory.record(state, team, mapOf(stable to "0.64.24")) { true })
        assertEquals(mapOf(stable to "0.64.24"), NativeMacVersionHistory.read(JSONObject(state.toString()), team.copy(generation = 2)))
        assertEquals(original, state.getJSONArray("pairings").toString())
        assertFalse(NativeMacVersionHistory.record(state, team, mapOf(stable to "0.64.24")) { true })
    }
    @Test fun accountTeamBuildAndUntaggedIdentityRemainExact() {
        val otherTeam = team.copy(teamId = "other"); val otherUser = team.copy(userId = "other")
        val legacy = stable.copy(buildTag = null)
        val state = state(mac(), mac(nightly), mac(legacy), mac(owner = otherTeam), mac(owner = otherUser))
        NativeMacVersionHistory.record(state, team, mapOf(stable to "0.64.24", nightly to "nightly", legacy to "0.64.17")) { true }
        assertEquals(3, NativeMacVersionHistory.read(state, team).size)
        assertTrue(NativeMacVersionHistory.read(state, otherTeam).isEmpty())
        assertTrue(NativeMacVersionHistory.read(state, otherUser).isEmpty())
        NativeMacVersionHistory.record(state, otherTeam, mapOf(stable to "0.65")) { true }
        assertEquals("0.64.24", NativeMacVersionHistory.read(state, team)[stable])
        assertEquals("0.65", NativeMacVersionHistory.read(state, otherTeam)[stable])
    }
    @Test fun staleLoginPermissionOrMissingPairingCannotWriteOrRecreateState() {
        val state = state(mac()); val before = state.toString()
        assertFalse(NativeMacVersionHistory.record(state, team.copy(login = "retired"), mapOf(stable to "0.1")) { true })
        assertFalse(NativeMacVersionHistory.record(state, team, mapOf(stable to "0.1")) { false })
        assertFalse(NativeMacVersionHistory.record(state, team, mapOf(nightly to "0.1")) { true })
        assertEquals(before, state.toString())
        state.remove("refresh_token")
        assertFalse(NativeMacVersionHistory.record(state, team, mapOf(stable to "0.1")) { true })
        assertTrue(NativeMacVersionHistory.read(state, team).isEmpty())
    }
    @Test fun missingReportedVersionIsAStoredObservationNotTheAbsenceOfAnObservation() {
        val state = state(mac())
        assertTrue(NativeMacVersionHistory.record(state, team, mapOf(stable to null)) { true })
        val restored = NativeMacVersionHistory.read(JSONObject(state.toString()), team)
        assertTrue(restored.containsKey(stable)); assertNull(restored[stable])
        assertFalse(NativeMacVersionHistory.record(state, team, mapOf(stable to null)) { true })
        assertTrue(NativeMacVersionHistory.record(state, team, mapOf(stable to "0.65")) { true })
    }
    @Test fun hideRetainsHistoryAndForgetPrunesOnlyRemovedExactMac() {
        val a = mac(); val b = mac(nightly, route = "100.64.0.2"); val state = state(a, b)
        NativeMacVersionHistory.record(state, team, mapOf(stable to "0.64.24", nightly to "0.64.25-nightly.1")) { true }
        NativeComputerVisibility.setVisible(state, team.login, a, false) { true }
        NativeMacVersionHistory.prune(state)
        assertEquals(2, NativeMacVersionHistory.read(state, team).size)
        NativePairingRecords.removeLocal(state, a.code, team); NativeMacVersionHistory.prune(state)
        assertEquals(setOf(nightly), NativeMacVersionHistory.read(state, team).keys)
        assertFalse(NativeMacVersionHistory.record(state, team, mapOf(stable to "0.1")) { true })
        NativePairingRecords.removeLocal(state, b.code, team); NativeMacVersionHistory.prune(state)
        assertFalse(state.has(NativeMacVersionHistory.KEY))
    }
    @Test fun routeChangesAndAliasesKeepVersionWithoutWildcardBuildMigration() {
        val original = mac(); val state = state(original)
        NativeMacVersionHistory.record(state, team, mapOf(stable to "0.64.24")) { true }
        val moved = original.copy(code = "cmux-ios://attach?v=2&r=100.64.0.9:58465", previousOrigins = setOf(original.origin))
        state.put("pairings", JSONArray(listOf(NativePairingRecords.encode(moved))))
        NativeMacVersionHistory.prune(state)
        assertEquals("0.64.24", NativeMacVersionHistory.read(state, team)[stable])
        state.put("pairings", JSONArray(listOf(NativePairingRecords.encode(moved.copy(instanceTag = "nightly")))))
        NativeMacVersionHistory.prune(state)
        assertTrue(NativeMacVersionHistory.read(state, team).isEmpty())
    }
    @Test fun corruptDuplicateWrongScalarAndOversizedCacheNeverBecomeVersions() {
        val state = state(mac()); NativeMacVersionHistory.record(state, team, mapOf(stable to "0.64.24")) { true }
        val row = state.getJSONArray(NativeMacVersionHistory.KEY).getJSONObject(0)
        state.put(NativeMacVersionHistory.KEY, JSONArray().put(row).put(row))
        assertTrue(NativeMacVersionHistory.read(state, team).isEmpty())
        state.put(NativeMacVersionHistory.KEY, JSONArray().put(JSONObject(row.toString()).put("version", 25)))
        assertTrue(NativeMacVersionHistory.read(state, team).isEmpty())
        state.put(NativeMacVersionHistory.KEY, JSONArray().put(JSONObject(row.toString()).put("version", "a".repeat(1025))))
        assertTrue(NativeMacVersionHistory.read(state, team).isEmpty())
        state.put(NativeMacVersionHistory.KEY, JSONArray(List(257) { row }))
        assertTrue(NativeMacVersionHistory.read(state, team).isEmpty())
    }
    @Test fun unknownOwnerAndAmbiguousLegacyRecordsDoNotReceiveHistory() {
        val legacy = NativeCredentialStore.PairedMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "mac", "Studio", "default")
        val state = state(legacy)
        assertFalse(NativeMacVersionHistory.record(state, team, mapOf(stable to "0.1")) { true })
        assertFalse(state.has(NativeMacVersionHistory.KEY))
    }
}
