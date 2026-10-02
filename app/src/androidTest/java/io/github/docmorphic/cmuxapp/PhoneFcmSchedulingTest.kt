package io.github.docmorphic.cmuxapp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.concurrent.futures.await
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.Executors

/** Real WorkManager graph with a retrying local worker; never contacts Firebase or a user account. */
@SuppressLint("RestrictedApi") // WorkManager test delegate must be restored for subsequent classes.
class PhoneFcmSchedulingTest {
    @Test fun newArrivalBypassesRetryAndRedeliveryDoesNotGrowWorkChain() = runBlocking {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Disposable emulator required" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = NativeCredentialStore(context)
        val executor = Executors.newFixedThreadPool(2)
        NativeNotificationService.setEnabled(context, false)
        store.clear()
        val config = Configuration.Builder().setExecutor(executor).setMinimumLoggingLevel(android.util.Log.ERROR)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                    if (workerClassName == PhoneFcmWorker::class.java.name) RetryingPushFixture(appContext, workerParameters) else null
            }).build()
        val previousManager = WorkManager.getInstance(context) as WorkManagerImpl
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        val work = WorkManager.getInstance(context)
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        suspend fun infos() = work.getWorkInfosForUniqueWork(PhoneFcmWork.NAME).await()
        suspend fun waitFor(condition: suspend () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(20) }
        try {
            context.getSharedPreferences("native_notification_settings", Context.MODE_PRIVATE).edit()
                .putBoolean("background_enabled", true).commit()
            store.update { it.put("task_session", "scheduler-fixture").put("refresh_token", "fixture-refresh") }
            val phone = PhonePushIdentity.generate()
            val sender = PhonePushIdentity.generate()
            fun encrypted(body: String): String {
                val envelope = PhonePushCrypto.encrypt(body.toByteArray(), PhonePushTuple("fixture-user", "fixture-team",
                    context.packageName, phone.installationID, "fixture-mac", "fixture-instance", "fixture.build"),
                    phone.keyID, sender.keyID, Base64.getDecoder().decode(phone.descriptor().publicKey), sender.privateKey)
                return JSONObject().put("encryptedPayloads", JSONArray().put(envelope.wire())).toString()
            }
            val first = encrypted("first scheduler fixture")
            val second = encrypted("second scheduler fixture")
            assertTrue(PhoneFcmWork.enqueue(context, first, false))
            val previous = infos().single().id
            driver.setAllConstraintsMet(previous)
            waitFor { infos().any { it.id == previous && it.runAttemptCount == 1 && it.state == WorkInfo.State.ENQUEUED } }

            assertTrue(PhoneFcmWork.enqueue(context, second, true))
            val pending = infos().filterNot { it.state.isFinished }
            assertEquals("One independent pending worker, not a child blocked by the retry", 1, pending.size)
            val fresh = pending.single()
            assertNotEquals(previous, fresh.id)
            assertEquals(WorkInfo.State.ENQUEUED, fresh.state)
            assertTrue(work.getWorkInfoById(previous).await()?.state?.isFinished != false)

            val originalRows = PhoneFcmQueue(store.load()!!).waiting(System.currentTimeMillis())
            repeat(20) { assertTrue(PhoneFcmWork.enqueue(context, second, true)) }
            repeat(3) { PhoneFcmWork.recover(context) }
            assertEquals(listOf(fresh.id), infos().filterNot { it.state.isFinished }.map { it.id })
            assertEquals(originalRows, PhoneFcmQueue(store.load()!!).waiting(System.currentTimeMillis()))

            work.cancelUniqueWork(PhoneFcmWork.NAME).await()
            PhoneFcmWork.recover(context)
            val recovered = infos().filterNot { it.state.isFinished }.single()
            assertNotEquals(fresh.id, recovered.id)
            assertEquals(WorkInfo.State.ENQUEUED, recovered.state)
            assertEquals(2, PhoneFcmQueue(store.load()!!).waiting(System.currentTimeMillis()).size)
        } finally {
            try {
                work.cancelUniqueWork(PhoneFcmWork.NAME).await()
                NativeNotificationService.setEnabled(context, false)
                store.clear()
            } finally {
                try { WorkManagerTestInitHelper.closeWorkDatabase() }
                finally { WorkManagerImpl.setDelegate(previousManager); executor.shutdownNow() }
            }
        }
        assertSame(previousManager, WorkManager.getInstance(context))
    }
}

private class RetryingPushFixture(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = Result.retry()
}
