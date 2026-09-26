package io.github.docmorphic.cmuxapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the user's opt-in notification connection after boot or app upgrade. */
class NativeNotificationRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (!NativeNotificationService.isEnabled(context)) return
        runCatching {
            context.startForegroundService(Intent(context, NativeNotificationService::class.java))
        }
    }
}
