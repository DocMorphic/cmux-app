package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativePairingPersistenceTest {
    private val scope = NativeTeamScope("login", "user", "team", 1)
    private val qr = "cmux-ios://attach?v=2&r=100.99.1.2:58465&ub=user"
    private val incoming = NativeCredentialStore.PairedMac(qr, "mac", "QR name", "default")
    private fun native(team: NativeTeamScope = scope, device: String = "mac", build: String = "default", endpoint: String = "peer") =
        NativeCredentialStore.PairedMac(PairingCodeParser.computer(IrohV2Computer("record", endpoint, device, build, "Native name", emptyList()), team),
            device, "Native name", build)
    private fun state(vararg rows: NativeCredentialStore.PairedMac) = JSONObject().put("task_session", scope.login)
        .put("refresh_token", "fixture-refresh").put("pairings", JSONArray(rows.map { row ->
            JSONObject().put("code", row.code).put("device_id", row.deviceId).put("name", row.name).put("instance_tag", row.instanceTag)
        }))
    private fun grant(state: JSONObject, row: NativeCredentialStore.PairedMac = incoming, team: NativeTeamScope = scope): TailscaleSavedGrant {
        val pairing = PairingCodeParser.parse(row.code).getOrThrow() as PairingCode.Tailscale
        val grant = TailscaleSavedGrant(UUID.randomUUID().toString(), team.userId, team.teamId, TailscaleGrantStore.source(pairing),
            canonicalMacDeviceId(row.deviceId), row.instanceTag, pairing.routes.single())
        TailscaleGrantStore({ state }, { it(state) }).save(team, grant) { true }
        return grant
    }
    private fun codes(state: JSONObject) = state.getJSONArray("pairings").let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it).getString("code") }
    }

    @Test fun authenticatedQrPreservesNativeIdentityNameOriginAndSelection() {
        val native = native(); val sibling = native(build = "debug", endpoint = "other-peer")
        val state = state(native, sibling).put("computer_selection", native.origin)
        val grant = grant(state)
        val rowsBefore = state.getJSONArray("pairings").toString()
        val result = NativePairingPersistence.remember(state, incoming, scope)
        assertEquals(native, result); assertEquals(native.origin, result.origin)
        assertEquals(rowsBefore, state.getJSONArray("pairings").toString())
        assertEquals(native.code, state.getString("pairing_code"))
        assertEquals(native.origin, state.getString("computer_selection"))
        assertEquals(grant, TailscaleGrantStore({ state }, { error("read only") }).find(scope, grant.source))
    }

    @Test fun uuidAliasesPreserveNativePairingAndOpaqueIdsRemainCaseSensitive() {
        val device = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
        val native = native(device = device)
        val row = incoming.copy(deviceId = device.lowercase())
        val state = state(native); grant(state, row)
        assertEquals(native, NativePairingPersistence.remember(state, row, scope))
        val opaque = state(native(device = "MAC")); grant(opaque)
        assertEquals(incoming, NativePairingPersistence.remember(opaque, incoming, scope))
        assertEquals(2, codes(opaque).size)
    }

    @Test fun anotherAccountOrTeamNativeRowSurvivesQrPersistence() {
        for (owner in listOf(scope.copy(userId = "other"), scope.copy(teamId = "other"))) {
            val other = native(owner)
            val state = state(other); grant(state)
            assertEquals(incoming, NativePairingPersistence.remember(state, incoming, scope))
            assertEquals(listOf(other.code, incoming.code), codes(state))
        }
    }

    @Test fun exactBuildIsRequiredAndUntaggedQrCannotReplaceTaggedIdentity() {
        val native = native()
        for (build in listOf("debug", null)) {
            val row = incoming.copy(instanceTag = build)
            val state = state(native); grant(state, row)
            assertEquals(row, NativePairingPersistence.remember(state, row, scope))
            assertEquals(listOf(native.code, qr), codes(state))
        }
    }

    @Test fun absentOrWrongGrantRejectsBeforeChangingAnyPairing() {
        for (change in listOf<(JSONObject) -> Unit>({}, { grant(it, incoming.copy(deviceId = "other")) },
            { grant(it, incoming.copy(instanceTag = "debug")) }, { grant(it, team = scope.copy(teamId = "other")) },
            { grant(it, team = scope.copy(userId = "other")) })) {
            val state = state(native()); change(state); val before = state.toString()
            assertTrue(runCatching { NativePairingPersistence.remember(state, incoming, scope) }.isFailure)
            assertEquals(before, state.toString())
        }
    }

    @Test fun loginChangeRejectsEvenIfOldRouteGrantStillExists() {
        val state = state(native()); grant(state); state.put("task_session", "new-login")
        val before = state.toString()
        assertTrue(runCatching { NativePairingPersistence.remember(state, incoming, scope) }.isFailure)
        assertEquals(before, state.toString())
    }

    @Test fun ambiguousNativeRowsAreNotChosenByTheirStoredOrder() {
        val state = state(native(), native(endpoint = "second-peer")); grant(state)
        val before = state.toString()
        assertTrue(runCatching { NativePairingPersistence.remember(state, incoming, scope) }.isFailure)
        assertEquals(before, state.toString())
    }

    @Test fun unscopedOrInconsistentNativeIdentityCannotBeOverwrittenByQr() {
        for (code in listOf("cmux-ios://attach?v=3&i=peer&d=mac&b=default", native(device = "other").code)) {
            val state = state(native().copy(code = code)); grant(state); val before = state.toString()
            assertTrue(runCatching { NativePairingPersistence.remember(state, incoming, scope) }.isFailure)
            assertEquals(before, state.toString())
        }
    }

    @Test fun reconnectingNativeLocatorCannotWriteUnderAnotherScopeOrIdentity() {
        for (row in listOf(native(scope.copy(teamId = "other")), native().copy(deviceId = "other"), native().copy(instanceTag = "debug"))) {
            val state = state(); val before = state.toString()
            assertTrue(runCatching { NativePairingPersistence.remember(state, row, scope) }.isFailure)
            assertEquals(before, state.toString())
        }
    }

    @Test fun nativeReconnectDoesNotEraseAnotherTeamsSamePhysicalMac() {
        val other = native(scope.copy(teamId = "other")); val current = native()
        val state = state(other)
        assertEquals(current, NativePairingPersistence.remember(state, current, scope))
        assertEquals(listOf(other.code, current.code), codes(state))
    }

    @Test fun existingStandaloneQrRowStillUpdatesWithoutCreatingDuplicates() {
        val state = state(incoming.copy(name = "Before")); grant(state)
        assertEquals(incoming, NativePairingPersistence.remember(state, incoming, scope))
        assertEquals(listOf(qr), codes(state))
        assertEquals(incoming.name, state.getJSONArray("pairings").getJSONObject(0).getString("name"))
    }
}
