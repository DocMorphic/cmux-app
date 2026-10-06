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
        .put("refresh_token", "fixture-refresh").put("pairings", JSONArray(rows.map(NativePairingRecords::encode)))
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

    private fun legacyNative(endpoint: String = "peer") = NativeCredentialStore.PairedMac(
        "cmux-ios://attach?v=3&i=$endpoint&d=mac&ub=user&t=team", "mac", "Legacy Mac")

    @Test fun backgroundUpgradePreservesAnotherSelectionAndRetargetsOnlyItsOwnLocator() {
        val old = legacyNative()
        for (selection in listOf(null, "other-computer", old.code)) {
            val state = state(old).put("computer_selection", "other-origin")
            selection?.let { state.put("pairing_code", it) }
            val result = NativePairingPersistence.refresh(state, native(), scope, old)
            assertEquals("other-origin", state.getString("computer_selection"))
            assertEquals(if (selection == old.code) result.code else selection, state.opt("pairing_code"))
            assertEquals(old.origin, result.origin)
        }
    }

    @Test fun authenticatedLegacyNativeReconnectLearnsBuildAndPreservesActualDraftAndNotificationHistory() {
        val legacy = legacyNative()
        val state = state(legacy).put("computer_selection", legacy.origin)
        val drafts = TaskDrafts(); val draftId = UUID.randomUUID().toString()
        val editor = drafts.begin(draftId, legacy.origin, legacy.name, "/tmp")
        drafts.edit(editor) { it.copy(prompt = "Keep legacy draft") }
        val ledgerState = JSONObject(); val ledger = NativeNotificationLedger(ledgerState)
        val notification = NativeNotification("n", "w", "s", "Ready", "Done", false)
        ledger.baseline(legacy.origin, emptyList())
        val destination = ledger.stage(legacy.origin, notification)
        ledger.acknowledge(legacy.origin, listOf(notification.id))
        val result = NativePairingPersistence.remember(state, legacy.copy(name = "Verified Mac", instanceTag = "default"), scope, legacy)
        assertEquals("default", result.instanceTag); assertEquals(legacy.origin, result.origin)
        assertEquals(legacy.code, result.code); assertEquals(scope.userId, result.accountUserId)
        assertEquals(1, codes(state).size); assertEquals(legacy.origin, state.getString("computer_selection"))
        val restored = TaskDrafts(drafts.saved())
        restored.begin(draftId, result.origin, result.name, "/tmp")
        assertEquals("Keep legacy draft", restored.state.value[draftId]?.prompt)
        val restarted = NativeNotificationLedger(JSONObject(ledgerState.toString()))
        assertTrue(restarted.prune(result.origins).isEmpty())
        assertEquals(destination, restarted.destination(destination.routeId))
        assertTrue(restarted.unseen(result.origin, listOf(notification)).isEmpty())
        assertEquals(result, NativePairingRecords.decode(state.getJSONArray("pairings").getJSONObject(0)))
        assertNotNull(NativeComputerTarget.from(result, scope))
    }

    @Test fun authenticatedNativePairingCanAdoptSoleGrantOwnedUntaggedRawHistoryWithoutRetaggingItsAddressGrant() {
        val legacy = incoming.copy(instanceTag = null)
        val state = state(legacy); val oldGrant = grant(state, legacy)
        val result = NativePairingPersistence.remember(state, native(), scope)
        assertEquals(legacy.origin, result.origin); assertEquals(native().code, result.code)
        assertEquals("default", result.instanceTag); assertEquals(1, codes(state).size)
        assertEquals(oldGrant, TailscaleGrantStore({ state }, {}).find(scope, oldGrant.source))
    }

    @Test fun explicitLegacySelectionPreservesOtherLegacyRowsAndTaggedSiblingBuilds() {
        val selected = legacyNative("selected"); val otherLegacy = legacyNative("other")
        val sibling = native(build = "nightly", endpoint = "nightly")
        val state = state(otherLegacy, sibling, selected)
        val before = state.toString()
        assertThrows(IllegalStateException::class.java) { NativePairingPersistence.remember(state, native(), scope) }
        assertEquals(before, state.toString())
        val result = NativePairingPersistence.remember(state, native(), scope, selected)
        assertEquals(selected.origin, result.origin)
        assertEquals(listOf(otherLegacy.code, sibling.code, result.code), codes(state))
        assertFalse(result.ownsOrigin(sibling.origin)); assertFalse(result.ownsOrigin(otherLegacy.origin))
    }

    @Test fun coalescingWithKnownExactBuildRetainsBothOriginsButDoesNotAdoptOnAnOrdinaryTaggedReconnect() {
        val legacy = legacyNative(); val known = NativePairingRecords.scoped(native(), scope)
        val state = state(legacy, known)
        val unchanged = NativePairingPersistence.remember(state, native(), scope, known)
        assertEquals(2, codes(state).size); assertFalse(unchanged.ownsOrigin(legacy.origin))
        val result = NativePairingPersistence.remember(state, native(), scope, legacy)
        assertEquals(known.origin, result.origin)
        assertEquals(setOf(known.origin, legacy.origin), result.origins)
        assertEquals(1, codes(state).size)
    }

    @Test fun hiddenOrWrongOwnerLegacyRecordCannotDonateHistoryOrBeRevived() {
        val legacy = legacyNative()
        val hidden = state(legacy)
        assertTrue(NativeComputerVisibility.setVisible(hidden, scope.login, legacy, false) { true })
        val before = hidden.toString()
        assertThrows(IllegalStateException::class.java) { NativePairingPersistence.remember(hidden, native(), scope) }
        assertEquals(before, hidden.toString())
        for (changed in listOf(legacy.copy(code = legacy.code.replace("t=team", "t=other")),
            legacy.copy(code = "cmux-ios://attach?v=3&i=peer&d=mac"))) {
            val state = state(changed); val previous = state.toString()
            assertThrows(IllegalStateException::class.java) { NativePairingPersistence.remember(state, native(), scope, changed) }
            assertEquals(previous, state.toString())
        }
    }

    @Test fun verifiedLegacyRawBuildUpdatesOnlyItsExactGrantAndSavedRecordThenChangesForegroundReconnectKey() {
        for (scoped in listOf(false, true)) {
            val old = incoming.copy(instanceTag = null).let { if (scoped) NativePairingRecords.scoped(it, scope) else it }
            val state = state(old).put("computer_selection", old.origin)
            val oldGrant = grant(state, old)
            val beforeKey = nativeForegroundReconnectKey(NativeComputersState(scope), listOf(old), scope, old.code)
            val result = NativePairingPersistence.remember(state, incoming, scope, expected = old)
            val savedGrant = TailscaleGrantStore({ state }, {}).find(scope, oldGrant.source)!!
            assertEquals(oldGrant.copy(build = "default"), savedGrant)
            assertEquals(old.origin, result.origin); assertEquals(old.code, result.code)
            assertEquals("default", result.instanceTag); assertEquals(1, codes(state).size)
            assertEquals(old.origin, state.getString("computer_selection"))
            assertNotEquals(beforeKey, nativeForegroundReconnectKey(NativeComputersState(scope), listOf(result), scope, result.code))
            assertTrue(NativePairingRecords.usable(result, scope, TailscaleGrantStore({ state }, {})))
        }
    }

    @Test fun failedLegacyRawUpgradeDoesNotMutateEitherGrantOrRecord() {
        val old = NativePairingRecords.scoped(incoming.copy(instanceTag = null), scope)
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("task_session", "other") },
            { NativeComputerVisibility.setVisible(it, scope.login, old, false) { true } },
            { it.remove("tailscale_grants_v1") },
            { it.put("pairings", JSONArray()) },
            { it.put("pairings", JSONArray().put(NativePairingRecords.encode(old.copy(name = "Renamed", code = "other")))) }
        )) {
            val state = state(old); grant(state, old); change(state); val before = state.toString()
            assertThrows(Exception::class.java) { NativePairingPersistence.remember(state, incoming, scope, expected = old) }
            assertEquals(before, state.toString())
        }
    }

    @Test fun reconnectWriteRejectsForgottenReplacedOrAmbiguousPairingInsideTransaction() {
        val expected = native()
        for (scoped in listOf(true, false)) {
            for (rows in listOf(emptyList(), listOf(expected.copy(code = native(endpoint = "replacement").code)), listOf(expected, expected))) {
                val value = state(*rows.toTypedArray())
                val before = value.toString()
                assertThrows(IllegalStateException::class.java) {
                    NativePairingPersistence.remember(value, expected, if (scoped) scope else null, expected)
                }
                assertEquals(before, value.toString())
            }
        }
        val value = state(expected.copy(name = "Renamed"))
        assertEquals(expected.code, NativePairingPersistence.remember(value, expected.copy(name = "Host name"), scope, expected).code)
    }

    @Test fun standaloneQrUpgradeRetainsDraftNotificationAndSelectionOrigin() {
        val state = state(); grant(state)
        val first = NativePairingPersistence.remember(state, incoming, scope)
        assertNotEquals(incoming.origin, first.origin)
        state.put("computer_selection", first.origin)
        val drafts = TaskDrafts()
        val draftId = UUID.randomUUID().toString()
        val editor = drafts.begin(draftId, first.origin, first.name, "/tmp")
        drafts.edit(editor) { it.copy(prompt = "Preserved task") }
        val ledgerState = JSONObject(); val ledger = NativeNotificationLedger(ledgerState)
        val notification = NativeNotification("n", "w", "s", "Ready", "Done", false)
        ledger.baseline(first.origin, emptyList())
        val destination = ledger.stage(first.origin, notification)
        ledger.acknowledge(first.origin, listOf(notification.id))
        val upgraded = NativePairingPersistence.remember(state, native(), scope)
        assertEquals(first.origin, upgraded.origin)
        val restored = TaskDrafts(drafts.saved())
        restored.begin(draftId, upgraded.origin, upgraded.name, "/tmp")
        assertEquals("Preserved task", restored.state.value[draftId]?.prompt)
        val restartedLedger = NativeNotificationLedger(JSONObject(ledgerState.toString()))
        assertTrue(restartedLedger.prune(setOf(upgraded.origin)).isEmpty())
        assertEquals(destination, restartedLedger.destination(destination.routeId))
        assertEquals(destination.routeId, restartedLedger.stage(upgraded.origin, notification).routeId)
        assertTrue(restartedLedger.unseen(upgraded.origin, listOf(notification)).isEmpty())
        assertEquals(native().code, upgraded.code)
        assertEquals(first.origin, state.getString("computer_selection"))
        assertEquals(listOf(upgraded.code), codes(state))
        val reloaded = NativePairingRecords.decode(state.getJSONArray("pairings").getJSONObject(0))!!
        assertEquals(upgraded, reloaded)
        assertEquals(scope.userId to scope.teamId, NativePairingRecords.owner(reloaded, TailscaleGrantStore({ state }, { error("read only") })))
        val reattached = NativePairingPersistence.remember(state, incoming, scope)
        assertEquals(upgraded, reattached)
    }

    @Test fun historicalQrWithOneGrantOwnerRetainsItsExistingOriginOnUpgrade() {
        val state = state(incoming); grant(state)
        val upgraded = NativePairingPersistence.remember(state, native(), scope)
        assertEquals(incoming.origin, upgraded.origin)
        assertEquals(native().code, upgraded.code)
        assertEquals(1, codes(state).size)
    }

    @Test fun sameQrSavedByTwoTeamsHasSeparateRecordsOriginsAndRemoval() {
        val other = scope.copy(teamId = "other")
        val state = state(); grant(state)
        val a = NativePairingPersistence.remember(state, incoming, scope)
        grant(state, team = other)
        val b = NativePairingPersistence.remember(state, incoming, other)
        assertNotEquals(a.origin, b.origin); assertEquals(2, codes(state).size)
        val grants = TailscaleGrantStore({ state }, { error("read only") })
        assertTrue(NativePairingRecords.usable(a, scope, grants))
        assertFalse(NativePairingRecords.usable(a, other, grants))
        assertTrue(NativePairingRecords.usable(b, other, grants))
        state.put("computer_selection", a.origin)
        NativePairingRecords.removeLocal(state, qr, scope)
        val rows = state.getJSONArray("pairings")
        assertEquals(1, rows.length()); assertEquals(b, NativePairingRecords.decode(rows.getJSONObject(0)))
        assertNull(grants.find(scope, TailscaleGrantStore.source(PairingCodeParser.parse(qr).getOrThrow() as PairingCode.Tailscale)))
        assertTrue(NativePairingRecords.usable(b, other, grants))
        assertEquals("", state.getString("computer_selection"))
    }

    @Test fun ambiguousHistoricalQrDoesNotDonateItsOriginToAnotherOwner() {
        val state = state(incoming); grant(state); grant(state, team = scope.copy(teamId = "other"))
        val grants = TailscaleGrantStore({ state }, { error("read only") })
        assertNull(NativePairingRecords.owner(incoming, grants))
        assertFalse(NativePairingRecords.usable(incoming, scope, grants))
        val scoped = NativePairingPersistence.remember(state, incoming, scope)
        assertNotEquals(incoming.origin, scoped.origin)
        assertEquals(2, codes(state).size)
    }

    @Test fun invalidOwnerMetadataCannotLoadOrRedirectAStoredOrigin() {
        for (item in listOf(
            NativePairingRecords.encode(incoming).put("owner_user", "user"),
            NativePairingRecords.encode(incoming).put("stable_origin", "a".repeat(64)),
            NativePairingRecords.encode(NativePairingRecords.scoped(incoming, scope)).put("stable_origin", "bad")
        )) assertNull(NativePairingRecords.decode(item))
        val grants = TailscaleGrantStore({ state() }, { error("read only") })
        assertNull(NativePairingRecords.owner(native().copy(accountUserId = "other", accountTeamId = scope.teamId), grants))
    }

    @Test fun changedGrantTargetDoesNotAuthorizeAStoredRowFromItsOldMac() {
        val state = state(); grant(state)
        val stored = NativePairingPersistence.remember(state, incoming, scope)
        grant(state, incoming.copy(deviceId = "different-mac"))
        assertFalse(NativePairingRecords.usable(stored, scope, TailscaleGrantStore({ state }, { error("read only") })))
    }

    @Test fun authenticatedQrPreservesNativeIdentityNameOriginAndSelection() {
        val native = native(); val sibling = native(build = "debug", endpoint = "other-peer")
        val state = state(native, sibling).put("computer_selection", native.origin)
        val grant = grant(state)
        val result = NativePairingPersistence.remember(state, incoming, scope)
        assertEquals(native.code, result.code); assertEquals(native.name, result.name); assertEquals(native.origin, result.origin)
        assertEquals(scope.userId, result.accountUserId); assertEquals(scope.teamId, result.accountTeamId)
        assertEquals(listOf(native.code, sibling.code), codes(state))
        assertEquals(native.code, state.getString("pairing_code"))
        assertEquals(native.origin, state.getString("computer_selection"))
        assertEquals(grant, TailscaleGrantStore({ state }, { error("read only") }).find(scope, grant.source))
    }

    @Test fun uuidAliasesPreserveNativePairingAndOpaqueIdsRemainCaseSensitive() {
        val device = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
        val native = native(device = device)
        val row = incoming.copy(deviceId = device.lowercase())
        val state = state(native); grant(state, row)
        assertEquals(native.origin, NativePairingPersistence.remember(state, row, scope).origin)
        val opaque = state(native(device = "MAC")); grant(opaque)
        assertEquals(incoming.code, NativePairingPersistence.remember(opaque, incoming, scope).code)
        assertEquals(2, codes(opaque).size)
    }

    @Test fun anotherAccountOrTeamNativeRowSurvivesQrPersistence() {
        for (owner in listOf(scope.copy(userId = "other"), scope.copy(teamId = "other"))) {
            val other = native(owner)
            val state = state(other); grant(state)
            assertEquals(incoming.code, NativePairingPersistence.remember(state, incoming, scope).code)
            assertEquals(listOf(other.code, incoming.code), codes(state))
        }
    }

    @Test fun exactBuildIsRequiredAndUntaggedQrCannotReplaceTaggedIdentity() {
        val native = native()
        for (build in listOf("debug", null)) {
            val row = incoming.copy(instanceTag = build)
            val state = state(native); grant(state, row)
            assertEquals(row.code, NativePairingPersistence.remember(state, row, scope).code)
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
        assertEquals(current.code, NativePairingPersistence.remember(state, current, scope).code)
        assertEquals(listOf(other.code, current.code), codes(state))
    }

    @Test fun existingStandaloneQrRowStillUpdatesWithoutCreatingDuplicates() {
        val state = state(incoming.copy(name = "Before")); grant(state)
        assertEquals(incoming.code, NativePairingPersistence.remember(state, incoming, scope).code)
        assertEquals(listOf(qr), codes(state))
        assertEquals(incoming.name, state.getJSONArray("pairings").getJSONObject(0).getString("name"))
    }
    @Test fun verifiedNativeReconnectCoalescesExactOwnedRowsAndPreservesAllDraftOrigins() {
        val first = native(endpoint = "old-peer")
        val second = native(endpoint = "other-peer")
        val sibling = native(build = "debug")
        val other = native(scope.copy(teamId = "another-team"))
        val state = state(sibling, first, other, second).put("computer_selection", second.origin)
        val drafts = TaskDrafts()
        val ids = listOf(first, second).map { row ->
            UUID.randomUUID().toString().also { id ->
                val editor = drafts.begin(id, row.origin, row.name, "/tmp")
                drafts.edit(editor) { it.copy(prompt = "Keep " + row.origin, groupId = "group") }
            }
        }
        val result = NativePairingPersistence.remember(state, native(endpoint = "verified-peer"), scope)
        assertEquals(listOf(sibling.code, result.code, other.code), codes(state))
        assertEquals(first.origin, result.origin)
        assertEquals(setOf(first.origin, second.origin), result.origins)
        assertTrue(result.ownsOrigin(state.getString("computer_selection")))
        assertFalse(result.ownsOrigin(sibling.origin)); assertFalse(result.ownsOrigin(other.origin))
        val restored = TaskDrafts(drafts.saved())
        ids.forEach { id ->
            val draft = restored.state.value.getValue(id)
            assertTrue(result.ownsOrigin(draft.origin))
            restored.begin(id, draft.origin, result.name, "/tmp")
            assertEquals("Keep " + draft.origin, draft.prompt); assertEquals("group", draft.groupId)
        }
        val reloaded = NativePairingRecords.decode(state.getJSONArray("pairings").getJSONObject(1))!!
        assertEquals(result, reloaded)
        val newer = NativePairingPersistence.remember(state, native(endpoint = "newer-peer"), scope)
        assertEquals(result.origins, newer.origins)
        NativePairingRecords.removeLocal(state, newer.code, scope)
        assertEquals("", state.getString("computer_selection"))
        assertEquals(listOf(sibling.code, other.code), codes(state))
    }

    @Test fun qrCanConsolidateItsOldRowsWhileKeepingSingleNativeAuthority() {
        val native = native()
        val state = state(incoming, native); grant(state)
        val result = NativePairingPersistence.remember(state, incoming, scope)
        assertEquals(native.code, result.code); assertEquals(native.name, result.name)
        assertEquals(setOf(incoming.origin, native.origin), result.origins)
        assertEquals(listOf(native.code), codes(state))
    }

    @Test fun originCollisionWithDifferentOwnerRejectsWithoutMutation() {
        val current = NativePairingRecords.scoped(native(), scope)
        val other = NativePairingRecords.scoped(native(scope.copy(teamId = "other")), scope.copy(teamId = "other"))
            .copy(previousOrigins = setOf(current.origin))
        val state = state(current, other); val before = state.toString()
        assertTrue(runCatching { NativePairingPersistence.remember(state, native(), scope) }.isFailure)
        assertEquals(before, state.toString())
    }

    @Test fun aliasMetadataNeedsCompleteOwnerAndValidOrigins() {
        val scoped = NativePairingRecords.scoped(native(), scope)
        for (raw in listOf(
            NativePairingRecords.encode(native()).put("previous_origins", JSONArray(listOf(scoped.origin))),
            NativePairingRecords.encode(scoped).put("previous_origins", JSONArray(listOf("bad"))),
            NativePairingRecords.encode(scoped).put("previous_origins", JSONArray(listOf(42))),
            NativePairingRecords.encode(scoped).put("previous_origins", "not-an-array")
        )) assertNull(NativePairingRecords.decode(raw))
    }

}
