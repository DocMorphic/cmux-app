package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeLocalPairingRemovalTest {
    private val first = NativeTeamScope("login", "user", "original", 1)
    private val second = first.copy(teamId = "picker", generation = 2)
    private val code = "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user"
    private val original = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(code, "device", "Original", "default"), first)
    private val picker = NativePairingRecords.scoped(original.copy(name = "Picker"), second)
    private val target = NativeComputerTarget("device", "default", "Original")
    private fun state(vararg rows: NativeCredentialStore.PairedMac) = JSONObject().put("task_session", "login").put("refresh_token", "fixture")
        .put("pairing_code", code).put("computer_selection", original.origin).put("draft", "Keep work")
        .put("pairings", JSONArray(rows.map(NativePairingRecords::encode)))
    private fun saved(value: JSONObject) = value.getJSONArray("pairings").let { rows ->
        (0 until rows.length()).mapNotNull { NativePairingRecords.decode(rows.getJSONObject(it)) }
    }

    @Test fun sameLocatorAcrossTeamsAndSiblingBuildsRemovesOnlyCapturedPairing() {
        val sibling = NativePairingRecords.scoped(original.copy(instanceTag = "nightly", name = "Nightly"), first)
        val root = state(original, picker, sibling)
        NativeLocalPairingRemoval("login", first, original, code).remove(root) { true }
        assertEquals(listOf(picker, sibling), saved(root)); assertEquals("Keep work", root.getString("draft"))
        assertEquals("", root.getString("pairing_code")); assertEquals("", root.getString("computer_selection"))
        assertEquals("fixture", root.getString("refresh_token"))
    }

    @Test fun replacedRecordOtherLoginAndWrongOwnerCannotMutateStorage() {
        val replacement = original.copy(ticketRevision = UUID.randomUUID().toString())
        for ((root, action) in listOf(
            state(replacement) to NativeLocalPairingRemoval("login", first, original, code),
            state(original).put("task_session", "other") to NativeLocalPairingRemoval("login", first, original, code),
            state(original) to NativeLocalPairingRemoval("login", second, original, code),
            state(original) to NativeLocalPairingRemoval("login", null, original, code))) {
            val before = root.toString()
            assertTrue(runCatching { action.remove(root) { true } }.isFailure); assertEquals(before, root.toString())
        }
    }

    @Test fun latePermissionLossAndAmbiguousRecordRejectBeforeAnyStateChange() {
        val root = state(original); var checks = 0; val before = root.toString()
        assertTrue(runCatching { NativeLocalPairingRemoval("login", first, original, code).remove(root) { ++checks == 1 } }.isFailure)
        assertEquals(before, root.toString())
        val ambiguous = state(original, original.copy(name = "Duplicate")); val unchanged = ambiguous.toString()
        assertTrue(runCatching { NativeLocalPairingRemoval("login", first, original, code).remove(ambiguous) { true } }.isFailure)
        assertEquals(unchanged, ambiguous.toString())
    }

    @Test fun bareUnsavedCodeCanBeClearedWithoutDeletingSavedRecordsOrAnotherSelection() {
        val root = state(picker).put("pairing_code", "unsaved").put("computer_selection", picker.origin)
        NativeLocalPairingRemoval("login", second, null, "unsaved").remove(root) { true }
        assertEquals(listOf(picker), saved(root)); assertEquals(picker.origin, root.getString("computer_selection"))
        assertEquals("", root.getString("pairing_code"))
        assertTrue(runCatching { NativeLocalPairingRemoval("login", second, null, code).remove(root) { true } }.isFailure)
    }

    @Test fun renameDoesNotChangeRemovalIdentityAndAnotherSavedCodeIsPreserved() {
        val root = state(original.copy(name = "Renamed"), picker).put("pairing_code", "another-code").put("computer_selection", picker.origin)
        NativeLocalPairingRemoval("login", first, original, code).remove(root) { true }
        assertEquals(listOf(picker), saved(root)); assertEquals("another-code", root.getString("pairing_code"))
        assertEquals(picker.origin, root.getString("computer_selection"))
    }

    @Test fun sameLocatorOtherOwnerCannotStopLiveRootButExactPendingRequestCanBeStopped() {
        assertFalse(nativeForgetOwnsRoot(second, target, listOf(picker), original, picker, code, true))
        assertTrue(nativeForgetOwnsRoot(first, target, listOf(original), original, null, code, true))
        assertTrue(nativeForgetOwnsRoot(second, target, listOf(picker), original, picker, code, false))
        assertFalse(nativeForgetOwnsRoot(second, target, listOf(original), original, null, code, true))
        assertFalse(nativeForgetOwnsRoot(first, target.copy(deviceId = "other"), listOf(original), original, null, code, true))
    }
}
