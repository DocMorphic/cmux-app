package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMacLastSeenTest {
    private val mac = NativeCredentialStore.PairedMac("route", "mac", "Studio", "default")
    private fun state() = JSONObject().put("task_session", "login").put("refresh_token", "fixture")
        .put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))

    @Test fun verifiedHistoryPersistsWithoutChangingPairingOrOriginAndNeverRegresses() {
        val state = state(); val pairing = state.getJSONArray("pairings").toString()
        assertTrue(NativeMacLastSeen.record(state, "login", mac, 1000) { true })
        assertEquals(1000L, NativeMacLastSeen.read(JSONObject(state.toString()), mac))
        assertFalse(NativeMacLastSeen.record(state, "login", mac, 900) { true })
        assertTrue(NativeMacLastSeen.record(state, "login", mac, 1100) { true })
        assertEquals(1100L, NativeMacLastSeen.read(state, mac))
        assertEquals(pairing, state.getJSONArray("pairings").toString())
    }
    @Test fun retiredLoginRemovedPairingReplacementAndRevocationCannotRecord() {
        val state = state(); val before = state.toString()
        assertFalse(NativeMacLastSeen.record(state, "old-login", mac, 1000) { true })
        assertFalse(NativeMacLastSeen.record(state, "login", mac, 1000) { false })
        assertFalse(NativeMacLastSeen.record(state, "login", mac.copy(code = "other"), 1000) { true })
        assertEquals(before, state.toString())
        state.put("pairings", JSONArray())
        assertFalse(NativeMacLastSeen.record(state, "login", mac, 1000) { true })
        assertNull(NativeMacLastSeen.read(state, mac))
    }
    @Test fun aliasMigrationPreservesHistoryAndForgetPrunesOnlyRemovedOrigins() {
        val state = state()
        NativeMacLastSeen.record(state, "login", mac, 1000) { true }
        val upgraded = NativePairingRecords.scoped(mac.copy(code = "upgraded"), NativeTeamScope("login", "user", "team", 1))
            .copy(previousOrigins = setOf(mac.origin))
        state.put("pairings", JSONArray().put(NativePairingRecords.encode(upgraded)))
        NativeMacLastSeen.prune(state)
        assertEquals(1000L, NativeMacLastSeen.read(state, upgraded))
        state.put("pairings", JSONArray()); NativeMacLastSeen.prune(state)
        assertTrue(NativeMacLastSeen.values(state).isEmpty())
    }
    @Test fun displayNameChangesAreHarmlessButUnknownAndInvalidDatesStayUnknown() {
        val state = state()
        assertNull(NativeMacLastSeen.read(state, mac))
        assertFalse(NativeMacLastSeen.record(state, "login", mac, Long.MAX_VALUE) { true })
        assertFalse(NativeMacLastSeen.record(state, "login", mac, -1) { true })
        assertTrue(NativeMacLastSeen.record(state, "login", mac.copy(name = "New name"), 1000) { true })
        assertEquals(1000L, NativeMacLastSeen.read(state, mac))
        assertNull(NativeMacLastSeen.timestamp("1000"))
        assertNull(NativeMacLastSeen.timestamp(Double.POSITIVE_INFINITY))
    }
}
