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
    suspend fun prepare(context: Context, review: PhoneHelperOfferReview, permits: () -> Boolean): String = withContext(Dispatchers.IO) {
        val store = NativeCredentialStore(context)
        var id: String? = null
        synchronized(store.accountStateLock) {
            check(permits()) { "Account or team changed" }
            val token = PhoneFcmTokens.snapshot(context)
            store.update { id = review.prepare(it, token, System.currentTimeMillis()).id }
        }
        // Leave a successor even when an older attempt is finishing.
        recover(context, replace = true)
        checkNotNull(id)
    }
    suspend fun cancel(context: Context, team: NativeTeamScope, id: String, permits: () -> Boolean) = withContext(Dispatchers.IO) {
        val store = NativeCredentialStore(context)
        synchronized(store.accountStateLock) {
            check(permits())
            store.update { state -> PhoneHelperEnrollmentState(state).apply {
                pending().singleOrNull { it.id == id && it.team.login == team.login && it.team.userId == team.userId && it.team.teamId == team.teamId }
                    ?.let(::remove)
            } }
        }
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
