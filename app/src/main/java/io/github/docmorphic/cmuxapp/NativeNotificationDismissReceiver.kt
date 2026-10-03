package io.github.docmorphic.cmuxapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** NotificationManager invokes this only for user dismissal, not app cancellation. */
class NativeNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val route = NativeNotificationDelivery.routeFromIntent(context, intent) ?: return
        val destination = NativeNotificationDelivery(context).destination(route) ?: return
        // Durable before returning from the broadcast. Existing app/service
        // connections flush it; no background network or new service is started.
        NativeCredentialStore(context).update { NativeNotificationDismissOutbox(it).enqueue(destination) }
    }
    companion object { const val ACTION = "io.github.docmorphic.cmuxapp.DISMISS_NOTIFICATION" }
}
