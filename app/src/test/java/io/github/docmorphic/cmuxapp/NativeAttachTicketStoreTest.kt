package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeAttachTicketStoreTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val peer = "a".repeat(64)
    private fun mac(scope: NativeTeamScope = team, endpoint: String = peer) = NativePairingRecords.scoped(
        NativeCredentialStore.PairedMac(PairingCodeParser.computer(
            IrohV2Computer("record", endpoint, "device", "default", "Mac", emptyList()), scope), "device", "Mac", "default"), scope)
    private fun state(vararg rows: NativeCredentialStore.PairedMac) = JSONObject().put("task_session", team.login)
        .put("refresh_token", "fixture-refresh").put("pairings", JSONArray(rows.map(NativePairingRecords::encode)))
    private fun payload(token: String = "synthetic-ticket") = JSONObject().put("version", 1).put("workspaceID", "workspace")
        .put("terminalID", "terminal").put("macDeviceID", "device").put("macUserID", "user").put("auth_token", token)
        .put("expiresAt", "2030-01-01T00:00:00Z").put("routes", JSONArray().put(JSONObject().put("id", "iroh")
            .put("kind", "iroh").put("endpoint", JSONObject().put("type", "peer").put("id", peer))))
    private fun ticket(value: JSONObject = payload()) = MobileAttachTicketCodec.decodeJson(value.toString()).getOrThrow()
    private fun install(value: JSONObject, row: NativeCredentialStore.PairedMac = mac(), payload: JSONObject = payload(), scope: NativeTeamScope = team) =
        NativeAttachTicketStore.install(value, scope, row, ticket(payload), "user@example.invalid")
    private fun rows(value: JSONObject) = value.getJSONArray("pairings").let { array ->
        (0 until array.length()).map { NativePairingRecords.decode(array.getJSONObject(it))!! }
    }
    private fun assertRejectedWithoutMutation(value: JSONObject, block: () -> Unit) {
        val before = value.toString()
        assertTrue(runCatching(block).isFailure)
        assertEquals(before, value.toString())
    }

    @Test fun roundTripKeepsBearerOutOfPublicRecordAndPreservesSelectionAndExpiry() {
        val state = state(mac())
        val saved = install(state)
        assertNotNull(saved.ticketRevision)
        assertEquals(mac().code, saved.code); assertEquals(mac().origin, saved.origin)
        assertFalse(saved.toString().contains("synthetic-ticket"))
        assertFalse(NativePairingRecords.encode(saved).toString().contains("synthetic-ticket"))
        val restored = JSONObject(state.toString())
        val context = NativeAttachTicketStore.read(restored, team, rows(restored).single())!!
        assertEquals("workspace", context.workspaceId); assertEquals("terminal", context.terminalId)
        val expiry = 1_893_456_000_000
        assertEquals(expiry, context.expiresAtMillis)
        assertEquals("synthetic-ticket", context.tokenFor("workspace.list", JSONObject(), expiry - 1))
        assertNull(context.tokenFor("workspace.list", JSONObject(), expiry))
    }

    @Test fun rejectsAnotherDeviceAccountEmailAndPeerWithoutChangingSavedState() {
        for (payload in listOf(payload().put("macDeviceID", "other"), payload().put("macUserID", "other"),
            payload().put("macUserEmail", "other@example.invalid"), payload().apply {
                getJSONArray("routes").getJSONObject(0).getJSONObject("endpoint").put("id", "b".repeat(64))
            })) {
            val state = state(mac())
            assertRejectedWithoutMutation(state) { install(state, payload = payload) }
        }
    }

    @Test fun rejectsStaleLoginWrongTeamForgottenReplacedAndAmbiguousRows() {
        for (state in listOf(state(), state(mac(endpoint = "b".repeat(64))), state(mac(), mac()),
            state(mac()).put("task_session", "new-login"), state(mac()).put("refresh_token", "")))
            assertRejectedWithoutMutation(state) { install(state) }
        val state = state(mac())
        assertRejectedWithoutMutation(state) { install(state, scope = team.copy(teamId = "other")) }
    }

    @Test fun replacementRetiresOldRevisionAndDeletesOldBearer() {
        val state = state(mac())
        val old = install(state)
        val replacement = install(state, old, payload("replacement-ticket"))
        assertNotEquals(old.ticketRevision, replacement.ticketRevision)
        assertFalse(NativeComputerMenuPairing.isCurrent(old, rows(state)))
        assertTrue(runCatching { NativeAttachTicketStore.read(state, team, old) }.isFailure)
        assertEquals(1, state.getJSONObject(NativeAttachTicketStore.KEY).length())
        assertFalse(state.toString().contains("synthetic-ticket"))
        assertEquals("replacement-ticket", NativeAttachTicketStore.read(state, team, replacement)!!
            .tokenFor("workspace.list", JSONObject(), 0))
        assertRejectedWithoutMutation(state) { install(state, old) }
    }

    @Test fun reconnectRetainsTicketButRouteReplacementDropsItWithoutChangingOrigin() {
        val state = state(mac())
        val saved = install(state)
        val reconnected = NativePairingPersistence.remember(state, mac().copy(name = "Host rename"), team, saved)
        assertEquals(saved.ticketRevision, reconnected.ticketRevision)
        assertNotNull(NativeAttachTicketStore.read(state, team, reconnected))
        val moved = NativePairingPersistence.remember(state, mac(endpoint = "b".repeat(64)), team, reconnected)
        assertEquals(saved.origin, moved.origin)
        assertNull(moved.ticketRevision); assertFalse(state.has(NativeAttachTicketStore.KEY))
    }

    @Test fun localForgetRemovesOnlyOwningTeamCredentials() {
        val other = team.copy(teamId = "other")
        val state = state(mac(), mac(other))
        val first = install(state)
        val second = install(state, mac(other), payload("other-ticket"), other)
        NativePairingRecords.removeLocal(state, first.code, team)
        assertEquals(listOf(second), rows(state))
        assertFalse(state.toString().contains("synthetic-ticket"))
        assertEquals("other-ticket", NativeAttachTicketStore.read(state, other, second)!!.tokenFor("workspace.list", JSONObject(), 0))
    }

    @Test fun capturedForgetDoesNotEraseAReplacementTicket() {
        val state = state(mac())
        val first = install(state)
        val second = install(state, first, payload("replacement-ticket"))
        NativeComputerForgetLocal.remove(state, team, listOf(first))
        assertEquals(listOf(second), rows(state)); assertNotNull(NativeAttachTicketStore.read(state, team, second))
        NativeComputerForgetLocal.remove(state, team, listOf(second))
        assertTrue(rows(state).isEmpty()); assertFalse(state.has(NativeAttachTicketStore.KEY))
    }

    @Test fun missingOrMisboundCredentialNeverSilentlyBecomesTicketless() {
        for (change in listOf<(JSONObject, NativeCredentialStore.PairedMac) -> Unit>(
            { value, _ -> value.remove(NativeAttachTicketStore.KEY) },
            { value, row -> value.getJSONObject(NativeAttachTicketStore.KEY).getJSONObject(row.ticketRevision!!).put("code", "other") },
            { value, row -> value.getJSONObject(NativeAttachTicketStore.KEY).getJSONObject(row.ticketRevision!!)
                .getJSONObject("context").remove("expires") },
            { value, row -> value.getJSONObject(NativeAttachTicketStore.KEY).getJSONObject(row.ticketRevision!!)
                .getJSONObject("context").put("expires", "tomorrow") })) {
            val state = state(mac()); val saved = install(state)
            change(state, saved)
            assertTrue(runCatching { NativeAttachTicketStore.read(state, team, saved) }.isFailure)
            NativeAttachTicketStore.prune(state)
            assertFalse(state.has(NativeAttachTicketStore.KEY))
        }
    }

    @Test fun ticketlessRowsRemainReadableAndUnscopedRevisionIsRejected() {
        assertNull(NativeAttachTicketStore.read(state(mac()), team, mac()))
        val unscoped = NativeCredentialStore.PairedMac(mac().code, "device", "Mac", ticketRevision = UUID.randomUUID().toString())
        assertNull(NativePairingRecords.decode(NativePairingRecords.encode(unscoped)))
        assertNull(NativePairingRecords.decode(NativePairingRecords.encode(mac()).put("attach_ticket_revision", "not-a-uuid")))
    }

    @Test fun tokenlessSelectionAndExpiredTicketRemainReadableWithoutMutationAuthority() {
        for (payload in listOf(payload().put("auth_token", JSONObject.NULL), payload().put("expiresAt", "2020-01-01T00:00:00Z"))) {
            val state = state(mac()); val saved = install(state, payload = payload)
            val context = NativeAttachTicketStore.read(state, team, saved)!!
            assertEquals("workspace", context.workspaceId)
            assertFalse(context.allowsMacWorkspaceMutations(false, 1_800_000_000_000))
            assertNull(context.tokenFor("workspace.list", JSONObject(), 1_800_000_000_000))
        }
    }

    @Test fun renamedRecordIsKeptWhenInstallingCapturedTicket() {
        val state = state(mac().copy(name = "Renamed"))
        val saved = install(state)
        assertEquals("Renamed", saved.name); assertEquals(saved, rows(state).single())
    }

    @Test fun duplicateRevisionAndDetachedCredentialsArePruned() {
        val state = state(mac()); val saved = install(state)
        val contexts = state.getJSONObject(NativeAttachTicketStore.KEY)
        contexts.put(UUID.randomUUID().toString(), JSONObject(contexts.getJSONObject(saved.ticketRevision!!).toString()))
        NativeAttachTicketStore.prune(state)
        assertEquals(1, contexts.length())
        state.getJSONArray("pairings").put(NativePairingRecords.encode(saved))
        NativeAttachTicketStore.prune(state)
        assertFalse(state.has(NativeAttachTicketStore.KEY))
        assertTrue(runCatching { NativeAttachTicketStore.read(state, team, saved) }.isFailure)
    }

    @Test fun editedTailscaleGrantReconnectPreservesPublicLocatorTicketAndHistoryWithoutReauthorizingOldAddress() {
        val code = "cmux-ios://attach?v=2&ub=user&r=100.64.0.7:58465"
        val row = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(code, "device", "Mac", "default"), team)
        val state = state(row).put("computer_selection", row.origin).put("fixture_draft", "retained")
        val grants = TailscaleGrantStore({ state }, { it(state) })
        val pairing = PairingCodeParser.parse(code).getOrThrow() as PairingCode.Tailscale
        val original = TailscaleSavedGrant(UUID.randomUUID().toString(), "user", "team", TailscaleGrantStore.source(pairing),
            "device", "default", pairing.routes.single())
        grants.save(team, original) { true }
        val payload = payload().put("routes", JSONArray().put(JSONObject().put("id", "raw").put("kind", "tailscale")
            .put("endpoint", JSONObject().put("type", "host_port").put("host", "100.64.0.7").put("port", 58465))))
        val saved = install(state, row, payload)
        val replacement = original.copy(id = UUID.randomUUID().toString(), source = "b".repeat(64), route = PairingCode.Route("100.64.0.8", 58465))
        grants.save(team, replacement, replacing = original) { true }
        val incoming = NativeCredentialStore.PairedMac(code, "device", "Renamed Mac", "default")
        assertRejectedWithoutMutation(state) { NativePairingPersistence.remember(state, incoming, team) }
        val remembered = NativePairingPersistence.remember(state, incoming, team, expected = saved)
        assertEquals(code, remembered.code); assertEquals(saved.origin, remembered.origin)
        assertEquals(saved.ticketRevision, remembered.ticketRevision); assertEquals("Renamed Mac", remembered.name)
        assertEquals(saved.origin, state.getString("computer_selection")); assertEquals("retained", state.getString("fixture_draft"))
        assertNotNull(NativeAttachTicketStore.read(state, team, remembered))
        assertNull(grants.find(team, original.source))
        assertFalse(NativeSavedTailscaleRouteAdmission(remembered, replacement) { true }.coversPrimaryTicket(pairing))
        grants.removeRoute(team, NativeComputerTarget("device", "default", "Mac"), replacement) { true }
        assertRejectedWithoutMutation(state) { NativePairingPersistence.remember(state, incoming, team, expected = remembered) }
    }

    @Test fun tailscaleTicketsRequireCurrentGrantAndCoverEverySelectedPublicRoute() {
        val code = "cmux-ios://attach?v=2&ub=user&r=100.64.0.7:58465&r=100.64.0.8:58465"
        val row = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(code, "device", "Mac", "default"), team)
        val pairing = PairingCodeParser.parse(code).getOrThrow() as PairingCode.Tailscale
        val state = state(row)
        val payload = payload().put("routes", JSONArray().put(JSONObject().put("id", "tailscale").put("kind", "tailscale")
            .put("endpoint", JSONObject().put("type", "host_port").put("host", "100.64.0.7").put("port", 58465))))
        assertRejectedWithoutMutation(state) { install(state, row, payload) }
        val grant = TailscaleSavedGrant(UUID.randomUUID().toString(), team.userId, team.teamId, TailscaleGrantStore.source(pairing),
            row.deviceId, row.instanceTag, pairing.routes.first())
        TailscaleGrantStore({ state }, { it(state) }).save(team, grant) { true }
        assertRejectedWithoutMutation(state) { install(state, row, payload) }
        payload.getJSONArray("routes").put(JSONObject().put("id", "tailscale_2").put("kind", "tailscale")
            .put("endpoint", JSONObject().put("type", "host_port").put("host", "100.64.0.8").put("port", 58465)))
        val saved = install(state, row, payload)
        assertNotNull(NativeAttachTicketStore.read(state, team, saved))
        TailscaleGrantStore.removeForCode(state, code, team)
        assertTrue(runCatching { NativeAttachTicketStore.read(state, team, saved) }.isFailure)
    }
}
