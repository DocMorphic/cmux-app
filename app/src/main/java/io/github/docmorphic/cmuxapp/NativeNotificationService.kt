package io.github.docmorphic.cmuxapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Keeps the Mac notification feed reachable while the user enables background delivery. */
class NativeNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val feedMutex = Mutex()
    private var worker: Job? = null
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private val seen by lazy { getSharedPreferences("native_notification_ids", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(
            STATUS_CHANNEL, "cmux connection", NotificationManager.IMPORTANCE_LOW
        ))
        manager.createNotificationChannel(NotificationChannel(
            ALERT_CHANNEL, "cmux agent notifications", NotificationManager.IMPORTANCE_HIGH
        ))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(STATUS_ID, statusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else startForeground(STATUS_ID, statusNotification())
        if (worker == null || worker?.isActive != true) worker = scope.launch { monitor() }
        return START_STICKY
    }

    private suspend fun monitor() {
        val store = NativeCredentialStore(applicationContext)
        val account = NativeAccount(store)
        while (scope.isActive) {
            var client: MobileRpcClient? = null
            try {
                val code = store.load()?.optString("pairing_code").orEmpty()
                val pairing = PairingCodeParser.parse(code).getOrNull() as? PairingCode.Tailscale
                if (pairing == null) { delay(15_000); continue }
                if (!account.isSignedIn()) { delay(15_000); continue }
                pairing.stackUserId?.let { expected ->
                    check(account.userId() == expected) { "cmux account does not match Mac" }
                }
                for (route in pairing.routes) {
                    val target = runCatching { TailscaleRoute.resolve(applicationContext, route) }.getOrNull() ?: continue
                    val candidate = MobileRpcClient(target.route, account::accessToken,
                        socketFactory = target.socketFactory)
                    try { candidate.connect(); candidate.hostStatus(); client = candidate; break }
                    catch (_: Exception) { candidate.close() }
                }
                val active = client ?: error("cmux Mac unavailable")
                val changes = scope.launch {
                    active.events.collect { event ->
                        if (event.topic == "notification.feed.changed") {
                            runCatching { refresh(active) }
                        }
                    }
                }
                try {
                    active.subscribe(listOf("notification.feed.changed"))
                    while (scope.isActive) {
                        refresh(active)
                        delay(30_000)
                    }
                } finally { changes.cancel() }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                delay(10_000)
            } finally { client?.close() }
        }
    }

    private suspend fun refresh(client: MobileRpcClient) = feedMutex.withLock {
        val feed = client.notifications().optJSONArray("notifications") ?: return@withLock
        val existing = seen.getStringSet("seen", null)
        val ids = mutableListOf<String>()
        for (index in 0 until minOf(feed.length(), 500)) {
            val item = feed.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            ids += id
            if (existing != null && id !in existing && !item.optBoolean("is_read")) postAlert(item)
        }
        val updated = (ids + existing.orEmpty()).distinct().take(512).toSet()
        seen.edit().putStringSet("seen", updated).apply()
    }

    private fun postAlert(item: JSONObject) {
        val id = item.optString("id")
        val workspaceId = item.optString("workspace_id")
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("notification_workspace_id", workspaceId)
        }
        val pending = PendingIntent.getActivity(this, id.hashCode(), launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = item.optString("title").take(160).ifBlank { "cmux" }
        val body = item.optString("body").take(1000)
        val notification = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        manager.notify((id.hashCode() and Int.MAX_VALUE).coerceAtLeast(2), notification)
    }

    private fun statusNotification(): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("cmux")
            .setContentText("Listening for Mac notifications")
            .setContentIntent(launch).setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val STATUS_CHANNEL = "cmux_connection"
        private const val ALERT_CHANNEL = "cmux_alerts"
        private const val STATUS_ID = 1

        fun isEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences("native_notification_settings", MODE_PRIVATE)
                .getBoolean("background_enabled", false)

        fun setEnabled(context: android.content.Context, enabled: Boolean) {
            context.getSharedPreferences("native_notification_settings", MODE_PRIVATE)
                .edit().putBoolean("background_enabled", enabled).apply()
            val intent = Intent(context, NativeNotificationService::class.java)
            if (enabled) context.startForegroundService(intent) else context.stopService(intent)
        }
    }
}
