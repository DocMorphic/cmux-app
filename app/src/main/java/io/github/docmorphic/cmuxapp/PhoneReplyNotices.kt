package io.github.docmorphic.cmuxapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import java.security.MessageDigest

/** Content-free failure notices: no reply text, workspace names or credentials enter system state. */
internal class PhoneReplyNotices(private val context: Context) {
    private val store = NativeCredentialStore(context)
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun sync(now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val snapshot = store.load()
        val receipts = snapshot?.let { PhoneReplyOutbox(it).receipts(now) }.orEmpty()
        val outstanding = receipts.filter { it.status != "accepted" }.associateBy(::key)
        manager.activeNotifications.filter { it.notification.channelId == CHANNEL && it.tag !in outstanding.keys }
            .forEach { manager.cancel(it.tag, it.id) }
        if (!manager.areNotificationsEnabled()) return@synchronized
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "cmux reply status", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return@synchronized
        val marked = snapshot?.optJSONArray(SEEN)?.let { array -> (0 until array.length()).map { array.optString(it) }.toSet() }.orEmpty()
        for ((key, receipt) in outstanding) {
            if (key in marked) continue
            // Revalidate and publish under the credential transaction so sign-out cannot rebind a notice.
            store.update { state ->
                val current = PhoneReplyOutbox(state).receipts(now)
                if (current.none { it == receipt } || receipt.status == "accepted") return@update
                val seen = state.optJSONArray(SEEN)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                if (key in seen) return@update
                manager.notify(key, 4, notification(key))
                val retained = current.map(::key).toSet()
                state.put(SEEN, JSONArray((seen.filter { it in retained } + key).distinct().takeLast(128)))
            }
        }
    }

    private fun notification(key: String): Notification {
        val open = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setData(Uri.Builder().scheme("cmux-reply-status").authority(context.packageName).appendPath(key).build())
        val pending = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reply delivery unconfirmed")
            .setContentText("Open cmux to check your Mac before sending it again.")
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build()
    }

    companion object {
        const val CHANNEL = "cmux_reply_status"
        private const val SEEN = "phone_reply_notices"
        private val lock = Any()
        fun key(reply: PreparedPhoneReply) = hash(reply.login, reply.origin, reply.replyID)
        fun key(receipt: PhoneReplyReceipt) = hash(receipt.login, receipt.origin, receipt.replyID)
        private fun hash(login: String, origin: String, id: String): String = "cmux.reply." +
            MessageDigest.getInstance("SHA-256").digest(JSONArray(listOf(login, origin, id)).toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
