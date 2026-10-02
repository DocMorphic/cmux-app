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
    private val now: () -> Long = System::currentTimeMillis
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
                        teams.isCurrent(authority) && store.taskSession() == item.login
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
            return PhoneFcmBackground(context, store, NativeAccountTeams(NativeAccount(store), store))
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
        var queued = false
        store.update { if (NativeNotificationService.isEnabled(context)) queued = PhoneFcmQueue(it).enqueue(raw, System.currentTimeMillis()) }
        if (queued) recover(context, highPriority)
        queued
    }
    suspend fun recover(context: Context, highPriority: Boolean = false) = withContext(Dispatchers.IO) {
        val store = NativeCredentialStore(context)
        if (!NativeNotificationService.isEnabled(context) || store.load()?.has(PhoneFcmQueue.KEY) != true) return@withContext
        val request = OneTimeWorkRequestBuilder<PhoneFcmWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).addTag(NAME)
            .apply { if (highPriority) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }.build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request).await()
    }
}
