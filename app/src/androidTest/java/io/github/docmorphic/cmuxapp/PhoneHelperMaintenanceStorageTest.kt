package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID
import java.io.File

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

    @Test fun deferredExplicitLogoutRetainsItsCleanupProofBeforePruningTheReadableAccount() {
        IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val seeded=seed(context);val unavailable=java.util.concurrent.atomic.AtomicBoolean(false)
            val reader=NativeCredentialStore.withKeyProvider(context,"native_cmux","cmux_native_v1") {
                if(unavailable.get()) throw java.security.KeyStoreException("Fixture provider unavailable")
                java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("cmux_native_v1",null) as javax.crypto.SecretKey
            }
            val account=NativeAccount(reader)
            val before=context.getSharedPreferences("native_cmux",Context.MODE_PRIVATE).getString("state",null)
            unavailable.set(true);assertTrue(account.signOut());assertTrue(reader.localLogoutPending)
            assertEquals(before,context.getSharedPreferences("native_cmux",Context.MODE_PRIVATE).getString("state",null))
            unavailable.set(false);account.retryLocalLogout()
            assertFalse(reader.localLogoutPending);assertNull(reader.taskSession())
            assertEquals(listOf(seeded.mac),reader.pairedMacs())
            val queue=PhoneHelperMaintenanceQueue(ledger(context).requireReadable()!!)
            queue.reconcile(JSONObject(),null,null)
            val revoke=queue.pending().single();assertEquals("revoke",revoke.action)
            assertEquals(seeded.receipt.getString("registration"),revoke.receipt.getString("registration"))
            assertArrayEquals(seeded.phone.privateKey,revoke.phone.privateKey)
            assertEquals(NativeAuthState(),account.sessionRestore.state.value)
        }
    }

    @Test fun duplicateSdkCallbackKeepsHelperProofAndConfirmedRotationQueuesRenewal() {
        IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val seeded=seed(context);val account=NativeCredentialStore(context).requireReadable()!!
            val token=PhoneFcmTokenState(JSONObject().put(PhoneFcmTokenState.KEY,JSONObject()
                .put("project",PhoneFcmProject("fixture-project","fixture-app","fixture-sender").json())
                .put("login",seeded.team.login).put("epoch","fixture-grant").put("touched",true)
                .put("token","fixture-token").put("revision","fixture-revision")))
            val original=token.snapshot!!;val queue=PhoneHelperMaintenanceQueue(ledger(context).requireReadable()!!)
            queue.reconcile(account,token.grant,token.snapshot);assertTrue(queue.pending().isEmpty())
            token.providerChanged(PhoneFcmTokenCallback.next())
            queue.reconcile(account,token.grant,token.currentSnapshot(false));assertTrue(queue.pending().isEmpty())
            assertTrue(token.accept(token.readTicket!!,"fixture-token"));assertEquals(original,token.snapshot)
            queue.reconcile(account,token.grant,token.snapshot);assertTrue(queue.pending().isEmpty())
            token.providerChanged(PhoneFcmTokenCallback.next());assertTrue(token.accept(token.readTicket!!,"rotated-token"))
            queue.reconcile(account,token.grant,token.snapshot)
            assertEquals("renew",queue.pending().single().action)
            assertNotNull(PhonePushHelperState(account).binding(seeded.team,seeded.mac.origin))
            assertArrayEquals(seeded.phone.privateKey,PhonePushKeyState(account).existingIdentity(seeded.team.login)!!.privateKey)
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
    @Test fun unreadablePrimaryAccountCannotRetireOrRewriteItsHelperMaintenanceLedger() {
        IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val seeded=seed(context)
            val account=context.getSharedPreferences("native_cmux",Context.MODE_PRIVATE)
            val savedAccount=account.getString("state",null)!!
            val maintenance=context.getSharedPreferences("phone_helper_maintenance",Context.MODE_PRIVATE)
            val savedLedger=maintenance.getString("state",null)!!
            assertTrue(account.edit().putString("state","unreadable fixture ciphertext").commit())
            try { kotlinx.coroutines.runBlocking { PhoneHelperMaintenanceWork.recover(context) }; fail("Unknown account must not mean logout") }
            catch (_:NativeCredentialReadException) { }
            assertEquals("unreadable fixture ciphertext",account.getString("state",null))
            assertEquals(savedLedger,maintenance.getString("state",null))
            assertTrue(account.edit().putString("state",savedAccount).commit())
            val recovered=NativeCredentialStore(context).load()!!
            assertEquals(seeded.receipt.getString("id"),recovered.getJSONArray(PhoneHelperEnrollmentState.RECEIPTS).getJSONObject(0).getString("id"))
            assertEquals(savedLedger,maintenance.getString("state",null))
        }
    }

    @Test fun unreadableMaintenanceLedgerCannotCommitACompetingAccountProjection() {
        IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            seed(context)
            val account=context.getSharedPreferences("native_cmux",Context.MODE_PRIVATE)
            val savedAccount=account.getString("state",null)!!
            val maintenance=context.getSharedPreferences("phone_helper_maintenance",Context.MODE_PRIVATE)
            val savedLedger=maintenance.getString("state",null)!!
            assertTrue(maintenance.edit().putString("state","unreadable fixture ledger").commit())
            try { NativeCredentialStore(context).update { it.remove(PhoneHelperEnrollmentState.RECEIPTS);it.remove(PhonePushHelperState.KEY) }; fail("Unknown ledger must stop the account commit") }
            catch (_:NativeCredentialReadException) { }
            assertEquals(savedAccount,account.getString("state",null))
            assertEquals("unreadable fixture ledger",maintenance.getString("state",null))
            assertTrue(maintenance.edit().putString("state",savedLedger).commit())
            assertEquals(1,rows(context).length());assertNotNull(NativeCredentialStore(context).load())
        }
    }

    @Test fun unreadableFcmGrantCannotMeanOptOutOrRetireThePrivateHelper() {
        IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val seeded=seed(context)
            val tokens=NativeCredentialStore(context,"phone_fcm_tokens")
            tokens.update { PhoneFcmTokenState(it).authorize(seeded.team.login,PhoneFcmProject.parse(seeded.receipt.getJSONObject("project"))) }
            val fcm=context.getSharedPreferences("phone_fcm_tokens",Context.MODE_PRIVATE)
            val savedFcm=fcm.getString("state",null)!!
            val account=context.getSharedPreferences("native_cmux",Context.MODE_PRIVATE)
            val savedAccount=account.getString("state",null)!!
            val maintenance=context.getSharedPreferences("phone_helper_maintenance",Context.MODE_PRIVATE)
            val savedLedger=maintenance.getString("state",null)!!
            assertTrue(fcm.edit().putString("state","unreadable fixture FCM grant").commit())
            try { PhoneFcmTokens.snapshot(context);fail("Unknown grant is not a missing grant") }
            catch (_:NativeCredentialReadException) { }
            try { kotlinx.coroutines.runBlocking { PhoneHelperMaintenanceWork.recover(context) };fail("Unknown grant must not trigger retirement") }
            catch (_:NativeCredentialReadException) { }
            val recovery=PhoneFcmTokens.recover(context);kotlinx.coroutines.runBlocking { recovery.join() }
            assertTrue(recovery.isCompleted);assertFalse("Background recovery must contain read failures",recovery.isCancelled)
            assertEquals(savedAccount,account.getString("state",null));assertEquals(savedLedger,maintenance.getString("state",null))
            assertEquals("unreadable fixture FCM grant",fcm.getString("state",null))
            assertTrue(fcm.edit().putString("state",savedFcm).commit())
            assertNotNull(tokens.load());assertEquals(1,rows(context).length())
        }
    }

    @Test fun deferredHistoricalRowsDoNotHideTheCurrentRenewalInTheEncryptedQueue() = maintenanceReadiness(true)
    @Test fun currentRenewalLeadsHistoricalCleanupWithoutExceedingThePassBudget() = maintenanceReadiness(false)
    private fun maintenanceReadiness(deferred: Boolean) = runBlocking<Unit> {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext).use { context ->
            val seeded = seed(context); val account = NativeCredentialStore(context).requireReadable()!!
            val time = System.currentTimeMillis()
            val token = PhoneFcmTokenSnapshot(PhoneFcmTokenGrant(seeded.team.login,
                PhoneFcmProject.parse(seeded.receipt.getJSONObject("project")), "fixture-grant"), "rotated-fixture-token", "new-revision")
            val current = JSONObject(rows(context).getJSONObject(0).toString())
            // These tests begin with an already-verified receipt. Use valid
            // server UUIDs to exercise the real maintenance request constructor.
            current.getJSONObject("receipt").put("registration", UUID.randomUUID().toString()).put("generation", UUID.randomUUID().toString())
            account.getJSONArray(PhoneHelperEnrollmentState.RECEIPTS).put(0, JSONObject(current.getJSONObject("receipt").toString()))
            val base = JSONObject().put(PhoneHelperMaintenanceQueue.KEY, JSONArray().put(current))
            PhoneHelperMaintenanceQueue(base, { time }).reconcile(account, token.grant, token)
            val ready = base.getJSONArray(PhoneHelperMaintenanceQueue.KEY).getJSONObject(0)
            val history = (1..if (deferred) 8 else 9).map {
                JSONObject(ready.toString()).apply {
                    getJSONObject("receipt").put("id", UUID.randomUUID().toString()).put("registration", UUID.randomUUID().toString())
                    put("cleanup_deadline", time + 86_400_000)
                    put("operation", JSONObject().put("id", UUID.randomUUID().toString()).put("action", "revoke")
                        .put("deadline", time + 86_400_000).apply { if (deferred) put("retry_at", time + 60_000) })
                }
            }
            val storage = ledger(context)
            storage.update { it.put(PhoneHelperMaintenanceQueue.KEY, JSONArray(history + ready)) }
            val readyID = ready.getJSONObject("operation").getString("id")
            fun access(write: Boolean, action: (PhoneHelperMaintenanceQueue) -> Unit) {
                fun apply(state: JSONObject) {
                    val queue = PhoneHelperMaintenanceQueue(state, { time })
                    queue.reconcile(account, token.grant, token); action(queue); queue.reconcile(account, token.grant, token)
                }
                if (write) storage.update(::apply) else apply(storage.requireReadable()!!)
            }
            val attempted = mutableListOf<String>(); val actions = mutableListOf<String>()
            val retry = PhoneHelperMaintenanceRecovery({ access(true, it) }, { access(false, it) }, { endpoint, permits, step, body ->
                assertEquals("https://fixture.invalid/v1/push/enroll", endpoint); assertTrue(permits())
                assertEquals("maintain.begin", step)
                attempted += body.getString("requestID"); actions += body.getString("action")
                if (body.getString("requestID") == readyID) assertEquals(token.token, body.getString("token"))
                // A transport seam: preserve pending proof/ownership and exercise
                // real encrypted retry commits, without claiming a remote ACK.
                PhoneHelperHttpResult.Retry(time + 30_000)
            }, { time }).runPass()
            assertTrue(retry); assertEquals(readyID, attempted.first()); assertEquals("renew", actions.first())
            assertEquals(if (deferred) 1 else 8, attempted.size); assertEquals(attempted.size, attempted.distinct().size)
            val cold = NativeCredentialStore(context, "phone_helper_maintenance").requireReadable()!!
            val pending = PhoneHelperMaintenanceQueue(cold, { time }).pending()
            assertEquals(history.size + 1, pending.size)
            assertEquals(time + 30_000, pending.single { it.requestID == readyID }.retryAt)
            assertEquals(if (deferred) 8 else 0, pending.count { it.retryAt == time + 60_000 })
            assertEquals(if (deferred) 0 else 2, pending.count { it.retryAt == 0L })
            val ciphertext = context.getSharedPreferences("phone_helper_maintenance", 0).getString("state", null)!!
            assertFalse(ciphertext.contains(token.token)); assertFalse(ciphertext.contains(seeded.phone.keyID))
            assertEquals(listOf(seeded.mac), NativeCredentialStore(context).pairedMacs())
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "helper-readiness").apply { mkdirs() }
                .resolve(if (deferred) "deferred-history.json" else "eligible-history.json").writeText(JSONObject()
                    .put("fixtureOnly", true).put("encryptedStoreUsed", true).put("currentRenewalFirst", true)
                    .put("attemptedOperations", attempted.size).put("retainedOperations", pending.size).put("passBudget", 8)
                    .put("historicalRetryFloorsPreserved", true).put("pairingPreserved", true).put("transportSeamReturnsRetry", true)
                    .put("remoteAcknowledgementClaimed", false).toString(2))
        }
    }

}
