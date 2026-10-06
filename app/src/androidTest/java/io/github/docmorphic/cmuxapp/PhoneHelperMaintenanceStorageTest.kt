package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

class PhoneHelperMaintenanceStorageTest {
    /** Exercise primary-store hooks and real Android Keystore, with completely separate test preferences/revision flows. */
    private class IsolatedContext(base: Context) : ContextWrapper(base), AutoCloseable {
        private val namespace = "push-maintenance-test-${UUID.randomUUID()}"
        private val names = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo() = ApplicationInfo(super.getApplicationInfo()).apply {
            dataDir = "${super.getApplicationInfo().dataDir}/$namespace"
        }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names += name
            return super.getSharedPreferences("$namespace.$name", mode)
        }
        override fun close() { names.forEach { super.deleteSharedPreferences("$namespace.$it") } }
    }
    private data class Seed(val team: NativeTeamScope, val mac: NativeCredentialStore.PairedMac,
        val phone: PhonePushIdentity, val receipt: JSONObject)
    private fun seed(context: Context): Seed {
        val team = NativeTeamScope("fixture-maintenance-login", "fixture-user", "fixture-team", 1)
        val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
            IrohV2Computer("record", "a".repeat(64), "directory-mac", "stable", "Fixture Mac", emptyList()), team),
            "directory-mac", "Fixture Mac", "stable"), team)
        var result: Seed? = null
        NativeCredentialStore(context).update { state ->
            state.put("task_session", team.login).put("refresh_token", "fixture-refresh")
                .put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
            val keys = PhonePushKeyState(state); val phone = keys.identity(team.login)
            val native = PhonePushPeer(PhonePushTuple(team.userId, null, context.packageName, phone.installationID,
                "physical-mac", "stable", "fixture.mac.build"), PhonePushIdentity.generate().descriptor())
            keys.pin(team, mac.origin, native)
            val epoch = checkNotNull(keys.peerEpoch(team, mac.origin))
            val project = PhoneFcmProject("fixture-project", "fixture-app", "fixture-sender")
            val helper = PhonePushHelperState(state).pin(team, mac.origin, native, epoch, PhonePushIdentity.generate().descriptor(), project)
            // A previously verified receipt: handshake cryptography has separate TLS/CryptoKit coverage.
            val receipt = JSONObject().put("id", UUID.randomUUID().toString()).put("login", team.login)
                .put("user", team.userId).put("team", team.teamId).put("origin", mac.origin)
                .put("native", native.wire()).put("native_epoch", epoch).put("phone", phone.descriptor().wire())
                .put("project", project.json()).put("grant", "fixture-grant").put("token_revision", "fixture-revision")
                .put("endpoint", "https://fixture.invalid/v1/push/enroll").put("registration", "fixture-registration")
                .put("generation", "fixture-generation").put("helper_epoch", helper.epoch)
            state.put(PhoneHelperEnrollmentState.RECEIPTS, JSONArray().put(receipt))
            result = Seed(team, mac, phone, receipt)
        }
        return checkNotNull(result)
    }
    private fun ledger(context: Context) = NativeCredentialStore(context, "phone_helper_maintenance")
    private fun rows(context: Context) = checkNotNull(ledger(context).load()).getJSONArray(PhoneHelperMaintenanceQueue.KEY)

    @Test fun logoutPreservesEncryptedCleanupKeyWithoutRestoringAccountTrust() {
        IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val seeded = seed(context)
            assertEquals(1, rows(context).length())
            val retained = rows(context).getJSONObject(0)
            assertArrayEquals(seeded.phone.privateKey, PhonePushIdentity.parse(retained.getJSONObject("phone_private")).privateKey)
            val before = System.currentTimeMillis(); NativeCredentialStore(context).clear()
            assertNull(NativeCredentialStore(context).load())
            val restored = checkNotNull(ledger(context).load())
            assertTrue(restored.getJSONArray(PhoneHelperMaintenanceQueue.KEY).getJSONObject(0).getLong("retirement_at") >= before)
            val queue = PhoneHelperMaintenanceQueue(restored)
            queue.reconcile(JSONObject(), null, null)
            val revoke = queue.pending().single()
            assertEquals("revoke", revoke.action)
            assertEquals(seeded.receipt.getString("registration"), revoke.receipt.getString("registration"))
            assertArrayEquals(seeded.phone.privateKey, revoke.phone.privateKey)
            val encrypted = context.getSharedPreferences("phone_helper_maintenance", Context.MODE_PRIVATE).getString("state", "")!!
            for (value in listOf(seeded.team.login, seeded.phone.keyID, "fixture-registration", Base64.getEncoder().encodeToString(seeded.phone.privateKey)))
                assertFalse("Cleanup material leaked into preferences", encrypted.contains(value))
            assertNull(NativeCredentialStore(context).load())
        }
    }

    @Test fun removingPairingCapturesCleanupBeforeNormalPruningRemovesPins() {
        IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val seeded = seed(context); val store = NativeCredentialStore(context)
            store.forgetMac(seeded.mac.code, seeded.team) { true }
            val account = checkNotNull(NativeCredentialStore(context).load())
            assertNull(PhonePushHelperState(account).binding(seeded.team, seeded.mac.origin))
            assertFalse(account.has(PhoneHelperEnrollmentState.RECEIPTS))
            val restored = checkNotNull(ledger(context).load()); val queue = PhoneHelperMaintenanceQueue(restored)
            queue.reconcile(account, null, null)
            assertEquals("revoke", queue.pending().single().action)
            assertEquals(seeded.receipt.getString("id"), queue.pending().single().id)
        }
    }

    @Test fun maintenanceProjectionDoesNotResurrectAnAlreadyDeletedCleanupLedger() {
        IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            seed(context)
            ledger(context).clear()
            NativeCredentialStore(context).updateFromMaintenance {
                it.remove(PhoneHelperEnrollmentState.RECEIPTS); it.remove(PhonePushHelperState.KEY)
            }
            assertNull(ledger(context).load())
            NativeCredentialStore(context).update { it.put("unrelated_fixture", true) }
            assertNull(ledger(context).load())
            assertFalse(NativeCredentialStore(context).load()!!.has(PhoneHelperEnrollmentState.RECEIPTS))
        }
    }
}
