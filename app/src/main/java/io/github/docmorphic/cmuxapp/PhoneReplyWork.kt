package io.github.docmorphic.cmuxapp

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

internal interface PhoneReplyBackground : AutoCloseable {
    suspend fun runPass(): Boolean // True means unexpired pending work remains.
}

/** Background HTTPS only. This owner never starts Iroh, SSH or a Mac terminal connection. */
internal class NativePhoneReplyBackground(
    private val store: NativeCredentialStore, private val teams: NativeAccountTeams,
    token: suspend () -> String?, origin: HttpUrl, private val notices: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) : PhoneReplyBackground {
    private val relay = PhoneReplyRelay(origin, token, ::permits, now = now)
    private fun waiting(): List<PreparedPhoneReply> {
        if (store.load()?.has(PhoneReplyOutbox.KEY) != true) return emptyList()
        var items = emptyList<PreparedPhoneReply>()
        store.update { items = PhoneReplyOutbox(it).waiting(now()) }
        return items.filterNot { it.directOnly }
    }
    private fun permits(reply: PreparedPhoneReply): Boolean {
        val state = store.load() ?: return false
        if (!PhoneReplyOutbox(state).permits(reply)) return false
        val membership = teams.state.value
        val scope = membership.scope ?: return false
        if (!teams.isCurrent(scope) || scope.login != reply.login || membership.userId != reply.peer.tuple.accountID ||
            membership.teams.none { it.id == reply.teamID }) return false
        val selected = NativeTeamScope(reply.login, scope.userId, reply.teamID, scope.generation)
        val mac = store.pairedMacs().singleOrNull { it.ownsOrigin(reply.origin) } ?: return false
        return NativePairingRecords.usable(mac, selected, TailscaleGrantStore({ state }, { error("read only") }))
    }
    override suspend fun runPass(): Boolean {
        try {
            if (waiting().isEmpty()) return false
            teams.refresh()
            drainPhoneReplies(pending = {
                var items = emptyList<PreparedPhoneReply>()
                store.update { items = PhoneReplyOutbox(it).pending(now()) }
                items
            }, permits = ::permits, send = relay::send, finish = { reply, result ->
                store.update { state -> if (permits(reply)) PhoneReplyOutbox(state).finish(reply, result, now()) }
            })
        } catch (_: Exception) { currentCoroutineContext().ensureActive() }
        finally { notices() }
        return waiting().isNotEmpty()
    }
    override fun close() { relay.close(); teams.close() }
    companion object {
        // Matches active iOS AppCompositionRoot -> PresenceClient production-auth resolution.
        private val ORIGIN = "https://presence.cmux.dev/".toHttpUrl()
        fun create(context: Context): NativePhoneReplyBackground {
            val store = NativeCredentialStore(context); val account = NativeAccount(store)
            return NativePhoneReplyBackground(store, NativeAccountTeams(account, store), { account.accessToken() }, ORIGIN,
                { PhoneReplyNotices(context).sync() })
        }
    }
}

class PhoneReplyWorker internal constructor(context: Context, parameters: WorkerParameters,
    private val background: () -> PhoneReplyBackground) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters,
        { NativePhoneReplyBackground.create(context.applicationContext) })
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        background().use { runtime -> if (runtime.runPass()) Result.retry() else Result.success() }
    }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel = NativeOngoingNotifications.REPLY
        NativeOngoingNotifications.register(applicationContext.getSystemService(NotificationManager::class.java),
            channel, "Sending cmux replies")
        val notification = Notification.Builder(applicationContext, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sending cmux reply").setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(5, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(5, notification)
    }
}

/** No network constraint: expiry notices can be shown even when a send worker cannot run offline. */
class PhoneReplyNoticeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = NativeCredentialStore(applicationContext)
        if (store.load()?.has(PhoneReplyOutbox.KEY) == true) store.update { PhoneReplyOutbox(it).prune() }
        PhoneReplyNotices(applicationContext).sync()
        Result.success()
    }
}

internal object PhoneReplyWork {
    const val SEND = "cmux.reply.send"
    const val NOTICES = "cmux.reply.notice"

    /** Caller awaits durable job enqueue before finishing the user-action broadcast. */
    suspend fun enqueue(context: Context, reply: PreparedPhoneReply): ReplyEnqueueResult = withContext(Dispatchers.IO) {
        val store = NativeCredentialStore(context)
        var result = ReplyEnqueueResult.RETIRED
        store.update { result = PhoneReplyOutbox(it).enqueue(reply, System.currentTimeMillis()) }
        if (result in setOf(ReplyEnqueueResult.QUEUED, ReplyEnqueueResult.DUPLICATE)) recover(context)
        result
    }

    suspend fun recover(context: Context) = withContext(Dispatchers.IO) {
        val store = NativeCredentialStore(context)
        if (store.load()?.has(PhoneReplyOutbox.KEY) != true) { PhoneReplyNotices(context).sync(); return@withContext }
        var pending = emptyList<PreparedPhoneReply>()
        store.update { pending = PhoneReplyOutbox(it).waiting(System.currentTimeMillis()) }
        val work = WorkManager.getInstance(context)
        for (reply in pending) {
            val failure = OneTimeWorkRequestBuilder<PhoneReplyNoticeWorker>()
                .setInitialDelay((reply.createdAtMillis + 130_000 - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .addTag(NOTICES).build()
            work.enqueueUniqueWork(PhoneReplyNotices.key(reply), ExistingWorkPolicy.KEEP, failure).await()
        }
        if (pending.any { !it.directOnly }) {
            val send = OneTimeWorkRequestBuilder<PhoneReplyWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS).addTag(SEND).build()
            // KEEP can lose a new action arriving just as a running drain finishes.
            work.enqueueUniqueWork(SEND, ExistingWorkPolicy.APPEND_OR_REPLACE, send).await()
        }
        PhoneReplyNotices(context).sync()
    }
}
