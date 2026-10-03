package io.github.docmorphic.cmuxapp

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

/** Persists one authenticated user action, then hands it to the durable background scheduler. */
class PhoneReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val (route, action) = PhoneReplyNotification.destination(context, intent) ?: return
        val input = runCatching { RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PhoneReplyNotification.TEXT) }.getOrNull() ?: return
        // Avoid copying arbitrary large spans before validating length. Formatting is never sent.
        val text = if (input.length <= 8192) input.toString() else ""
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val app = context.applicationContext
            var active: android.service.notification.StatusBarNotification? = null
            try {
                withTimeout(8_000) {
                    val store = NativeCredentialStore(app)
                    val target = store.load()?.let { PhoneReplyActions(it).directTarget(route, action) }
                    val direct = target?.let { withContext(Dispatchers.Main.immediate) { PhoneReplyDirect.prepare(it) } }
                    var packet: PreparedPhoneReply? = null
                    var result = PhoneReplySubmission.RETIRED
                    synchronized(NativeNotificationDelivery.lock) {
                        active = PhoneReplyNotification.active(app, route, action)
                        val notice = active ?: return@synchronized
                        if (NativeNotificationService.isEnabled(app)) store.update {
                            val now = System.currentTimeMillis()
                            result = PhoneReplyActions(it).submit(route, action, text, now, direct?.target)
                            if (result == PhoneReplySubmission.QUEUED && direct != null)
                                packet = PhoneReplyOutbox(it).waiting(now).singleOrNull { queued -> queued.replyID == action && queued.directOnly }
                        }
                        when (result) {
                            PhoneReplySubmission.QUEUED, PhoneReplySubmission.ALREADY_QUEUED ->
                                PhoneReplyNotification.status(app, notice, "Reply queued", "Waiting for delivery to your Mac.")
                            PhoneReplySubmission.INVALID_TEXT -> PhoneReplyNotification.status(app, notice,
                                "Reply not queued", "Use a shorter reply with 1–8,192 characters.", retry = true)
                            PhoneReplySubmission.FULL -> PhoneReplyNotification.status(app, notice,
                                "Reply not queued", "Earlier replies are still pending. Try again shortly.", retry = true)
                            else -> PhoneReplyNotification.status(app, notice,
                                "Reply unavailable", "Open cmux to check your account and Mac before replying.")
                        }
                    }
                    if (result in setOf(PhoneReplySubmission.QUEUED, PhoneReplySubmission.ALREADY_QUEUED)) {
                        // Schedule expiry reporting before any terminal write. Process death keeps the direct fence.
                        PhoneReplyWork.recover(app)
                        packet?.let { queued ->
                            val outcome = withContext(Dispatchers.Main.immediate) {
                                checkNotNull(direct).send(text) {
                                    queued.isFresh(System.currentTimeMillis()) && store.load()?.let {
                                        PhoneReplyOutbox(it).permits(queued)
                                    } == true
                                }
                            }
                            store.update { PhoneReplyOutbox(it).finishDirect(queued, outcome, System.currentTimeMillis()) }
                            // A confirmed terminal write needs no further job scheduling. Do not turn
                            // a later scheduler failure into a misleading delivery failure banner.
                            if (outcome != PhoneReplyDirectResult.DELIVERED) PhoneReplyWork.recover(app)
                        }
                        synchronized(NativeNotificationDelivery.lock) { NativeNotificationDelivery(app).cancel(route) }
                    }
                }
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { synchronized(NativeNotificationDelivery.lock) { active?.let {
                    PhoneReplyNotification.status(app, it, "Reply delivery unconfirmed", "Open cmux to check delivery before sending it again.")
                } } }
            } finally { pending.finish(); scope.cancel() }
        }
    }
}
