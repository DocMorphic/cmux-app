package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object PhoneHelperEnrollments {
    private const val NAME = "cmux.push.enrollment"
    private val serial = Mutex()
    private val http = okhttp3.OkHttpClient()

    /** Called only after the user confirms a displayed Mac offer and cloud push consent. */
    suspend fun prepare(context: Context, rawOffer: String, team: NativeTeamScope, origin: String): String = withContext(Dispatchers.IO) {
        val store = NativeCredentialStore(context)
        var id: String? = null
        synchronized(store.accountStateLock) {
            val token = checkNotNull(PhoneFcmTokens.snapshot(context)) { "Enable cloud push before pairing a helper" }
            store.update { id = PhoneHelperEnrollmentState(it).prepare(rawOffer, team, origin, token).id }
        }
        // A new offer must leave work even if a previous pass is about to finish.
        recover(context, replace = true)
        checkNotNull(id)
    }
    suspend fun recover(context: Context, replace: Boolean = false) = withContext(Dispatchers.IO) {
        val store = NativeCredentialStore(context)
        if (store.load()?.has(PhoneHelperEnrollmentState.KEY) != true) return@withContext
        var pending = false
        synchronized(store.accountStateLock) {
            val token = PhoneFcmTokens.snapshot(context)
            store.update { PhoneHelperEnrollmentState(it).apply { retainForToken(token); pending = pending().isNotEmpty() } }
        }
        if (!pending) return@withContext
        WorkManager.getInstance(context).enqueueUniqueWork(NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PhoneHelperEnrollmentWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()).await()
    }
    suspend fun run(context: Context): Boolean = serial.withLock {
        withContext(Dispatchers.IO) {
            val store = NativeCredentialStore(context)
            fun access(write: Boolean, action: (JSONObject, PhoneFcmTokenSnapshot?) -> Unit) = synchronized(store.accountStateLock) {
                val token = PhoneFcmTokens.snapshot(context)
                if (write) store.update { action(it, token) }
                else action(store.load() ?: JSONObject(), token)
            }
            PhoneHelperEnrollmentRecovery(
                transaction = { access(true, it) }, inspect = { access(false, it) },
                send = { endpoint, permits, step, payload ->
                    PhoneHelperHttp(endpoint, permits, http).use { it.send(step, payload) }
                }
            ).runPass()
        }
    }
}

class PhoneHelperEnrollmentWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        if (PhoneHelperEnrollments.run(applicationContext)) Result.retry() else Result.success()
    } catch (_: Exception) { currentCoroutineContext().ensureActive(); Result.retry() }
}
