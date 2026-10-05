package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeComputerForgetTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val mac = IrohV2Computer("record", "ab".repeat(32), "mac", "default", "Mac", emptyList())
    private val target = NativeComputerTarget.from(mac)
    private fun row(computer: IrohV2Computer = mac, owner: NativeTeamScope = team) =
        NativeCredentialStore.PairedMac(PairingCodeParser.computer(computer, owner), computer.deviceId, computer.name, computer.buildTag)
    private fun state(rows: List<NativeCredentialStore.PairedMac>) = JSONObject()
        .put("task_session", team.login).put("refresh_token", "fixture")
        .put("pairing_code", rows.first().code).put("computer_selection", rows.first().origin)
        .put("draft", "Keep my work").put("pairings", JSONArray().apply { rows.forEach {
            put(JSONObject().put("code", it.code).put("device_id", it.deviceId).put("instance_tag", it.instanceTag).put("name", it.name))
        } })

    @Test fun scopedTailscaleCleanupRemovesOnlyCapturedIdentityAfterConfirmedRevoke() {
        val raw = NativePairingRecords.scoped(row().copy(code = "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user"), team)
        val sibling = raw.copy(instanceTag = "nightly")
        val state = state(listOf(raw, sibling)).put("pairings", JSONArray().put(NativePairingRecords.encode(raw)).put(NativePairingRecords.encode(sibling)))
        assertEquals(listOf(raw), NativeComputerForgetLocal.capture(listOf(raw, sibling), team, target))
        NativeComputerForgetLocal.remove(state, team, listOf(raw))
        assertEquals(listOf(sibling), NativeComputerVisibility.saved(state))
        assertEquals("Keep my work", state.getString("draft"))
    }

    @Test fun confirmationOrdersCaptureStopRevokeAndCleanupAndDoesNotRepeatSuccess() = runBlocking<Unit> {
        val events = mutableListOf<String>(); val rows = listOf(row())
        val flow = NativeComputerForgetFlow({ true }, { events += "capture"; rows },
            { assertEquals(rows, it); events += "stop" }, { events += "revoke" },
            { assertEquals(rows, it); events += "cleanup" })
        assertTrue(flow.confirm()); assertTrue(flow.confirm())
        assertEquals(listOf("capture", "stop", "revoke", "cleanup"), events)
        assertTrue(flow.state.value.finished); assertFalse(flow.state.value.busy)
    }

    @Test fun duplicateSubmissionIsIgnoredWhileOriginalIsPending() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>(); var revokes = 0
        val flow = NativeComputerForgetFlow({ true }, { emptyList() }, {}, { revokes++; entered.complete(Unit); gate.await() }, {})
        val first = async { flow.confirm() }; entered.await()
        assertTrue(flow.state.value.busy); assertFalse(flow.confirm()); assertEquals(1, revokes)
        gate.complete(Unit); assertTrue(first.await())
    }

    @Test fun remoteFailureRetainsPairingAndExplicitRetryRecaptures() = runBlocking<Unit> {
        var captures = 0; var revokes = 0; var cleanups = 0
        val flow = NativeComputerForgetFlow({ true }, { captures++; emptyList() }, {},
            { if (++revokes == 1) error("lost acknowledgement") }, { cleanups++ })
        assertFalse(flow.confirm()); assertEquals(0, cleanups)
        assertEquals(NativeComputerForgetFlow.REMOTE_ERROR, flow.state.value.error)
        assertFalse(flow.state.value.remoteConfirmed)
        assertTrue(flow.confirm()); assertEquals(2, captures); assertEquals(2, revokes); assertEquals(1, cleanups)
    }

    @Test fun localRetryKeepsCapturedRowsAndNeverRevokesAgain() = runBlocking<Unit> {
        var revokes = 0; var captures = 0; var cleanups = 0; val rows = listOf(row())
        val flow = NativeComputerForgetFlow({ true }, { captures++; rows }, {}, { revokes++ }, {
            assertEquals(rows, it); if (++cleanups == 1) error("disk full")
        })
        assertFalse(flow.confirm()); assertTrue(flow.state.value.remoteConfirmed)
        assertEquals(NativeComputerForgetFlow.LOCAL_ERROR, flow.state.value.error)
        assertTrue(flow.confirm()); assertEquals(1, revokes); assertEquals(1, captures); assertEquals(2, cleanups)
    }

    @Test fun accountChangeDuringCaptureStopsBeforeAnyMutation() = runBlocking<Unit> {
        var permitted = true
        val flow = NativeComputerForgetFlow({ permitted }, { permitted = false; emptyList() },
            { error("must not stop another account") }, { error("must not revoke") }, { error("must not delete") })
        assertFalse(flow.confirm()); assertTrue(flow.state.value.error!!.contains("account or team changed"))
        assertFalse(flow.state.value.remoteConfirmed)
    }

    @Test fun cancellationReleasesGateWithoutClaimingRemoval() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); var block = true
        val flow = NativeComputerForgetFlow({ true }, { emptyList() }, {}, {
            if (block) { entered.complete(Unit); awaitCancellation() }
        }, {})
        val pending = launch { flow.confirm() }; entered.await(); pending.cancelAndJoin()
        assertFalse(flow.state.value.busy); assertFalse(flow.state.value.finished); assertNull(flow.state.value.error)
        block = false; assertTrue(flow.confirm())
    }

    @Test fun cleanupIsExactScopedAndPreservesDraftsTokensAndNewPairings() {
        val selected = row(); val sibling = row(mac.copy(buildTag = "debug"))
        val otherTeam = row(owner = team.copy(teamId = "other"))
        val otherUser = row(owner = team.copy(userId = "other"))
        val rotated = row(mac.copy(endpointId = "cd".repeat(32)))
        val legacy = NativeCredentialStore.PairedMac("cmux-ios://attach?v=2&r=100.100.1.1:58470", "mac", "Legacy", "default")
        val rows = listOf(selected, sibling, otherTeam, otherUser, legacy)
        val captured = NativeComputerForgetLocal.capture(rows, team, target)
        assertEquals(listOf(selected), captured)
        val saved = state(rows + rotated)
        NativeComputerForgetLocal.remove(saved, team, captured)
        val remaining = saved.getJSONArray("pairings")
        assertEquals(5, remaining.length())
        assertFalse((0 until remaining.length()).any { remaining.getJSONObject(it).getString("code") == selected.code })
        assertEquals("", saved.getString("pairing_code")); assertEquals("", saved.getString("computer_selection"))
        assertEquals("Keep my work", saved.getString("draft")); assertEquals("fixture", saved.getString("refresh_token"))
        NativeComputerForgetLocal.remove(saved, team, captured)
        assertEquals(5, saved.getJSONArray("pairings").length())
    }

    @Test fun repeatedCapturedRowsAreAllRemovedWithoutTouchingSibling() {
        val duplicated = listOf(row(), row(), row(mac.copy(buildTag = "debug")))
        val saved = state(duplicated)
        NativeComputerForgetLocal.remove(saved, team, NativeComputerForgetLocal.capture(duplicated, team, target))
        assertEquals(1, saved.getJSONArray("pairings").length())
        assertEquals("debug", saved.getJSONArray("pairings").getJSONObject(0).getString("instance_tag"))
    }

    @Test fun changedLoginFailsWithoutTouchingNewAccountOrSelection() {
        val saved = state(listOf(row())).put("task_session", "new-login")
        val before = saved.toString()
        assertTrue(runCatching { NativeComputerForgetLocal.remove(saved, team, listOf(row())) }.isFailure)
        assertEquals(before, saved.toString())
        val sibling = row(mac.copy(buildTag = "debug"))
        val sameAccount = state(listOf(sibling, row()))
        NativeComputerForgetLocal.remove(sameAccount, team, listOf(row()))
        assertEquals(sibling.code, sameAccount.getString("pairing_code"))
        assertEquals(sibling.origin, sameAccount.getString("computer_selection"))
    }

    @Test fun pendingUnsavedHandshakeIsStoppedOnlyForExactScopedTarget() {
        assertTrue(NativeComputerForgetLocal.ownsForeground(row().code, team, target, emptyList()))
        assertFalse(NativeComputerForgetLocal.ownsForeground(row(mac.copy(buildTag = "debug")).code, team, target, emptyList()))
        assertFalse(NativeComputerForgetLocal.ownsForeground(row(owner = team.copy(teamId = "other")).code, team, target, emptyList()))
        assertFalse(NativeComputerForgetLocal.ownsForeground("cmux-ios://attach?v=3&i=endpoint", team, target, emptyList()))
    }

    @Test fun uuidAliasesAreCapturedButOpaqueIdsKeepCase() {
        val id = "ABCDEF01-2345-6789-ABCD-0123456789AB"
        val upper = row(mac.copy(deviceId = id))
        assertEquals(listOf(upper), NativeComputerForgetLocal.capture(listOf(upper), team, target.copy(deviceId = id.lowercase())))
        assertTrue(NativeComputerForgetLocal.capture(listOf(row()), team, target.copy(deviceId = "MAC")).isEmpty())
    }

    @Test fun appearanceRemovalIsScopedToUuidAndBuildAndFailedSaveDoesNotPublish() {
        var disk: String? = null; var fail = false
        val store = NativeMacAppearanceStore({ disk }, { if (fail) error("disk full"); disk = it })
        val id = "ABCDEF01-2345-6789-ABCD-0123456789AB"
        val keys = listOf(NativeMacIdentity(id, "default"), NativeMacIdentity(id.lowercase(), "default"),
            NativeMacIdentity(id, "debug"), NativeMacIdentity("other", "default"))
        keys.forEach { key -> store.update(key, { true }) { NativeMacAppearance("Saved") } }
        val removal = target.copy(deviceId = id)
        fail = true
        assertTrue(runCatching { store.removeComputer(removal) { true } }.isFailure)
        assertEquals(4, store.state.value.values.size)
        fail = false
        assertTrue(runCatching { store.removeComputer(removal) { false } }.isFailure)
        store.removeComputer(removal) { true }
        assertEquals(keys.drop(2).toSet(), store.state.value.values.keys)
        assertEquals(store.state.value, NativeMacAppearanceStore({ disk }, {}).state.value)
    }
}
