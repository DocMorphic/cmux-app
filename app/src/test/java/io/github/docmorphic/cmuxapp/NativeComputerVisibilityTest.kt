package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeComputerVisibilityTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private fun mac(tag: String? = "default", route: String = "100.64.0.1") = NativePairingRecords.scoped(
        NativeCredentialStore.PairedMac("cmux-ios://attach?v=2&r=$route:58465", "mac", "Studio", tag), team)
    private fun state(vararg rows: NativeCredentialStore.PairedMac) = JSONObject().put("task_session", "login")
        .put("refresh_token", "fixture").put("pairings", JSONArray(rows.map(NativePairingRecords::encode)))
    @Test fun hideKeepsPairingBytesAndBuildSiblingThenUnhidesOffline() {
        val stable = mac(); val nightly = mac("nightly", "100.64.0.2")
        val state = state(stable, nightly).put("pairing_code", stable.code).put("computer_selection", stable.origin)
        val records = state.getJSONArray("pairings").toString()
        assertTrue(NativeComputerVisibility.setVisible(state, "login", stable, false) { true })
        assertTrue(NativeComputerVisibility.isHidden(state, stable))
        assertFalse(NativeComputerVisibility.isHidden(state, nightly))
        assertEquals(records, state.getJSONArray("pairings").toString())
        assertEquals("", state.getString("pairing_code")); assertEquals("", state.getString("computer_selection"))
        val restored = JSONObject(state.toString())
        assertTrue(NativeComputerVisibility.setVisible(restored, "login", stable, true) { true })
        assertFalse(NativeComputerVisibility.isHidden(restored, stable))
    }
    @Test fun untaggedIsExactAndOtherAccountTeamAndUnrelatedSelectionSurvive() {
        val legacy = mac(null); val stable = mac()
        val other = NativePairingRecords.scoped(stable.copy(code = "cmux-ios://attach?v=2&r=100.64.0.3:58465"), team.copy(teamId = "other"))
        val state = state(legacy, stable, other).put("pairing_code", stable.code).put("computer_selection", stable.origin)
        // Give legacy its own route; visibility is exact even on one physical Mac.
        val separate = legacy.copy(code = "legacy")
        state.put("pairings", JSONArray(listOf(separate, stable, other).map(NativePairingRecords::encode)))
        assertTrue(NativeComputerVisibility.setVisible(state, "login", separate, false) { true })
        assertFalse(NativeComputerVisibility.isHidden(state, stable)); assertFalse(NativeComputerVisibility.isHidden(state, other))
        assertEquals(stable.origin, state.getString("computer_selection")); assertEquals(stable.code, state.getString("pairing_code"))
    }
    @Test fun visibilityAndLateHandshakeRemainScopedEvenForIdenticalRoute() {
        val first = mac()
        val otherTeam = NativePairingRecords.scoped(first, team.copy(teamId = "other-team"))
        val otherUser = NativePairingRecords.scoped(first, team.copy(userId = "other-user"))
        val state = state(first, otherTeam, otherUser)
        assertTrue(NativeComputerVisibility.setVisible(state, "login", first, false) { true })
        assertFalse(NativeComputerVisibility.isHidden(state, otherTeam))
        assertFalse(NativeComputerVisibility.isHidden(state, otherUser))
        NativeComputerVisibility.requireVisibleHandshake(state, otherTeam)
        NativeComputerVisibility.requireVisibleHandshake(state, otherUser)
    }

    @Test fun staleLoginChangedPairingAndRetiredTeamCannotWrite() {
        val mac = mac(); val state = state(mac); val before = state.toString()
        assertFalse(NativeComputerVisibility.setVisible(state, "old", mac, false) { true })
        assertFalse(NativeComputerVisibility.setVisible(state, "login", mac, false) { false })
        assertFalse(NativeComputerVisibility.setVisible(state, "login", mac.copy(code = "replacement"), false) { true })
        assertEquals(before, state.toString())
        state.put("pairings", JSONArray())
        assertFalse(NativeComputerVisibility.setVisible(state, "login", mac, true) { true })
    }
    @Test fun lateHandshakeCannotUnhideAndForgetPrunesOnlyRemovedMarkers() {
        val stable = mac(); val nightly = mac("nightly", "100.64.0.2"); val state = state(stable, nightly)
        NativeComputerVisibility.setVisible(state, "login", stable, false) { true }
        NativeComputerVisibility.setVisible(state, "login", nightly, false) { true }
        val before = state.toString()
        assertThrows(IllegalStateException::class.java) { NativePairingPersistence.remember(state, stable, team) }
        assertEquals(before, state.toString())
        state.put("pairings", JSONArray().put(NativePairingRecords.encode(nightly)))
        NativeComputerVisibility.prune(state)
        assertFalse(NativeComputerVisibility.isHidden(state, stable)); assertTrue(NativeComputerVisibility.isHidden(state, nightly))
        state.put("pairings", JSONArray()); NativeComputerVisibility.prune(state)
        assertTrue(NativeComputerVisibility.hiddenOrigins(state).isEmpty())
    }
    @Test fun originAliasRetainsHiddenStateAndUnhideRemovesAliasMarkers() {
        val old = NativeCredentialStore.PairedMac("old-route", "mac", "Studio", "default")
        val state = state(old); NativeComputerVisibility.setVisible(state, "login", old, false) { true }
        val migrated = mac().copy(previousOrigins = setOf(old.origin))
        state.put("pairings", JSONArray().put(NativePairingRecords.encode(migrated)))
        NativeComputerVisibility.prune(state)
        assertTrue(NativeComputerVisibility.isHidden(state, migrated))
        assertTrue(NativeComputerVisibility.setVisible(state, "login", migrated.copy(name = "Renamed"), true) { true })
        assertFalse(NativeComputerVisibility.isHidden(state, migrated))
    }
    @Test fun hiddenDirectoryRowsCannotReappearButSiblingBuildCan() {
        val hidden = mac()
        val stable = IrohV2Computer("stable", "endpoint", "mac", "default", "Studio", emptyList())
        val nightly = stable.copy(recordId = "nightly", buildTag = "nightly")
        val result = NativeReconnectComputers.merge(emptyList(), listOf(stable, nightly), listOf(hidden))
        assertEquals(listOf(nightly), result.discovered)
    }
}
