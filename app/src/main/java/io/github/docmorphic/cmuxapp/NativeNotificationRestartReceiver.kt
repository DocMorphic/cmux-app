package io.github.docmorphic.cmuxapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

/** Restores the user's opt-in notification connection after boot or app upgrade. */
class NativeNotificationRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try { withTimeout(8_000) {
                PhoneFcmTokens.recover(context.applicationContext).join()
                PhoneReplyWork.recover(context.applicationContext)
                PhoneFcmWork.recover(context.applicationContext)
            } }
            catch (_: Exception) { /* Durable queue is recovered again at app startup. */ }
            finally { pending.finish(); scope.cancel() }
        }
        if (!NativeNotificationService.isEnabled(context)) return
        runCatching {
            context.startForegroundService(Intent(context, NativeNotificationService::class.java))
        }
    }
}
