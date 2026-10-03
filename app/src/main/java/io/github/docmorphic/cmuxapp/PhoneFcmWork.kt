package io.github.docmorphic.cmuxapp

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/** Fresh account membership only; never opens an Iroh/SSH/Mac connection. */
internal class PhoneFcmBackground(
    private val context: Context, private val store: NativeCredentialStore,
    private val teams: NativeAccountTeams,
    private val now: () -> Long = System::currentTimeMillis,
    private val audience: NativeMacBuildAudience? = null
) : AutoCloseable {
    private fun waiting(): List<QueuedPhonePush> {
        if (store.load()?.has(PhoneFcmQueue.KEY) != true) return emptyList()
        var pending = emptyList<QueuedPhonePush>()
        store.update { state ->
            if (!NativeNotificationService.isEnabled(context)) state.remove(PhoneFcmQueue.KEY)
            else pending = PhoneFcmQueue(state).waiting(now())
        }
        return pending
    }
    suspend fun runPass(): Boolean {
        if (waiting().isEmpty()) return false
        try {
            val membership = teams.refresh()
            val authority = membership.scope ?: return waiting().isNotEmpty()
            if (membership.error != null || !teams.isCurrent(authority)) return waiting().isNotEmpty()
            for (item in waiting()) {
                currentCoroutineContext().ensureActive()
                if (!teams.isCurrent(authority)) return waiting().isNotEmpty()
                val state = store.load() ?: return false
                val message = openQueuedPhonePush(item, state, membership, context.packageName, now())
                if (message != null) {
                    NativeNotificationDelivery(context).receivePush(message) {
                        teams.isCurrent(authority) && store.taskSession() == item.login && audience?.allowsPush(message.peer.tuple) != false
                    }
                }
                store.update { PhoneFcmQueue(it).remove(item, now()) }
            }
        } catch (failure: Exception) { currentCoroutineContext().ensureActive() }
        return waiting().isNotEmpty()
    }
    override fun close() = teams.close()
    companion object {
        fun create(context: Context): PhoneFcmBackground {
            val store = NativeCredentialStore(context)
            return PhoneFcmBackground(context, store, NativeAccountTeams(NativeAccount(store), store),
                audience = NativeMacBuildAudience.consumer)
        }
    }
}

class PhoneFcmWorker internal constructor(context: Context, parameters: WorkerParameters,
    private val background: () -> PhoneFcmBackground) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters,
        { PhoneFcmBackground.create(context.applicationContext) })
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        background().use { if (it.runPass()) Result.retry() else Result.success() }
    }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel = "cmux_push_delivery"
        NativeOngoingNotifications.register(applicationContext.getSystemService(NotificationManager::class.java), channel, "Processing cmux alerts")
        val notification = Notification.Builder(applicationContext, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Checking cmux alerts").setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(6, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(6, notification)
    }
}

internal object PhoneFcmWork {
    const val NAME = "cmux.push.receive"
    suspend fun enqueue(context: Context, raw: String, highPriority: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!NativeNotificationService.isEnabled(context) || !PhoneFcmQueue.valid(raw)) return@withContext false
        val store = NativeCredentialStore(context)
        if (store.taskSession() == null) return@withContext false
        var result = PhoneFcmQueue.EnqueueResult.REJECTED
        store.update { if (NativeNotificationService.isEnabled(context)) result = PhoneFcmQueue(it).offer(raw, System.currentTimeMillis(), highPriority) }
        if (result != PhoneFcmQueue.EnqueueResult.REJECTED)
            recover(context, replacePending = result == PhoneFcmQueue.EnqueueResult.NEW ||
                result == PhoneFcmQueue.EnqueueResult.PRIORITY_UPGRADE)
        result != PhoneFcmQueue.EnqueueResult.REJECTED
    }
    suspend fun recover(context: Context, replacePending: Boolean = false) = withContext(Dispatchers.IO) {
        val store = NativeCredentialStore(context)
        if (!NativeNotificationService.isEnabled(context)) return@withContext
        val state = store.load() ?: return@withContext
        val pending = PhoneFcmQueue(state).waiting(System.currentTimeMillis())
        if (pending.isEmpty()) return@withContext
        val request = OneTimeWorkRequestBuilder<PhoneFcmWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).addTag(NAME)
            .apply { if (pending.any { it.highPriority }) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }.build()
        // New ciphertext must not wait behind an earlier membership retry. The
        // durable inbox survives cancellation, and delivery handles redelivery.
        // Duplicates/startup keep pending work but recreate a missing worker.
        val policy = if (replacePending) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request).await()
    }
}
