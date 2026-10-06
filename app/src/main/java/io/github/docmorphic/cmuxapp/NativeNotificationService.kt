package io.github.docmorphic.cmuxapp

import android.app.Notification
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

/** One independently reconnecting feed per saved Mac, including when another Mac is on screen. */
class NativeNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var connectionsHandle: NativeAppConnections.Handle? = null
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private val delivery by lazy { NativeNotificationDelivery(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        connectionsHandle = NativeAppConnections.acquire(applicationContext)
        NativeOngoingNotifications.register(manager, STATUS_CHANNEL, "cmux connection")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(STATUS_ID, statusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else startForeground(STATUS_ID, statusNotification())
        if (!isEnabled(this)) { connectionsHandle?.connections?.setProbeActive(this, false); stopSelf(); return START_NOT_STICKY }
        connectionsHandle?.connections?.setProbeActive(this, true)
        if (worker?.isActive != true) worker = scope.launch {
            try { PhoneFcmWork.recover(applicationContext) }
            catch (failure: Exception) { if (failure is CancellationException) throw failure }
            monitor()
        }
        return START_STICKY
    }

    private suspend fun monitor() = coroutineScope {
        val connections = checkNotNull(connectionsHandle).connections
        val store = connections.store
        val account = connections.account
        val connector = connections.connector
        val workers = mutableMapOf<NativeCredentialStore.PairedMac, Job>()
        val workerRoutes = mutableMapOf<NativeCredentialStore.PairedMac, String?>()
        var previousOrigins: Set<String>? = null
        try {
            while (isActive && isEnabled(this@NativeNotificationService)) {
                val routeKeys = connections.native.state.value.connectionKeys
                val localKeys = connections.native.state.value.localConnectionKeys
                fun routeKey(mac: NativeCredentialStore.PairedMac) =
                    localKeys[NativeMacIdentity(canonicalMacDeviceId(mac.deviceId), mac.instanceTag)] ?:
                        (PairingCodeParser.parse(mac.code).getOrNull() as? PairingCode.Iroh)?.endpointId?.let(routeKeys::get)
                val paired = if (account.isSignedIn()) store.visiblePairedMacs().filter {
                    it.deviceId.isNotBlank() && connector.allowsSaved(it)
                }.toSet() else emptySet()
                workers.keys.toList().filter { it !in paired || workers[it]?.isActive != true || workerRoutes[it] != routeKey(it) }.forEach {
                    workers.remove(it)?.cancel()
                    workerRoutes.remove(it)
                }
                val origins = paired.map { it.origin }.toSet()
                if (origins != previousOrigins) {
                    delivery.prune(origins)
                    previousOrigins = origins
                }
                for (mac in paired) if (mac !in workers) {
                    workerRoutes[mac] = routeKey(mac)
                    workers[mac] = launch {
                    while (isActive) {
                        var client: MobileRpcClient? = null
                        try {
                            val login = store.taskSession()
                            client = connector.connectSaved(mac, account)
                            val active = client
                            val status = active.hostStatus()
                            mac.requireMatchingHost(status)
                            if (connector.refreshSavedIdentity(mac, active, status)) break
                            val isCurrent = {
                                isActive && login != null && store.taskSession() == login &&
                                    isEnabled(this@NativeNotificationService) && account.isSignedIn() &&
                                    store.visiblePairedMacs().contains(mac) && connector.allowsSaved(mac)
                            }
                            coroutineScope {
                                val team = connections.teams.state.value.scope
                                if (team != null) launch {
                                    val permits = { isCurrent() && connections.teams.isCurrent(team) }
                                    exchangePhonePushKeys(active, status, mac, team, packageName, permits,
                                        identity = {
                                            var identity: PhonePushIdentity? = null
                                            store.update { if (permits()) identity = PhonePushKeyState(it).identity(team.login) }
                                            checkNotNull(identity) { "Account changed during push setup" }
                                        }, pin = { peer ->
                                            store.update { if (permits()) PhonePushKeyState(it).pin(team, mac.origin, peer) }
                                        })
                                }
                                monitorNativeNotificationFeed(active, NativeNotificationSync(
                                    delivered = { delivery.deliveredIDs(mac.origin, isCurrent) },
                                    handled = { delivery.clearHandled(mac.origin, it, isCurrent) }
                                )) { feed ->
                                    val team = connections.teams.state.value.scope?.takeIf(connections.teams::isCurrent)
                                    val displayName = team?.let { NativeMacAppearanceStore.create(this@NativeNotificationService, it)
                                        .state.value.name(mac) } ?: mac.name
                                    delivery.refresh(mac.origin, displayName, feed, isCurrent)
                                }
                            }
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                        } finally { client?.close() }
                        delay(10_000)
                    }
                }
                }
                withTimeoutOrNull(2_000) { connections.native.state.first { it.connectionKeys != routeKeys || it.localConnectionKeys != localKeys } }
            }
        } finally {
            workers.values.forEach { it.cancel() }
            stopSelf()
        }
    }

    private fun statusNotification(): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("cmux")
            .setContentText("Listening for saved Mac notifications")
            .setContentIntent(launch).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        connectionsHandle?.connections?.setProbeActive(this, false)
        connectionsHandle?.close(); connectionsHandle = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val STATUS_CHANNEL = NativeOngoingNotifications.CONNECTION
        private const val STATUS_ID = 1
        fun isEnabled(context: android.content.Context): Boolean =
            context.getSharedPreferences("native_notification_settings", MODE_PRIVATE)
                .getBoolean("background_enabled", false)

        fun setEnabled(context: android.content.Context, enabled: Boolean) {
            // Publish opt-out before waiting for delivery cleanup so an in-flight
            // batch stops promptly instead of keeping the UI behind the whole feed.
            val prefs = context.getSharedPreferences("native_notification_settings", MODE_PRIVATE)
            val previous = prefs.getBoolean("background_enabled", false)
            check(prefs.edit().putBoolean("background_enabled", enabled).commit()) { "Could not save notification setting" }
            try {
                val intent = Intent(context, NativeNotificationService::class.java)
                if (enabled) context.startForegroundService(intent) else {
                    context.stopService(intent)
                    val credentials = NativeCredentialStore(context)
                    if (credentials.load()?.has(PhoneFcmQueue.KEY) == true) credentials.update { it.remove(PhoneFcmQueue.KEY) }
                    NativeNotificationDelivery(context).prune(emptySet())
                }
            } catch (failure: Exception) {
                prefs.edit().putBoolean("background_enabled", previous).commit()
                throw failure
            }
        }
    }
}
