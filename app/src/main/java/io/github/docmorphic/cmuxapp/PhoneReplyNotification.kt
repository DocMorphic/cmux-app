package io.github.docmorphic.cmuxapp

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.notification.StatusBarNotification
import java.util.UUID

/** Android's input result is the only mutable data; destination identities are fixed in the base Intent. */
internal object PhoneReplyNotification {
    const val ACTION = "io.github.docmorphic.cmuxapp.REPLY_NOTIFICATION"
    const val TEXT = "cmux_reply_text"
    const val OFFERED = "cmux.reply_offered"
    fun intent(context: Context, route: String, action: String) = Intent(context, PhoneReplyReceiver::class.java)
        .setAction(ACTION).setData(Uri.Builder().scheme("cmux-reply").authority(context.packageName)
            .appendPath(route).appendPath(action).build())
    fun destination(context: Context, intent: Intent): Pair<String, String>? {
        if (intent.action != ACTION) return null
        val uri = intent.data ?: return null
        if (uri.scheme != "cmux-reply" || uri.authority != context.packageName || uri.pathSegments.size != 2 ||
            uri.query != null || uri.fragment != null) return null
        if (uri.pathSegments.any { runCatching { UUID.fromString(it).toString() != it }.getOrDefault(true) }) return null
        return uri.pathSegments[0] to uri.pathSegments[1]
    }
    private fun pending(context: Context, route: String, action: String, lookup: Boolean = false): PendingIntent? =
        PendingIntent.getBroadcast(context, 0, intent(context, route, action),
            (if (lookup) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT) or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
    fun action(context: Context, route: String, action: String): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_send), "Reply",
            checkNotNull(pending(context, route, action)))
            .addRemoteInput(RemoteInput.Builder(TEXT).setLabel("Message the agent…").build())
            .setAllowGeneratedReplies(false).apply {
                if (Build.VERSION.SDK_INT >= 28) setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            }.build()

    fun active(context: Context, route: String, action: String): StatusBarNotification? {
        val pending = pending(context, route, action, lookup = true) ?: return null
        return context.getSystemService(NotificationManager::class.java).activeNotifications.singleOrNull {
            it.tag == NativeNotificationDelivery.tag(route) && it.notification.channelId == NativeNotificationDelivery.ALERT_CHANNEL &&
                it.notification.actions.orEmpty().any { button -> button.actionIntent == pending }
        }
    }
    fun status(context: Context, previous: StatusBarNotification, title: String, detail: String, retry: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.activeNotifications.none { it.tag == previous.tag && it.id == previous.id }) return
        val value = Notification.Builder(context, NativeNotificationDelivery.ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(detail)
            .setContentIntent(previous.notification.contentIntent).setAutoCancel(true).setOnlyAlertOnce(true)
            .addExtras(android.os.Bundle().apply { putBoolean(OFFERED, true) })
            .setVisibility(Notification.VISIBILITY_PRIVATE).apply {
                if (Build.VERSION.SDK_INT >= 29) setAllowSystemGeneratedContextualActions(false)
                if (retry) previous.notification.actions.orEmpty().forEach { addAction(it) }
            }.build()
        manager.notify(previous.tag, previous.id, value)
    }
}
