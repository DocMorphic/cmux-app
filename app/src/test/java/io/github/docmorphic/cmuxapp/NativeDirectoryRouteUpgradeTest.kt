package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeDirectoryRouteUpgradeTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val raw = "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user"
    private val computer = IrohV2Computer("record", "a".repeat(64), "mac", "default", "Mac", emptyList())
    private val directory = NativeComputersState(team, ready = true, computers = listOf(computer))
    private fun saved() = NativeCredentialStore.PairedMac(raw, "mac", "Custom name", "default")
    private fun state(): JSONObject {
        val state = JSONObject().put("task_session", team.login).put("refresh_token", "fixture-refresh")
        val pairing = PairingCodeParser.parse(raw).getOrThrow() as PairingCode.Tailscale
        val grant = TailscaleSavedGrant(UUID.randomUUID().toString(), team.userId, team.teamId,
            TailscaleGrantStore.source(pairing), "mac", "default", pairing.routes.single())
        TailscaleGrantStore({ state }, { it(state) }).save(team, grant) { true }
        val row = NativePairingPersistence.remember(state, saved(), team)
        state.put("computer_selection", row.origin).put("fixture-draft", "keep")
        return state
    }
    private fun row(state: JSONObject) = NativeComputerVisibility.saved(state).single()

    private fun legacyState(): JSONObject {
        val state = state()
        val grants = TailscaleGrantStore({ state }, { it(state) })
        val grant = grants.find(team, TailscaleGrantStore.source(PairingCodeParser.parse(raw).getOrThrow() as PairingCode.Tailscale))!!
        grants.save(team, grant.copy(build = null)) { true }
        state.put("pairings", JSONArray().put(NativePairingRecords.encode(row(state).copy(instanceTag = null))))
        return state
    }

    @Test fun soleDirectoryBuildSuppliesProvisionalRouteThenAuthenticatedPromotionPreservesLegacyHistory() {
        val state = legacyState(); val before = row(state)
        val grants = state.getJSONArray("tailscale_grants_v1").toString()
        assertTrue(NativeDirectoryRouteUpgrade.retain(state, team, directory))
        val enriched = row(state)
        assertNull(enriched.instanceTag); assertEquals(before.origin, enriched.origin)
        assertEquals(before.code, enriched.code)
        val target = NativeComputerTarget.from(enriched, team)!!
        assertEquals("default", target.buildTag)
        assertEquals(grants, state.getJSONArray("tailscale_grants_v1").toString())
        assertEquals(enriched, NativePairingRecords.decode(NativePairingRecords.encode(enriched)))
        assertEquals(enriched, NativeDirectoryRouteUpgrade.refreshedSelection(before, enriched, team, TailscaleGrantStore({ state }, {})))
        val route = nativeSavedMacRoute(enriched, NativeMacConnectionMethod.IROH)
        assertFalse(route.usesPrimaryTicket); assertTrue(route.pairing is PairingCode.Iroh)
        val authenticated = NativePairingPersistence.remember(state, before.copy(code = route.code, instanceTag = "default"), team, enriched)
        assertEquals(before.origin, authenticated.origin); assertEquals("default", authenticated.instanceTag)
        assertEquals(1, NativeComputerVisibility.saved(state).size)
        assertEquals(before.origin, state.getString("computer_selection"))
    }

    @Test fun nativePreTagPairingCanReplaceItsStalePeerOnlyFromSoleScopedDirectoryAndLearnAfterAuthentication() {
        val old = NativeCredentialStore.PairedMac("cmux-ios://attach?v=3&i=old-peer&d=mac&ub=user&t=team", "mac", "Legacy Native")
        val state = legacyState().put("pairings", JSONArray().put(NativePairingRecords.encode(old)))
            .put("computer_selection", old.origin)
        assertTrue(NativeDirectoryRouteUpgrade.retain(state, team, directory))
        val enriched = row(state)
        assertEquals(old.code, enriched.code); assertNull(enriched.instanceTag)
        for (method in NativeMacConnectionMethod.entries) {
            val selected = nativeSavedMacRoute(enriched, method)
            assertEquals(computer.endpointId, (selected.pairing as PairingCode.Iroh).endpointId)
            assertFalse(selected.usesPrimaryTicket)
        }
        assertEquals("default", NativeComputerTarget.from(enriched, team)!!.buildTag)
        val verified = NativePairingPersistence.remember(state, enriched.copy(code = enriched.nativeRouteCode!!,
            nativeRouteCode = null, instanceTag = "default"), team, enriched)
        assertEquals(old.origin, verified.origin); assertEquals("default", verified.instanceTag)
        assertEquals(1, NativeComputerVisibility.saved(state).size)
    }

    @Test fun rawPrimaryReconnectObservesItsExactDiscoveredBuildAndLocalTailscaleRouteRevision() {
        val target = NativeComputerTarget.from(computer)
        val state = directory.copy(connectionKeys = mapOf(computer.endpointId to "native-route"))
        assertEquals("native-route", state.connectionKey(null, target))
        assertNull(state.connectionKey(null, target.copy(buildTag = "nightly")))
        assertNull(state.connectionKey(null))
        val local = state.copy(localConnectionKeys = mapOf(NativeMacIdentity("mac", "default") to "local-route"))
        assertEquals("local-route", local.connectionKey(null, target))
    }

    @Test fun foregroundReconnectKeyChangesForPersistedLocatorAndAccountButNotHostRename() {
        val state = legacyState(); val before = row(state)
        val first = nativeForegroundReconnectKey(directory, listOf(before), team, before.code)
        NativeDirectoryRouteUpgrade.retain(state, team, directory)
        val enriched = row(state)
        val second = nativeForegroundReconnectKey(directory, listOf(enriched), team, enriched.code)
        assertNotEquals(first, second)
        assertEquals(second, nativeForegroundReconnectKey(directory, listOf(enriched.copy(name = "Renamed")), team, enriched.code))
        assertNotEquals(second, nativeForegroundReconnectKey(directory, listOf(enriched), team.copy(generation = 2), enriched.code))
    }

    @Test fun missingOrAmbiguousBuildsDoNotChooseOrEraseLegacyNativeRoutes() {
        val state = legacyState(); val before = state.toString()
        for (computers in listOf(emptyList(), listOf(computer, computer.copy(buildTag = "nightly", endpointId = "b".repeat(64))),
            listOf(computer, computer.copy(recordId = "conflict"), computer.copy(buildTag = "nightly", endpointId = "b".repeat(64))))) {
            assertFalse(NativeDirectoryRouteUpgrade.retain(state, team, directory.copy(computers = computers)))
            assertEquals(before, state.toString())
        }
        assertTrue(NativeDirectoryRouteUpgrade.retain(state, team, directory.copy(computers = listOf(computer, computer))))
        val retained = state.toString()
        assertFalse(NativeDirectoryRouteUpgrade.retain(state, team, directory.copy(computers = emptyList())))
        assertEquals(retained, state.toString())
    }

    @Test fun provisionalIdentityRequiresFullScopeAndDoesNotSilentlyRetagOldTailscaleGrant() {
        val state = legacyState(); NativeDirectoryRouteUpgrade.retain(state, team, directory)
        val enriched = row(state); val grants = TailscaleGrantStore({ state }, {})
        assertEquals(listOf<String?>(null), NativeSavedTailscaleRoutes.candidates(enriched, team, grants).map { it.build })
        assertNull(NativePairingRecords.retainedNativeRoute(enriched.copy(nativeRouteCode = "cmux-ios://attach?v=3&i=peer&d=mac&b=default")))
        assertNull(NativeComputerTarget.from(enriched.copy(accountTeamId = "other"), team))
        assertNull(NativeComputerTarget.from(enriched.copy(code = raw.replace("ub=user", "ub=other")), team))
    }

    @Test fun independentlyAuthorizedTaggedGrantCanConnectProvisionalRowWithoutOldTicketAndLearnBuild() {
        val state = legacyState(); NativeDirectoryRouteUpgrade.retain(state, team, directory)
        val enriched = row(state); val grants = TailscaleGrantStore({ state }, { it(state) })
        val old = grants.find(team, TailscaleGrantStore.source(PairingCodeParser.parse(raw).getOrThrow() as PairingCode.Tailscale))!!
        val tagged = old.copy(id = UUID.randomUUID().toString(), build = "default")
        grants.save(team, tagged) { true }
        assertEquals(listOf(tagged), NativeSavedTailscaleRoutes.candidates(enriched, team, grants))
        val admission = NativeSavedTailscaleRouteAdmission(enriched, tagged) { true }
        val pairing = PairingCodeParser.parse(raw).getOrThrow() as PairingCode.Tailscale
        admission.requireBinding(pairing, team)
        assertFalse(admission.coversPrimaryTicket(pairing))
        val result = NativePairingPersistence.remember(state, saved(), team, enriched)
        assertEquals(enriched.origin, result.origin); assertEquals("default", result.instanceTag)
    }

    @Test fun retainsUniqueScopedIdentityWithoutReplacingRawTicketGrantsNamesOrHistory() {
        val state = state(); val before = row(state)
        val ticket = MobileAttachTicketCodec.decodeJson("""{"version":1,"workspaceID":"w","macDeviceID":"mac","macUserID":"user",
            "auth_token":"private-fixture","routes":[{"id":"ts","kind":"tailscale","endpoint":{"type":"host_port","host":"100.64.0.7","port":58465}}]}""").getOrThrow()
        val ticketRow = NativeAttachTicketStore.install(state, team, before, ticket, null)
        val grants = state.getJSONArray("tailscale_grants_v1").toString()
        assertTrue(NativeDirectoryRouteUpgrade.retain(state, team, directory))
        val after = row(state)
        assertEquals(ticketRow.copy(nativeRouteCode = PairingCodeParser.computer(computer, team)), after)
        assertEquals(grants, state.getJSONArray("tailscale_grants_v1").toString())
        assertEquals(before.origin, state.getString("computer_selection")); assertEquals("keep", state.getString("fixture-draft"))
        assertEquals("private-fixture", NativeAttachTicketStore.read(state, team, after)!!.tokenFor("workspace.list", JSONObject(), 0))
        assertFalse(NativeDirectoryRouteUpgrade.retain(state, team, directory))
        assertTrue(nativeSavedMacRoute(after, NativeMacConnectionMethod.IROH).pairing is PairingCode.Iroh)
        assertTrue(nativeSavedMacRoute(after, NativeMacConnectionMethod.DIRECT).pairing is PairingCode.Iroh)
        assertTrue(nativeSavedMacRoute(after, NativeMacConnectionMethod.TAILSCALE).pairing is PairingCode.Tailscale)
    }
    @Test fun unavailableEmptyOrConflictingDirectoryCannotDowngradeRetainedNativeIdentity() {
        val state = state(); NativeDirectoryRouteUpgrade.retain(state, team, directory)
        val retained = state.toString()
        for (snapshot in listOf(directory.copy(ready = false), directory.copy(computers = emptyList()),
            directory.copy(computers = listOf(computer, computer.copy(endpointId = "b".repeat(64)))))) {
            assertFalse(NativeDirectoryRouteUpgrade.retain(state, team, snapshot)); assertEquals(retained, state.toString())
        }
        val grants = TailscaleGrantStore({ state }, {})
        val grant = grants.find(team, TailscaleGrantStore.source(PairingCodeParser.parse(raw).getOrThrow() as PairingCode.Tailscale))!!
        assertTrue(NativeLegacyTailscalePolicy.hasNativeIdentity(grant, team, listOf(row(state)), grants))
        assertFalse(NativeLegacyTailscalePolicy.permits(NativeMacConnectionMethod.IROH, true))
    }
    @Test fun newPeerReplacesOnlyTheAlternativeAndDoesNotDuplicateTheSavedComputer() {
        val state = state(); NativeDirectoryRouteUpgrade.retain(state, team, directory)
        val before = row(state)
        val changed = computer.copy(endpointId = "b".repeat(64))
        assertTrue(NativeDirectoryRouteUpgrade.retain(state, team, directory.copy(computers = listOf(changed, changed))))
        val after = row(state)
        assertEquals(before.copy(nativeRouteCode = PairingCodeParser.computer(changed, team)), after)
        val list = NativeReconnectComputers.merge(listOf(NativeComputerListRow(after, after.name,
            NativeComputerPresence(false, null), NativeComputerRouteLabel(NativeMacConnectionMethod.IROH, null))), listOf(changed))
        assertEquals(1, list.saved.size); assertTrue(list.discovered.isEmpty())
    }
    @Test fun wrongOwnerBuildAndAmbiguousPeersCannotSupplyAnUpgrade() {
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("task_session", "other") }, { it.remove("refresh_token") },
            { it.put("pairings", JSONArray().put(NativePairingRecords.encode(row(it).copy(accountTeamId = "other")))) },
            { it.put("pairings", JSONArray().put(NativePairingRecords.encode(row(it).copy(instanceTag = "nightly")))) }
        )) {
            val state = state(); change(state); val original = state.toString()
            assertFalse(NativeDirectoryRouteUpgrade.retain(state, team, directory)); assertEquals(original, state.toString())
        }
        val state = state()
        assertFalse(NativeDirectoryRouteUpgrade.retain(state, team, directory.copy(account = team.copy(generation = 2))))
        assertFalse(NativeDirectoryRouteUpgrade.retain(state, team, directory.copy(computers = listOf(computer, computer.copy(recordId = "other")))))
    }
    @Test fun hiddenOrForgottenRowsAreNotRevivedAndPrimaryNativeRowsAreNotRewritten() {
        val state = state(); val captured = row(state)
        assertTrue(NativeComputerVisibility.setVisible(state, team.login, captured, false) { true })
        assertFalse(NativeDirectoryRouteUpgrade.retain(state, team, directory))
        assertTrue(NativeComputerVisibility.setVisible(state, team.login, captured, true) { true })
        assertTrue(NativeDirectoryRouteUpgrade.retain(state, team, directory))
        NativePairingRecords.removeLocal(state, raw, team)
        assertFalse(NativeDirectoryRouteUpgrade.retain(state, team, directory)); assertTrue(NativeComputerVisibility.saved(state).isEmpty())
        val native = NativePairingPersistence.remember(state,
            saved().copy(code = PairingCodeParser.computer(computer, team)), team)
        assertFalse(NativeDirectoryRouteUpgrade.retain(state, team, directory.copy(computers = listOf(computer.copy(endpointId = "b".repeat(64))))))
        assertEquals(native, row(state))
    }
    @Test fun historicalGrantOwnedRowKeepsItsOriginAndUnownedRowCannotBeClaimed() {
        val state = state(); val historical = saved()
        state.put("pairings", JSONArray().put(NativePairingRecords.encode(historical)))
        assertTrue(NativeDirectoryRouteUpgrade.retain(state, team, directory))
        assertEquals(historical.origin, row(state).origin)
        assertEquals(team.userId, row(state).accountUserId)
        val unowned = state(); unowned.remove("tailscale_grants_v1")
        unowned.put("pairings", JSONArray().put(NativePairingRecords.encode(historical)))
        assertFalse(NativeDirectoryRouteUpgrade.retain(unowned, team, directory))
    }
    @Test fun pendingSelectionCanAdoptOnlyNativeLocatorChangesWhileOtherCapturedActionsRetire() {
        val state = state(); val captured = row(state)
        NativeDirectoryRouteUpgrade.retain(state, team, directory)
        val upgraded = row(state)
        assertEquals(upgraded, NativeDirectoryRouteUpgrade.refreshedSelection(captured, upgraded))
        assertFalse(NativeComputerMenuPairing.isCurrent(captured, listOf(upgraded)))
        assertSame(captured, NativeDirectoryRouteUpgrade.refreshedSelection(captured, upgraded.copy(ticketRevision = UUID.randomUUID().toString())))
        assertSame(captured, NativeDirectoryRouteUpgrade.refreshedSelection(captured, upgraded.copy(name = "Changed")))
        assertSame(captured, NativeDirectoryRouteUpgrade.refreshedSelection(captured, upgraded.copy(accountTeamId = "other")))
        assertNull(NativeDirectoryRouteUpgrade.refreshedSelection(null, upgraded))
    }
    @Test fun historicalPendingSelectionAdoptsExplicitScopeOnlyWithItsOriginalGrantAuthority() {
        val state = state(); val historical = saved()
        state.put("pairings", JSONArray().put(NativePairingRecords.encode(historical)))
        NativeDirectoryRouteUpgrade.retain(state, team, directory)
        val upgraded = row(state); val grants = TailscaleGrantStore({ state }, {})
        assertEquals(upgraded, NativeDirectoryRouteUpgrade.refreshedSelection(historical, upgraded, team, grants))
        assertSame(historical, NativeDirectoryRouteUpgrade.refreshedSelection(historical, upgraded, team.copy(teamId = "other"), grants))
        state.remove("tailscale_grants_v1")
        assertSame(historical, NativeDirectoryRouteUpgrade.refreshedSelection(historical, upgraded, team, grants))
        state.put("tailscale_grants_v1", "damaged")
        assertSame(historical, NativeDirectoryRouteUpgrade.refreshedSelection(historical, upgraded, team, grants))
    }
    @Test fun laterAuthenticatedNativePromotionPreservesStableOrigin() {
        val state = state(); val original = row(state)
        NativeDirectoryRouteUpgrade.retain(state, team, directory)
        val enriched = row(state)
        val authenticated = NativePairingPersistence.remember(state,
            saved().copy(code = enriched.nativeRouteCode!!), team, expected = enriched)
        assertEquals(original.origin, authenticated.origin)
        assertEquals(enriched.nativeRouteCode, authenticated.code)
        assertNull(authenticated.nativeRouteCode)
    }
}
