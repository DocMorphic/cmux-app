package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object PhoneHelperMaintenanceWork {
    private const val NAME = "cmux.push.maintenance"
    private val serial = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = okhttp3.OkHttpClient()
    private var observing = false
    private fun ledger(context: Context) = NativeCredentialStore(context, "phone_helper_maintenance")

    /** Called under the shared store lock, before publishing a primary account mutation. No network or scheduling. */
    fun preserve(context: Context, account: JSONObject?, stageRetirement: Boolean = false) {
        if (!stageRetirement && account?.has(PhoneHelperEnrollmentState.RECEIPTS) != true) return
        val store = ledger(context); val state = store.load() ?: JSONObject(); val before = state.toString()
        PhoneHelperMaintenanceQueue(state).apply {
            preserve(account)
            if (stageRetirement) this.stageRetirement(account ?: JSONObject())
        }
        if (before != state.toString()) store.update {
            if (state.has(PhoneHelperMaintenanceQueue.KEY)) it.put(PhoneHelperMaintenanceQueue.KEY, state.getJSONArray(PhoneHelperMaintenanceQueue.KEY))
            else it.remove(PhoneHelperMaintenanceQueue.KEY)
        }
    }
    private fun access(context: Context, write: Boolean, action: (PhoneHelperMaintenanceQueue) -> Unit): Boolean {
        val credentials = NativeCredentialStore(context); val storage = ledger(context)
        return synchronized(credentials.accountStateLock) {
            val account = credentials.load() ?: JSONObject(); val beforeAccount = account.toString()
            val state = storage.load() ?: JSONObject(); val beforeState = state.toString()
            val provider = PhoneFcmTokens.setup(context)
            val queue = PhoneHelperMaintenanceQueue(state)
            queue.reconcile(account, provider.grant, provider.token)
            action(queue)
            queue.reconcile(account, provider.grant, provider.token)
            val changed = state.toString() != beforeState
            if (write) {
                // Commit the authoritative remote generation first. A later pass repairs a failed account projection.
                if (state.toString() != beforeState) storage.update {
                    if (state.has(PhoneHelperMaintenanceQueue.KEY)) it.put(PhoneHelperMaintenanceQueue.KEY, state.getJSONArray(PhoneHelperMaintenanceQueue.KEY))
                    else it.remove(PhoneHelperMaintenanceQueue.KEY)
                }
                if (account.toString() != beforeAccount) credentials.updateFromMaintenance {
                    for (key in listOf(PhoneHelperEnrollmentState.RECEIPTS, PhonePushHelperState.KEY)) {
                        if (account.has(key)) it.put(key, account.get(key)) else it.remove(key)
                    }
                }
            }
            changed
        }
    }
    @Synchronized fun observe(context: Context) {
        if (observing) return
        observing = true; val app = context.applicationContext
        scope.launch {
            NativeCredentialStore(app).revisions.collectLatest {
                delay(100)
                try { recover(app) } catch (_: Exception) { currentCoroutineContext().ensureActive() }
            }
        }
    }
    suspend fun recover(context: Context) = withContext(Dispatchers.IO) {
        var pending = false
        val changed = access(context, true) { pending = it.pending().isNotEmpty() }
        if (!pending) return@withContext
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, if (changed) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PhoneHelperMaintenanceWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).build()).await()
    }
    suspend fun run(context: Context): Boolean = serial.withLock {
        withContext(Dispatchers.IO) {
            PhoneHelperMaintenanceRecovery(transaction = { access(context, true, it) }, inspect = { access(context, false, it) },
                send = { endpoint, permits, step, body -> PhoneHelperHttp(endpoint, permits, http).use { it.send(step, body) } }).runPass()
        }
    }
}

class PhoneHelperMaintenanceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        if (PhoneHelperMaintenanceWork.run(applicationContext)) Result.retry() else Result.success()
    } catch (_: Exception) { currentCoroutineContext().ensureActive(); Result.retry() }
}
