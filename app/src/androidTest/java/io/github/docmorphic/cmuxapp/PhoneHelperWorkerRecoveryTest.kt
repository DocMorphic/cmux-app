package io.github.docmorphic.cmuxapp

import android.annotation.SuppressLint
import android.content.Context
import androidx.concurrent.futures.await
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Actual WorkManager/worker/recovery/HTTPS with isolated storage and a local Node/CryptoKit helper. */
@SuppressLint("RestrictedApi")
class PhoneHelperWorkerRecoveryTest {
    @Test fun replacementSettlesACanceledCommittedRenewalThenLogoutRemovesItsNextGeneration() = runBlocking<Unit> {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "helper-worker-fixture")
        val config = JSONObject(File(folder, "config.json").readText())
        val certificate = File(folder, "cert.pem").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .callTimeout(15, TimeUnit.SECONDS).build()
        val fixture = Fixture(context, config, client)
        val executor = Executors.newFixedThreadPool(2)
        val offset = AtomicLong()
        val workers = ConcurrentHashMap<UUID, PhoneHelperMaintenanceWorker>()
        val previous = WorkManager.getInstance(context) as WorkManagerImpl
        val configuration = Configuration.Builder().setExecutor(executor)
            .setClock(androidx.work.Clock { System.currentTimeMillis() + offset.get() })
            .setMinimumLoggingLevel(android.util.Log.ERROR)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                    if (workerClassName == PhoneHelperMaintenanceWorker::class.java.name)
                        PhoneHelperMaintenanceWorker(appContext, workerParameters) { fixture.runPass() }
                            .also { workers[workerParameters.id] = it } else null
            }).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        val work = WorkManager.getInstance(context)
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        suspend fun info(id: UUID) = checkNotNull(work.getWorkInfoById(id).await())
        suspend fun until(test: suspend () -> Boolean) = withTimeout(10_000) { while (!test()) delay(10) }
        suspend fun pendingID(): UUID = work.getWorkInfosForUniqueWork(PhoneHelperMaintenanceWork.NAME).await()
            .filterNot { it.state.isFinished }.single().id
        suspend fun schedule(): UUID {
            val changed = fixture.access(true) {}
            PhoneHelperMaintenanceWork.enqueue(context, changed)
            return pendingID().also(driver::setAllConstraintsMet)
        }
        suspend fun drain(id: UUID) {
            repeat(4) {
                until { info(id).state == WorkInfo.State.SUCCEEDED ||
                    info(id).state == WorkInfo.State.ENQUEUED && info(id).runAttemptCount > 0 && fixture.active.get() == 0 }
                val current = info(id)
                if (current.state == WorkInfo.State.SUCCEEDED) return
                // Advance only WorkManager's injected scheduler clock. Crypto,
                // HTTP and production operation/cleanup deadlines keep real time.
                offset.addAndGet((current.runAttemptCount + 1L) * 10_000)
                driver.setAllConstraintsMet(id)
                until { info(id).state == WorkInfo.State.SUCCEEDED || info(id).runAttemptCount > current.runAttemptCount }
            }
            fail("Worker did not settle its bounded recovery passes")
        }
        val observed = JSONArray()
        try {
            fixture.enroll()
            val initial = fixture.receipt().getString("generation")
            fixture.rotate("first-token", "one")
            fixture.control("/arm", true)
            val old = schedule()
            until { fixture.control("/state").getJSONArray("gates").length() == 1 }
            assertEquals(WorkInfo.State.RUNNING, info(old).state)
            val saved = fixture.pending().single()
            assertNotNull(saved.response)
            val firstRequest = saved.requestID
            assertEquals(firstRequest, fixture.control("/state").getJSONArray("gates").getJSONObject(0).getString("requestID"))
            assertEquals(initial, fixture.receipt().getString("generation"))
            assertEquals("first-token", fixture.remote().single().getString("token"))

            fixture.rotate("latest-token", "two")
            val fresh = schedule()
            assertNotEquals(old, fresh)
            // REPLACE can delete the old WorkSpec. Observe the actual stopped
            // worker and closed HTTP response, rather than requiring that row.
            until { workers[old]?.isStopped == true && fixture.control("/state").getJSONArray("gates").getJSONObject(0).getBoolean("closed") }
            assertTrue(work.getWorkInfoById(old).await()?.state?.let { it == WorkInfo.State.CANCELLED } != false)
            drain(fresh)
            assertEquals("two", fixture.receipt().getString("token_revision"))
            val currentRemote = fixture.remote().single()
            assertEquals("latest-token", currentRemote.getString("token"))
            assertEquals(currentRemote.getJSONObject("registration").getString("generation"), fixture.receipt().getString("generation"))
            assertTrue(fixture.pending().isEmpty())
            var server = fixture.control("/state")
            assertEquals(2, server.getInt("commits"))
            val firstAbort = server.getJSONArray("events").objects().single { it.optString("step") == "maintain.abort" }
            assertEquals(firstRequest, firstAbort.getString("requestID")); assertTrue(firstAbort.getBoolean("recoveredCommittedAck"))
            observed.put(JSONObject().put("case", "replacement").put("oldWorkerCanceled", true).put("canceledResponseClosed", true)
                .put("savedChallengeRecovered", true).put("commits", 2).put("latestGenerationVerified", true))

            fixture.rotate("third-token", "three")
            fixture.control("/arm", true)
            val retiring = schedule()
            until { fixture.control("/state").getJSONArray("gates").length() == 2 }
            val thirdRequest = fixture.pending().single().requestID
            assertNotNull(fixture.pending().single().response)
            assertEquals("third-token", fixture.remote().single().getString("token"))
            fixture.logout()
            val removal = schedule()
            assertNotEquals(retiring, removal)
            until { workers[retiring]?.isStopped == true && fixture.control("/state").getJSONArray("gates").getJSONObject(1).getBoolean("closed") }
            assertTrue(work.getWorkInfoById(retiring).await()?.state?.let { it == WorkInfo.State.CANCELLED } != false)
            drain(removal)
            assertTrue(fixture.remote().isEmpty()); assertTrue(fixture.pending().isEmpty())
            assertFalse(fixture.account().has(PhonePushHelperState.KEY)); assertFalse(fixture.account().has(PhoneHelperEnrollmentState.RECEIPTS))
            assertEquals(listOf(fixture.mac), fixture.accountStore.pairedMacs())
            server = fixture.control("/state")
            assertEquals(4, server.getInt("commits"))
            val events = server.getJSONArray("events").objects()
            assertEquals(listOf("maintain.begin", "maintain.finish", "maintain.abort", "maintain.begin", "maintain.finish",
                "maintain.begin", "maintain.finish", "maintain.abort", "maintain.begin", "maintain.finish"), events.map { it.getString("step") })
            assertEquals(listOf(firstRequest, thirdRequest), events.filter { it.getString("step") == "maintain.abort" }.map { it.getString("requestID") })
            assertTrue(events.filter { it.getString("step") == "maintain.abort" }.all { it.getBoolean("recoveredCommittedAck") })
            assertEquals(1, fixture.maximumActive.get())
            assertEquals(WorkInfo.State.SUCCEEDED, info(removal).state)
            until { client.dispatcher.runningCallsCount() == 0 && fixture.active.get() == 0 }
            observed.put(JSONObject().put("case", "logout").put("oldWorkerCanceled", true).put("canceledResponseClosed", true)
                .put("savedChallengeRecovered", true).put("totalCommits", 4).put("registrationRemoved", true).put("trustNotRestored", true))
            File(context.getExternalFilesDir(null), "helper-worker").apply { mkdirs() }.resolve("recovery.json").writeText(JSONObject()
                .put("fixtureOnly", true).put("realWorkManager", true).put("realHttps", true).put("realCryptoKitHelper", true)
                .put("encryptedStores", true).put("injectedStorageAndTokenProvider", true).put("schedulerClockInjected", true)
                .put("defaultFirebaseObserverClaimed", false).put("pairingPreserved", true).put("maximumActivePasses", fixture.maximumActive.get())
                .put("phases", observed).put("serverEvents", JSONArray(events)).toString(2))
        } finally {
            try {
                work.cancelUniqueWork(PhoneHelperMaintenanceWork.NAME).await()
                withTimeout(10_000) { while (fixture.active.get() > 0 || client.dispatcher.runningCallsCount() > 0) delay(10) }
                fixture.control("/release", true)
            } finally {
                try { WorkManagerTestInitHelper.closeWorkDatabase() }
                finally { WorkManagerImpl.setDelegate(previous); executor.shutdownNow(); fixture.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            }
        }
        assertSame(previous, WorkManager.getInstance(context))
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private class Fixture(private val context: Context, private val config: JSONObject, private val client: OkHttpClient) : AutoCloseable {
        private val name = "helper-worker-" + UUID.randomUUID()
        private val alias = "cmux_helper_worker_" + UUID.randomUUID()
        val accountStore = NativeCredentialStore.withKeyProvider(context, "$name-account", "$alias-account")
        private val ledger get() = NativeCredentialStore.withKeyProvider(context, "$name-ledger", "$alias-ledger")
        private val source = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("push-worker/helper-enrollment.json").bufferedReader().use { it.readText() })
        private val offer = config.getJSONObject("offer")
        private val fields = offer.getJSONObject("mac")
        private val team = NativeTeamScope("worker-fixture-login", fields.getString("accountID"), fields.getString("teamID"), 1)
        private val descriptor = PhonePushDescriptor.parse(source.getJSONObject("begin").getJSONObject("phone"))
        private val phone = PhonePushIdentity(descriptor.installationID, descriptor.keyID, Base64.getDecoder().decode(source.getString("phonePrivateKey")))
        private val peer = PhonePushPeer(PhonePushTuple(team.userId, team.teamId, offer.getString("phoneBuildID"), phone.installationID,
            fields.getString("macDeviceID"), fields.getString("macInstanceTag"), fields.getString("macBuildID")), PhonePushDescriptor.parse(offer.getJSONObject("native")))
        val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
            IrohV2Computer("record", "a".repeat(64), "directory-mac", fields.getString("macInstanceTag"), "Fixture Mac", emptyList()), team),
            "directory-mac", "Fixture Mac", fields.getString("macInstanceTag")), team)
        private val original = PhoneFcmTokenSnapshot(PhoneFcmTokenGrant(team.login, PhoneFcmProject.parse(offer.getJSONObject("project")), "fixture-consent"),
            source.getJSONObject("begin").getString("token"), "original-revision")
        private val provider = AtomicReference<PhoneFcmTokenSnapshot?>(original)
        private val serial = Mutex()
        val active = AtomicInteger(); val maximumActive = AtomicInteger()
        fun account(): JSONObject = accountStore.requireReadable() ?: JSONObject()
        fun receipt(): JSONObject = account().getJSONArray(PhoneHelperEnrollmentState.RECEIPTS).getJSONObject(0)
        fun pending() = PhoneHelperMaintenanceQueue(ledger.requireReadable() ?: JSONObject()).pending()
        fun rotate(token: String, revision: String) { provider.set(original.copy(token = token, revision = revision)) }
        fun logout() { provider.set(null); assertTrue(NativeAccount(accountStore).signOut()) }
        fun control(path: String, mutate: Boolean = false): JSONObject {
            val request = Request.Builder().url(config.getString("control") + path).apply { if (mutate) post(ByteArray(0).toRequestBody()) }.build()
            return client.newCall(request).execute().use { assertEquals(200, it.code); JSONObject(it.body!!.string()) }
        }
        fun remote(): List<JSONObject> = control("/state").getJSONArray("remote").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        suspend fun enroll() {
            accountStore.update {
                it.put("task_session", team.login).put("refresh_token", "fixture-refresh").put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
                it.put(PhonePushKeyState.KEY, JSONObject().put("login", team.login).put("identity", phone.wire()))
                PhonePushKeyState(it).pin(team, mac.origin, peer)
            }
            lateinit var enrollment: PendingPhoneHelperEnrollment
            accountStore.update { enrollment = PhoneHelperEnrollmentState(it).prepare(offer.toString(), team, mac.origin, original) }
            val epoch = PhonePushKeyState(account()).peerEpoch(team, mac.origin)!!
            PhoneHelperEnrollment(offer.toString(), team, mac.origin, peer, epoch, phone, original, { true }, requestID = enrollment.requestID).use { session ->
                PhoneHelperHttp(session.endpoint, { true }, client).use { http ->
                    val response = (http.send("begin", session.begin()) as PhoneHelperHttpResult.Success).body
                    val proof = session.finish(response)
                    accountStore.update { assertTrue(PhoneHelperEnrollmentState(it).checkpoint(enrollment, original, response)) }
                    val ack = (http.send("finish", proof) as PhoneHelperHttpResult.Success).body
                    accountStore.update { PhoneHelperEnrollmentState(it).confirm(enrollment, original, session, ack) }
                }
            }
            access(true) {}
            assertTrue(pending().isEmpty())
        }
        // Isolated storage/provider adapter mirrors the production ledger-first
        // transaction. The default Firebase/permission observer is not exercised.
        fun access(write: Boolean, action: (PhoneHelperMaintenanceQueue) -> Unit): Boolean = synchronized(accountStore.accountStateLock) {
            val account = account(); val beforeAccount = account.toString()
            val state = ledger.requireReadable() ?: JSONObject(); val beforeState = state.toString()
            val token = provider.get(); val queue = PhoneHelperMaintenanceQueue(state)
            queue.reconcile(account, token?.grant, token); action(queue); queue.reconcile(account, token?.grant, token)
            if (write) {
                if (state.toString() != beforeState) ledger.update {
                    if (state.has(PhoneHelperMaintenanceQueue.KEY)) it.put(PhoneHelperMaintenanceQueue.KEY, state.getJSONArray(PhoneHelperMaintenanceQueue.KEY))
                    else it.remove(PhoneHelperMaintenanceQueue.KEY)
                }
                if (account.toString() != beforeAccount) accountStore.updateFromMaintenance {
                    for (key in listOf(PhoneHelperEnrollmentState.RECEIPTS, PhonePushHelperState.KEY)) {
                        if (account.has(key)) it.put(key, account.get(key)) else it.remove(key)
                    }
                }
            }
            state.toString() != beforeState
        }
        suspend fun runPass(): Boolean = serial.withLock {
            val count = active.incrementAndGet(); maximumActive.updateAndGet { maxOf(it, count) }
            try {
                PhoneHelperMaintenanceRecovery({ access(true, it) }, { access(false, it) }, { endpoint, permits, step, body ->
                    PhoneHelperHttp(endpoint, permits, client).use { it.send(step, body) }
                }).runPass()
            } finally { active.decrementAndGet() }
        }
        override fun close() {
            accountStore.clear(); ledger.clear()
            context.deleteSharedPreferences("$name-account"); context.deleteSharedPreferences("$name-ledger")
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { it.deleteEntry("$alias-account"); it.deleteEntry("$alias-ledger") }
        }
    }
}
