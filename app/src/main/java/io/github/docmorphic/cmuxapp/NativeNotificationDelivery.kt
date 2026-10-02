package io.github.docmorphic.cmuxapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.UUID

internal class NativeNotificationDelivery(private val context: Context) {
    private val store = NativeCredentialStore(context, "native_notification_state")
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun destination(routeId: String): NotificationDestination? = synchronized(lock) {
        store.load()?.let { NativeNotificationLedger(it).destination(routeId) }
    }

    fun refresh(origin: String, computer: String, feed: List<NativeNotification>, isCurrent: () -> Boolean) = synchronized(lock) {
        if (!isCurrent()) return@synchronized
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL,
            "cmux agent notifications", NotificationManager.IMPORTANCE_HIGH))
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(ALERT_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return@synchronized
        var baseline = false
        var pending = emptyList<NativeNotification>()
        var readRoutes = emptyList<String>()
        val mac = NativeCredentialStore(context).pairedMacs().singleOrNull { it.origin == origin }
        store.update {
            val ledger = NativeNotificationLedger(it)
            mac?.let { ledger.coalesce(it.origin, it.previousOrigins) }
            baseline = ledger.baseline(origin, feed)
            val readIds = feed.filter { item -> item.isRead }.map { item -> item.id }
            ledger.acknowledge(origin, readIds)
            readRoutes = ledger.destinations().filter { route ->
                route.origin == origin && route.notificationId in readIds
            }.map { route -> route.routeId }
            pending = ledger.unseen(origin, feed)
        }
        readRoutes.forEach(::cancel)
        if (baseline) return@synchronized
        for (item in pending) {
            if (!isCurrent()) break
            var route: NotificationDestination? = null
            store.update { route = NativeNotificationLedger(it).stage(origin, item, NativeCredentialStore(context).taskSession()) }
            val destination = requireNotNull(route)
            // Store first: even immediate taps and process death have a durable destination.
            if (!isCurrent()) break
            manager.notify(tag(destination.routeId), ALERT_ID, buildAlert(destination, computer, item))
            store.update { NativeNotificationLedger(it).acknowledge(origin, listOf(item.id)) }
        }
        val validOrigins = NativeCredentialStore(context).pairedMacs().map { it.origin }.toSet()
        prune(validOrigins)
    }

    fun deliveredIDs(origin: String, isCurrent: () -> Boolean): List<String> = synchronized(lock) {
        if (!isCurrent()) return@synchronized emptyList()
        val active = manager.activeNotifications.filter { it.notification.channelId == ALERT_CHANNEL }
            .map { it.tag }.toSet()
        val login = NativeCredentialStore(context).taskSession()
        val ids = store.load()?.let { NativeNotificationLedger(it).destinations() }.orEmpty()
            .filter { it.origin == origin && it.login == login && tag(it.routeId) in active }
            .map { it.notificationId }.distinct()
        if (isCurrent()) ids else emptyList()
    }

    fun clearHandled(origin: String, ids: List<String>, isCurrent: () -> Boolean) = synchronized(lock) {
        if (ids.isEmpty() || !isCurrent()) return@synchronized
        var routes = emptyList<String>()
        store.update {
            // Credential updates share this storage lock. Recheck inside the transaction
            // so a login/forget change while waiting cannot adopt the new account.
            if (!isCurrent()) return@update
            val login = NativeCredentialStore(context).taskSession()
            val ledger = NativeNotificationLedger(it)
            ledger.rememberHandled(origin, ids)
            routes = ledger.destinations().filter { route ->
                route.origin == origin && route.login == login && route.notificationId in ids
            }.map { route -> route.routeId }
        }
        // Programmatic cancellation must not invoke the user-swipe DeleteIntent.
        routes.forEach(::cancel)
    }

    fun prune(validOrigins: Set<String>) = synchronized(lock) {
        val state = store.load()
        val before = state?.toString()
        val paired = NativeCredentialStore(context).pairedMacs()
        if (state != null) {
            val ledger = NativeNotificationLedger(state)
            paired.filter { it.origin in validOrigins }.forEach { ledger.coalesce(it.origin, it.previousOrigins) }
        }
        val removed = state?.let { NativeNotificationLedger(it).prune(validOrigins) }.orEmpty()
        if (state != null && state.toString() != before) {
            store.update { it.put("origins", state.getJSONObject("origins")).put("routes", state.getJSONObject("routes"))
                .put("deferred_handled", state.getJSONObject("deferred_handled")) }
        }
        removed.forEach(::cancel)
        // Retire the former workspace-only intents and alerts whose encrypted route was lost.
        val retained = state?.let { NativeNotificationLedger(it).destinations().map { route -> tag(route.routeId) }.toSet() }.orEmpty()
        manager.activeNotifications.filter { it.notification.channelId == ALERT_CHANNEL && it.tag !in retained &&
            it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    fun cancel(routeId: String) { manager.cancel(tag(routeId), ALERT_ID) }

    internal fun buildAlert(destination: NotificationDestination, computer: String, item: NativeNotification): Notification {
        val pending = PendingIntent.getActivity(context, 0, launchIntent(context, destination.routeId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(context, 0,
            launchIntent(context, destination.routeId).setClass(context, NativeNotificationDismissReceiver::class.java)
                .setAction(NativeNotificationDismissReceiver.ACTION).setFlags(0),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = NativeSearchText.prefix(item.body.ifBlank { item.subtitle.orEmpty() }, 1000)
        return Notification.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(NativeSearchText.prefix(item.title.ifBlank { "cmux" }, 160))
            .setSubText(computer).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending).setDeleteIntent(dismiss).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build()
    }

    companion object {
        internal val lock = Any()
        const val ALERT_CHANNEL = "cmux_alerts"
        private const val ALERT_ID = 2
        private fun tag(routeId: String) = "cmux.native.$routeId"
        fun launchIntent(context: Context, routeId: String) = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.Builder().scheme("cmux-notification").authority(context.packageName).appendPath(routeId).build()
        }
        fun routeFromIntent(context: Context, intent: Intent?): String? {
            val uri = intent?.data ?: return null
            if (uri.scheme != "cmux-notification" || uri.authority != context.packageName ||
                uri.pathSegments.size != 1 || uri.query != null || uri.fragment != null) return null
            val value = uri.pathSegments.single()
            return value.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
        }
    }
}
