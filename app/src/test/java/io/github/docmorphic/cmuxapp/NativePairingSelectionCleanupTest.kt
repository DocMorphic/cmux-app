package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativePairingSelectionCleanupTest {
    private val team = NativeTeamScope("login", "user", "original", 1)
    private val code = "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user"
    private val original = NativePairingRecords.scoped(
        NativeCredentialStore.PairedMac(code, "device", "Original", "default"), team)
    private val other = NativePairingRecords.scoped(original, team.copy(teamId = "picker"))
    private val operations: List<Pair<String, (JSONObject) -> Unit>> = listOf(
        "forget" to { state -> NativeComputerForgetLocal.remove(state, team, listOf(original)) },
        "local" to { state -> NativeLocalPairingRemoval("login", team, original, code).remove(state) { true } },
        "scoped code" to { state -> NativePairingRecords.removeLocal(state, code, team) },
        "hide" to { state -> assertTrue(NativeComputerVisibility.setVisible(state, "login", original, false) { true }) })

    private fun state(selection: String) = JSONObject().put("task_session", "login")
        .put("refresh_token", "fixture").put("pairing_code", code).put("computer_selection", selection)
        .put("pairings", JSONArray(listOf(original, other).map(NativePairingRecords::encode)))

    @Test fun selectedOtherTeamKeepsSameLocatorThroughCleanupAndStorageReload() {
        operations.forEach { (name, remove) ->
            val saved = state(other.origin)
            remove(saved)
            val restored = JSONObject(saved.toString())
            assertEquals(name, code, restored.getString("pairing_code"))
            assertEquals(name, other.origin, restored.getString("computer_selection"))
            assertTrue(name, NativeComputerVisibility.saved(restored).any { it == other })
            assertFalse(name, NativeComputerVisibility.isHidden(restored, other))
        }
    }

    @Test fun selectedAffectedRecordClearsBothEvenWhenAnotherTeamHasSameLocator() {
        operations.forEach { (name, remove) ->
            val saved = state(original.origin)
            remove(saved)
            assertEquals(name, "", saved.getString("pairing_code"))
            assertEquals(name, "", saved.getString("computer_selection"))
        }
    }

    @Test fun noSelectedRecordStillClearsLegacyLocator() {
        operations.forEach { (name, remove) ->
            val saved = state("")
            remove(saved)
            assertEquals(name, "", saved.getString("pairing_code"))
        }
    }

    @Test fun staleSelectionCannotKeepAnAffectedLocator() {
        operations.forEach { (name, remove) ->
            val saved = state("missing-origin")
            remove(saved)
            assertEquals(name, "", saved.getString("pairing_code"))
            assertEquals(name, "missing-origin", saved.getString("computer_selection"))
        }
    }

    @Test fun unrelatedSelectedCodeSurvives() {
        operations.forEach { (name, remove) ->
            val saved = state(other.origin).put("pairing_code", "another-code")
            remove(saved)
            assertEquals(name, "another-code", saved.getString("pairing_code"))
            assertEquals(name, other.origin, saved.getString("computer_selection"))
        }
    }

    @Test fun hidingAnotherTeamCannotRetireLiveOrPendingForeground() {
        val rows = listOf(original, other)
        val hidden = setOf(other.origin)
        assertFalse(NativeComputerVisibility.hidesForeground(hidden, rows, original, other, code, true, other.origin))
        assertFalse(NativeComputerVisibility.hidesForeground(hidden, rows, other, original, code, false, other.origin))
        assertTrue(NativeComputerVisibility.hidesForeground(hidden, rows, other, original, code, true, original.origin))
        assertTrue(NativeComputerVisibility.hidesForeground(hidden, rows, original, other, code, false, original.origin))
    }

    @Test fun hiddenOriginalRootRetiresEvenAfterPickerMovesToOtherTeam() {
        assertTrue(NativeComputerVisibility.hidesForeground(setOf(original.origin), listOf(original, other),
            original, null, code, true, other.origin))
        assertFalse(NativeComputerVisibility.hidesForeground(setOf(original.origin), listOf(original, other),
            other, null, code, true, original.origin))
    }

    @Test fun unsavedOrAmbiguousForegroundCannotBorrowHiddenSameLocator() {
        val rows = listOf(original, other)
        val hidden = setOf(original.origin)
        assertFalse(NativeComputerVisibility.hidesForeground(hidden, rows, null, null, code, false, ""))
        assertFalse(NativeComputerVisibility.hidesForeground(hidden, rows, null, null, code, false, other.origin))
        assertTrue(NativeComputerVisibility.hidesForeground(hidden, rows, null, null, code, false, original.origin))
        assertFalse(NativeComputerVisibility.hidesForeground(hidden, rows, original, null, "unsaved", false, original.origin))
    }

    @Test fun hidingAnotherTeamKeepsOriginalDetailsAndBuildSiblingOpen() {
        val sibling = NativePairingRecords.scoped(original.copy(instanceTag = "nightly"), team)
        val rows = listOf(original, other, sibling)
        val target = NativeComputerTarget("device", "default", "Original")
        assertFalse(NativeComputerVisibility.hidesDetails(setOf(other.origin), rows, team, target))
        assertFalse(NativeComputerVisibility.hidesDetails(setOf(sibling.origin), rows, team, target))
        assertTrue(NativeComputerVisibility.hidesDetails(setOf(original.origin), rows, team, target))
    }
}
