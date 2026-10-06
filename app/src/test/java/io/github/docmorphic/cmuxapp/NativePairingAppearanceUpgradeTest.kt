package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePairingAppearanceUpgradeTest {
    private val owner = NativeTeamScope("login", "user", "team", 1)
    private val legacy = NativeCredentialStore.PairedMac("cmux-ios://attach?v=3&i=peer&d=mac&ub=user&t=team", "mac", "Host")
    private val customization = NativeMacAppearance("Studio", "#ABCDEF", "🚀")

    private inner class Fixture(vararg rows: NativeCredentialStore.PairedMac) {
        var credentials = JSONObject().put("task_session", owner.login).put("refresh_token", "fixture")
            .put("pairings", JSONArray(rows.map(NativePairingRecords::encode)))
        var disk: String? = null
        var failAppearance = false
        var failCredentials = false
        var allowed = true
        var appearance = reopen()
        fun pending() = NativePairingAppearanceUpgrades.pending(credentials, owner)
        fun reopen() = NativeMacAppearanceStore({ disk }, { if (failAppearance) error("Disk full"); disk = it }, {
            pending().filter { NativePairingAppearanceUpgrades.current(credentials, it) }
        })
        fun update(change: (JSONObject) -> Unit) {
            val next = JSONObject(credentials.toString()); change(next)
            if (failCredentials) error("Credential write failed")
            credentials = next
        }
        fun upgrade(expected: NativeCredentialStore.PairedMac = legacy, build: String = "default") {
            update { NativePairingPersistence.remember(it, expected.copy(instanceTag = build), owner, expected) }
        }
        fun seed(row: NativeCredentialStore.PairedMac = legacy, value: NativeMacAppearance = customization) {
            appearance.update(NativeMacIdentity(row.deviceId, row.instanceTag), { true }) { value }
        }
        fun reconcile() = NativePairingAppearanceUpgrades.reconcile(owner, { credentials }, ::update, appearance) { allowed }
    }

    @Test fun authenticatedUpgradeCarriesAllFieldsAcrossBothStoreRestarts() {
        val f = Fixture(legacy); f.seed(); f.upgrade()
        assertEquals(1, f.pending().size)
        f.credentials = JSONObject(f.credentials.toString()); f.appearance = f.reopen(); f.reconcile()
        val restarted = f.reopen().state.value
        assertEquals(customization, restarted.get("mac", "default"))
        assertEquals(NativeMacAppearance(), restarted.get("mac", null))
        assertTrue(f.pending().isEmpty())
        assertEquals(legacy.origin, NativeComputerVisibility.saved(f.credentials).single().origin)
        val disk = f.disk; f.reconcile(); assertEquals(disk, f.disk)
    }

    @Test fun exactTaggedRecordWinsEvenWhenItsAppearanceWasResetToDefaults() {
        for (existing in listOf(NativeMacAppearance(), NativeMacAppearance("Tagged", "palette:2", "terminal"))) {
            val tagged = legacy.copy(code = "cmux-ios://attach?v=3&i=tagged&d=mac&b=default&ub=user&t=team", instanceTag = "default")
            val f = Fixture(legacy, tagged); f.seed(); f.seed(tagged, existing); f.upgrade()
            assertFalse(f.pending().single().inherit)
            f.reconcile()
            assertEquals(existing, f.appearance.state.value.get("mac", "default"))
            assertEquals(NativeMacAppearance(), f.appearance.state.value.get("mac", null))
        }
    }

    @Test fun failedAcknowledgementCannotResurrectCustomizationAfterUserReset() {
        val f = Fixture(legacy); f.seed(); f.upgrade(); f.failCredentials = true
        assertThrows(IllegalStateException::class.java) { f.reconcile() }
        assertEquals(customization, f.appearance.state.value.get("mac", "default"))
        assertEquals(1, f.pending().size)
        f.appearance = f.reopen()
        f.appearance.update(NativeMacIdentity("mac", "default"), { true }) { NativeMacAppearance() }
        f.failCredentials = false; f.appearance = f.reopen(); f.reconcile()
        assertEquals(NativeMacAppearance(), f.appearance.state.value.get("mac", "default"))
        assertEquals("[]", f.disk); assertTrue(f.pending().isEmpty())
    }

    @Test fun editingBeforeWorkerRunsInheritsUntouchedFieldsAndExplicitResetStaysReset() {
        for (reset in listOf(false, true)) {
            val f = Fixture(legacy); f.seed(); f.upgrade()
            f.appearance.update(NativeMacIdentity("mac", "default"), { true }) {
                if (reset) NativeMacAppearance() else it.copy(color = "palette:3")
            }
            f.reconcile()
            assertEquals(if (reset) NativeMacAppearance() else customization.copy(color = "palette:3"),
                f.reopen().state.value.get("mac", "default"))
        }
    }

    @Test fun failedAppearanceWriteLeavesOriginalAndPendingWorkForRetry() {
        val f = Fixture(legacy); f.seed(); f.upgrade(); val original = f.disk
        f.failAppearance = true
        assertThrows(IllegalStateException::class.java) { f.reconcile() }
        assertEquals(original, f.disk); assertEquals(1, f.pending().size)
        assertEquals(customization, f.appearance.state.value.get("mac", null))
        f.failAppearance = false; f.appearance = f.reopen(); f.reconcile()
        assertEquals(customization, f.appearance.state.value.get("mac", "default"))
    }

    @Test fun failedPairingCommitCannotStartAppearanceMigration() {
        val f = Fixture(legacy); f.seed(); val disk = f.disk
        f.failCredentials = true
        assertThrows(IllegalStateException::class.java) { f.upgrade() }
        f.failCredentials = false; f.reconcile()
        assertEquals(disk, f.disk); assertTrue(f.pending().isEmpty())
        assertEquals(legacy, NativeComputerVisibility.saved(f.credentials).single())
    }

    @Test fun successfulReadAfterTransientFailureClearsErrorWithoutWritingOrChangingAppearance() {
        val f = Fixture(legacy); f.seed(); val before = f.disk
        f.appearance.reportUpgradeFailure(); assertTrue(f.appearance.state.value.error)
        f.reconcile()
        assertFalse(f.appearance.state.value.error); assertEquals(before, f.disk)
        assertEquals(customization, f.appearance.state.value.get("mac", null))
    }

    @Test fun siblingBuildAndOtherOwnerAreNeverChosenForAppearanceAdoption() {
        val sibling = legacy.copy(code = "cmux-ios://attach?v=3&i=nightly&d=mac&b=nightly&ub=user&t=team", instanceTag = "nightly")
        val f = Fixture(legacy, sibling); f.seed(); f.seed(sibling, NativeMacAppearance("Nightly")); f.upgrade()
        assertTrue(NativePairingAppearanceUpgrades.pending(f.credentials, owner.copy(teamId = "other")).isEmpty())
        val disk = f.disk; f.allowed = false; f.reconcile(); assertEquals(disk, f.disk)
        f.allowed = true; f.reconcile()
        assertEquals("Nightly", f.appearance.state.value.get("mac", "nightly").name)
        assertEquals(customization, f.appearance.state.value.get("mac", "default"))
    }

    @Test fun multipleLegacyRecordsDoNotDonateAmbiguousAppearance() {
        val other = legacy.copy(code = legacy.code.replace("i=peer", "i=other"))
        val f = Fixture(legacy, other); f.seed(); f.upgrade(); f.reconcile()
        assertTrue(f.pending().isEmpty())
        assertEquals(customization, f.appearance.state.value.get("mac", null))
        assertEquals(NativeMacAppearance(), f.appearance.state.value.get("mac", "default"))
    }

    @Test fun newlySavedLegacyIdentityPreventsQueuedMoveFromConsumingItsAppearance() {
        val f = Fixture(legacy); f.seed(); f.upgrade()
        f.credentials.getJSONArray("pairings").put(NativePairingRecords.encode(legacy.copy(code = legacy.code.replace("i=peer", "i=new"))))
        f.reconcile()
        assertEquals(customization, f.appearance.state.value.get("mac", null))
        assertEquals(NativeMacAppearance(), f.appearance.state.value.get("mac", "default"))
    }

    @Test fun forgetDisarmsPendingMigrationBeforeAppearanceRemoval() {
        val f = Fixture(legacy); f.seed(); f.upgrade(); val move = f.pending().single()
        val target = NativeComputerTarget("mac", "default", "Host")
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            f.appearance.adoptLegacy(move) {
                if (++checks == 2) {
                    NativePairingAppearanceUpgrades.discard(f.credentials, owner, target)
                    f.appearance.removeComputer(target, { true }, { true })
                    false
                } else true
            }
        }
        f.reconcile(); assertEquals("[]", f.disk); assertTrue(f.pending().isEmpty())
    }

    @Test fun canonicalUuidSpellingRetainsCustomizationWithoutAliasingNamedDeviceIds() {
        val id = "123E4567-E89B-12D3-A456-426614174000"
        val row = legacy.copy(deviceId = id, code = legacy.code.replace("d=mac", "d=$id"))
        val f = Fixture(row); f.seed(row)
        f.update { NativePairingPersistence.remember(it, row.copy(deviceId = id.lowercase(), instanceTag = "default"), owner, row) }
        f.reconcile()
        assertEquals(customization, f.appearance.state.value.get(id, "default"))
        assertEquals(customization, f.appearance.state.value.get(id.lowercase(), "default"))
        assertEquals(NativeMacAppearance(), f.appearance.state.value.get(id, null))
        f.appearance.update(NativeMacIdentity(id, "default"), { true }) { it.copy(name = "Renamed") }
        assertEquals(customization.copy(name = "Renamed"), f.reopen().state.value.get(id.lowercase(), "default"))
        assertEquals(1, f.reopen().state.value.values.size)
    }
}
