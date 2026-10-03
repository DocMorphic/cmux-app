package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeComputerListTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val device = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private fun computer(tag: String = "default", id: String = device, endpoint: String = "0123456789abcdef") =
        IrohV2Computer("record-$id-$tag", endpoint, id, tag, "Studio", emptyList())
    private fun mac(tag: String = "default", id: String = device, name: String = "Studio") =
        NativeCredentialStore.PairedMac(PairingCodeParser.computer(computer(tag, id), team), id, name, tag)
    private fun prefs(vararg entries: Pair<NativeMacIdentity, NativeMacConnectionPreference>) = NativeMacConnectionPreferences(mapOf(*entries))
    private fun rows(macs: List<NativeCredentialStore.PairedMac>, prefs: NativeMacConnectionPreferences = NativeMacConnectionPreferences(),
        history: Map<String, Long> = emptyMap(), presence: NativeMacPresenceState = NativeMacPresenceState()) =
        NativeComputerList.rows(macs, NativeMacAppearances(), presence, history, prefs, emptyList(), emptyMap())

    @Test fun methodsGroupEachComputerOnceAndKeepNewestOrderInsideNonemptySections() {
        val a = mac(); val b = mac("nightly"); val c = mac(id = "other")
        val settings = prefs(NativeMacIdentity(device, "nightly") to NativeMacConnectionPreference(NativeMacConnectionMethod.DIRECT))
        val result = NativeComputerList.sections(rows(listOf(a, b, c), settings, mapOf(a.origin to 10, b.origin to 30, c.origin to 20)))
        assertEquals(listOf("Iroh", "Direct"), result.map { it.first })
        assertEquals(listOf(c, a), result[0].second.map { it.mac })
        assertEquals(listOf(b), result[1].second.map { it.mac })
        assertEquals(3, result.sumOf { it.second.size })
        assertTrue(NativeComputerList.sections(emptyList()).isEmpty())
    }
    @Test fun routeUsesExactBuildAndCurrentDirectoryBeforeSavedPeerHint() {
        val m = mac()
        fun route(directory: List<IrohV2Computer>) = NativeComputerList.route(device.uppercase(), "default",
            NativeMacConnectionPreferences(), directory, emptyMap(), m.code).endpoint
        assertEquals("current-peer…", route(listOf(computer(endpoint = "current-peer-0123456789"), computer("nightly", endpoint = "wrong"))))
        assertEquals("0123456789ab…", route(listOf(computer("nightly", endpoint = "wrong"))))
        assertEquals("0123456789ab…", route(listOf(computer(), computer(endpoint = "ambiguous"))))
        assertNull(NativeComputerList.route("wrong", "default", NativeMacConnectionPreferences(), emptyList(), emptyMap(), m.code).endpoint)
    }
    @Test fun directSkipsDisabledCoordinatesAndTailscaleDoesNotBorrowAnotherBuild() {
        val id = NativeMacIdentity(device, "default")
        val pref = prefs(id to NativeMacConnectionPreference(NativeMacConnectionMethod.DIRECT, listOf(
            NativeDirectAddress("10.0.0.1:42", enabled = false), NativeDirectAddress("[fd00::1]:43"))))
        assertEquals("[fd00::1]:43", NativeComputerList.route(device, "default", pref, listOf(computer()), emptyMap()).endpoint)
        val ts = prefs(id to NativeMacConnectionPreference(NativeMacConnectionMethod.TAILSCALE))
        assertNull(NativeComputerList.route(device, "default", ts, listOf(computer()), mapOf(NativeMacIdentity(device, "nightly") to "wrong")).endpoint)
        assertEquals("100.64.0.1:58465", NativeComputerList.route(device, "default", ts, emptyList(), mapOf(id to "100.64.0.1:58465")).endpoint)
    }
    @Test fun unreadablePreferencesNeverPretendToKnowNativeMethodButLegacyTcpUsesItsActualRoute() {
        val failed = NativeMacConnectionPreferences(error = true)
        assertEquals("Connection settings unavailable", rows(listOf(mac()), failed).single().route.section)
        assertNull(rows(listOf(mac()), failed).single().route.endpoint)
        val legacy = mac().copy(code = "cmux-ios://attach?v=2&r=100.64.0.1:58465")
        assertEquals(NativeComputerRouteLabel(NativeMacConnectionMethod.TAILSCALE, "100.64.0.1:58465"), rows(listOf(legacy), failed).single().route)
    }
    @Test fun onlyConfirmedOfflineDuplicateIsMarkedAfterNewestFirstOrdering() {
        val old = mac(id = "old", name = " Studio "); val fresh = mac(id = "fresh", name = "STUDIO")
        val unknown = mac(id = "unknown"); val online = mac(id = "online")
        val instances = listOf(old to false, fresh to true, online to true).associate { (mac, status) ->
            val id = NativeMacIdentity(mac.deviceId, mac.instanceTag)
            id to NativeMacPresenceInstance(id, null, status)
        }
        val rows = rows(listOf(old, unknown, online, fresh), history = mapOf(fresh.origin to 40, online.origin to 30, unknown.origin to 20, old.origin to 10),
            presence = NativeMacPresenceState(team, instances))
        assertEquals(listOf(fresh, online, unknown, old), rows.map { it.mac })
        assertEquals(listOf(false, false, false, true), rows.map { it.olderPairing })
        assertFalse(rows(listOf(old), presence = NativeMacPresenceState(team, instances)).single().olderPairing)
    }
    @Test fun routeProjectionRequiresCurrentLoginAndExactAccountTeamBuild() {
        var state = JSONObject().put("task_session", "login").put("refresh_token", "fixture")
        val store = TailscaleGrantStore({ state }, { it(state) })
        val route = PairingCode.Route("100.64.0.1", 58465)
        val grant = TailscaleSavedGrant("00000000-0000-0000-0000-000000000001", "user", "team", "a".repeat(64), device, "default", route)
        store.save(team, grant, permits = { true })
        val target = NativeComputerTarget(device.uppercase(), "default", "Studio")
        assertEquals(mapOf(NativeMacIdentity(device, "default") to "100.64.0.1:58465"), NativeComputerList.tailscaleRoutes(state, team, listOf(target)))
        assertTrue(NativeComputerList.tailscaleRoutes(state, team.copy(login = "retired"), listOf(target)).isEmpty())
        assertTrue(NativeComputerList.tailscaleRoutes(state, team.copy(teamId = "other"), listOf(target)).isEmpty())
        assertTrue(NativeComputerList.tailscaleRoutes(state, team, listOf(target.copy(buildTag = "nightly"))).isEmpty())
        state = JSONObject()
        assertTrue(NativeComputerList.tailscaleRoutes(state, team, listOf(target)).isEmpty())
    }
}
