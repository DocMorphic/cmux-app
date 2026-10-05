package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeRetainedRouteTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val native = NativeCredentialStore.PairedMac(PairingCodeParser.computer(
        IrohV2Computer("record", "a".repeat(64), "mac", "default", "Mac", emptyList()), team), "mac", "Mac", "default")
    private val raw = NativeCredentialStore.PairedMac("cmux-ios://attach?v=2&r=100.99.1.2:58465&ub=user", "mac", "Mac", "default")
    private fun state() = JSONObject().put("task_session", team.login).put("refresh_token", "fixture-refresh")
    private fun grant(state: JSONObject): TailscaleSavedGrant {
        val pairing = PairingCodeParser.parse(raw.code).getOrThrow() as PairingCode.Tailscale
        return TailscaleSavedGrant("00000000-0000-4000-8000-000000000001", team.userId, team.teamId, TailscaleGrantStore.source(pairing), "mac", "default", pairing.routes.single())
            .also { value -> TailscaleGrantStore({ state }, { it(state) }).save(team, value) { true } }
    }
    private fun ticket() = MobileAttachTicketCodec.decodeJson(JSONObject().put("version", 1).put("workspaceID", "workspace")
        .put("macDeviceID", "mac").put("macUserID", "user").put("auth_token", "private-fixture-ticket")
        .put("routes", JSONArray().put(JSONObject().put("id", "tail").put("kind", "tailscale")
            .put("endpoint", JSONObject().put("type", "host_port").put("host", "100.99.1.2").put("port", 58465))))
        .toString()).getOrThrow()
    private fun retained(state: JSONObject): NativeCredentialStore.PairedMac {
        NativePairingPersistence.remember(state, native, team); grant(state)
        val row = NativePairingPersistence.remember(state, raw, team, preferIncomingRoute = true)
        return NativeAttachTicketStore.install(state, team, row, ticket(), null)
    }

    @Test fun ticketRetainsOnlyPreviouslyAuthenticatedNativeRouteAndExactTicketBinding() {
        val state = state(); val before = NativePairingPersistence.remember(state, native, team)
        val row = retained(state)
        assertEquals(raw.code, row.code); assertEquals(native.code, row.nativeRouteCode); assertEquals(before.origin, row.origin)
        assertNotNull(row.ticketRevision)
        val restored = NativePairingRecords.decode(NativePairingRecords.encode(row))!!
        assertEquals(row, restored)
        assertEquals("private-fixture-ticket", NativeAttachTicketStore.read(state, team, restored)!!
            .tokenFor("workspace.list", JSONObject(), 0))
        assertFalse(NativePairingRecords.encode(row).toString().contains("private-fixture-ticket"))
        assertFalse(NativeComputerMenuPairing.isCurrent(row.copy(nativeRouteCode = null), listOf(row)))
    }
    @Test fun methodsSelectRetainedNativeWithoutExtendingTheRawTicketsCoverage() {
        val row = retained(state())
        for (method in listOf(NativeMacConnectionMethod.IROH, NativeMacConnectionMethod.DIRECT)) {
            val selection = nativeSavedMacRoute(row, method)
            assertEquals(native.code, selection.code); assertTrue(selection.pairing is PairingCode.Iroh)
            assertFalse(selection.usesPrimaryTicket)
        }
        val tail = nativeSavedMacRoute(row, NativeMacConnectionMethod.TAILSCALE)
        assertEquals(raw.code, tail.code); assertTrue(tail.usesPrimaryTicket)
        assertEquals(NativeComputerTarget("mac", "default", "Mac"), NativeComputerTarget.from(row, team))
        assertNull(NativeComputerTarget.from(row, team.copy(teamId = "other")))
    }
    @Test fun nativeReconnectPromotesItsAuthenticatedRouteAndRetiresUncoveredTicketWithoutLosingOrigin() {
        val state = state(); val row = retained(state)
        state.put("computer_selection", row.origin)
        val nativeReconnected = NativePairingPersistence.remember(state, native, team, expected = row)
        assertEquals(row.origin, nativeReconnected.origin); assertEquals(native.code, nativeReconnected.code)
        assertNull(nativeReconnected.nativeRouteCode); assertNull(nativeReconnected.ticketRevision)
        assertFalse(state.has(NativeAttachTicketStore.KEY)); assertEquals(row.origin, state.getString("computer_selection"))
    }
    @Test fun rawReconnectKeepsTicketAndNativeAlternativeAndGrantRemovalDoesNotRemoveNativeAuthority() {
        val state = state(); val row = retained(state)
        val same = NativePairingPersistence.remember(state, raw, team, expected = row)
        assertEquals(row, same)
        val grants = TailscaleGrantStore({ state }, { it(state) })
        val grant = grants.computer(team, NativeComputerTarget("mac", "default", "Mac")).single()
        grants.removeRoute(team, NativeComputerTarget("mac", "default", "Mac"), grant) { true }
        assertTrue(NativePairingRecords.usable(row, team, grants))
        assertNull(grants.find(team, grant.source))
        assertTrue(nativeSavedMacRoute(row, NativeMacConnectionMethod.IROH).pairing is PairingCode.Iroh)
    }
    @Test fun freshIncomingDataCannotInventANativeAlternative() {
        val state = state(); grant(state)
        val row = NativePairingPersistence.remember(state, raw.copy(nativeRouteCode = native.code), team, preferIncomingRoute = true)
        assertNull(row.nativeRouteCode)
        assertEquals(raw.code, nativeSavedMacRoute(row, NativeMacConnectionMethod.IROH).code)
    }
    @Test fun malformedOrCrossScopeRetainedRoutesCannotRestoreAuthority() {
        val row = retained(state())
        val otherNative = PairingCodeParser.computer(IrohV2Computer("other", "b".repeat(64), "other", "nightly", "Mac", emptyList()), team)
        for (code in listOf("garbage", raw.code, otherNative, native.code.replace("user", "other-user"))) {
            assertNull(NativePairingRecords.decode(NativePairingRecords.encode(row).put("native_route_code", code)))
        }
        assertNull(NativePairingRecords.decode(NativePairingRecords.encode(row).apply { remove("owner_user"); remove("owner_team") }))
        assertFalse(NativePairingRecords.usable(row, team.copy(teamId = "other"), TailscaleGrantStore({ state() }, {})))
    }
}
