package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeLocalPairingRemovalTest {
    @Test fun encryptedCommitKeepsSameLocatorOtherTeamAndLateGuardCannotAlterDisk() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Emulator-only fixture" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "local-pairing-removal-fixture-" + UUID.randomUUID()
        val store = NativeCredentialStore(context, name)
        val first = NativeTeamScope("fixture-login", "user", "original", 1)
        val second = first.copy(teamId = "picker", generation = 2)
        val code = "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user"
        val original = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(code, "device", "Original", "default"), first)
        val picker = NativePairingRecords.scoped(original.copy(name = "Picker"), second)
        val action = NativeLocalPairingRemoval(first.login, first, original, code)
        try {
            store.update { it.put("task_session", first.login).put("refresh_token", "fixture").put("pairing_code", code)
                .put("computer_selection", picker.origin).put("draft", "Keep work")
                .put("pairings", JSONArray().put(NativePairingRecords.encode(original)).put(NativePairingRecords.encode(picker))) }
            val before = store.load().toString(); var checks = 0
            assertTrue(runCatching { store.forgetLocalPairing(action) { ++checks < 3 } }.isFailure)
            assertEquals(before, NativeCredentialStore(context, name).load().toString())
            store.forgetLocalPairing(action) { true }
            val reopened = NativeCredentialStore(context, name)
            assertEquals(listOf(picker), reopened.pairedMacs()); assertEquals("Keep work", reopened.load()!!.getString("draft"))
            assertEquals(picker.origin, reopened.load()!!.getString("computer_selection"))
            assertEquals(code, reopened.load()!!.getString("pairing_code"))
            assertEquals(first.login, reopened.taskSession()); assertEquals("fixture", reopened.load()!!.getString("refresh_token"))
        } finally { store.clear() }
    }
}
