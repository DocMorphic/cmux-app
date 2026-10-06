package io.github.docmorphic.cmuxapp

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.wireguard.android.backend.GoBackend
import kotlinx.coroutines.CompletableDeferred

/** The upstream service owns the TUN/native handle; cmux adds lifetime, foreground
 * notification and an explicit-start reservation. Never enrolled by boot/always-on. */
class NativeCloudVpnService : GoBackend.VpnService() {
    @Volatile internal var alive = false
        private set
    internal val destroyed = CompletableDeferred<Unit>()
    private var handle: NativeAppConnections.Handle? = null
    private var startup: Startup? = null
    private var foreground = false
    private val main = Handler(Looper.getMainLooper())
    override fun onCreate() {
        synchronized(nativeLock) { super.onCreate(); alive = true }
        val manager = getSystemService(NotificationManager::class.java)
        NativeOngoingNotifications.register(manager, CHANNEL, "cmux Cloud VPN")
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification(false), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            else startForeground(ID, notification(false))
            foreground = true
        } catch (_: Exception) { requestStop() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            if (intent.getStringExtra(TOKEN) == startup?.id) handle?.connections?.cloudVpn?.disable()
            return START_NOT_STICKY
        }
        val ticket = synchronized(reservationLock) { pending?.takeIf { it.id == intent?.getStringExtra(TOKEN) } }
        if (intent?.action != START || ticket == null || !ticket.current() || !alive || !foreground) { requestStop(); return START_NOT_STICKY }
        try {
            val acquired = handle ?: NativeAppConnections.acquire(applicationContext).also { handle = it }
            check(acquired.connections.cloudVpn.platform === ticket.platform)
            startup = ticket
            if (!ticket.deliver(this)) requestStop()
        } catch (_: Exception) {
            ticket.ready.completeExceptionally(IllegalStateException("Cloud VPN service could not start"))
            requestStop()
        }
        return START_NOT_STICKY
    }
    override fun onRevoke() {
        handle?.connections?.cloudVpn?.disable()
        requestStop()
    }
    override fun onDestroy() {
        synchronized(nativeLock) { alive = false; super.onDestroy() }
        startup?.ready?.completeExceptionally(IllegalStateException("Cloud VPN service stopped"))
        startup?.platform?.serviceEnded(this)
        destroyed.complete(Unit)
        handle?.close(); handle = null
    }
    internal fun requestStop() { main.post { stopSelf() } }
    internal fun showConnected() { main.post {
        if (alive) getSystemService(NotificationManager::class.java).notify(ID, notification(true))
    } }
    private fun notification(connected: Boolean): Notification {
        val open = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_workspace_cloud)
            .setContentTitle("cmux Cloud VPN").setContentText(if (connected) "Private Cloud services connected" else "Connecting to private Cloud services…")
            .setOngoing(true).setContentIntent(open).setCategory(Notification.CATEGORY_SERVICE).setShowWhen(false)
        startup?.let { ticket ->
            val stop = PendingIntent.getService(this, ID, Intent(this, NativeCloudVpnService::class.java)
                .setAction(STOP).putExtra(TOKEN, ticket.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
        }
        return builder.build()
    }
    internal class Startup(val id: String, val platform: NativeCloudVpnPlatform, val current: () -> Boolean) {
        val ready = CompletableDeferred<NativeCloudVpnService>()
        @Volatile var service: NativeCloudVpnService? = null
            private set
        private var retired = false
        @Synchronized fun deliver(value: NativeCloudVpnService): Boolean {
            if (retired || !current()) return false
            service = value; ready.complete(value); return true
        }
        @Synchronized fun retire() { retired = true; if (!ready.isCompleted) ready.cancel() }
    }
    companion object {
        internal val nativeLock = Any()
        private val reservationLock = Any()
        private var pending: Startup? = null
        internal fun register(startup: Startup) = synchronized(reservationLock) {
            check(pending == null) { "Cloud VPN startup is already pending" }; pending = startup
        }
        internal fun unregister(startup: Startup) = synchronized(reservationLock) {
            startup.retire(); if (pending === startup) pending = null
        }
        internal const val START = "io.github.docmorphic.cmuxapp.CLOUD_VPN_START"
        internal const val STOP = "io.github.docmorphic.cmuxapp.CLOUD_VPN_STOP"
        internal const val TOKEN = "startup"
        private const val CHANNEL = "cmux_cloud_vpn"
        private const val ID = 4817
    }
}
