package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.os.Bundle
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Data-only ingress. Firebase project/token registration is deliberately not enabled here. */
class PhoneFcmService : FirebaseMessagingService() {
    override fun handleIntent(intent: Intent) {
        // The pinned SDK displays notification messages BEFORE onMessageReceived.
        // Discard those contents but preserve its message-ID acknowledgement.
        // No plaintext title/body, icon, link or PendingIntent reaches SDK display.
        super.handleIntent(phoneFcmDataOnlyIntent(intent))
    }
    override fun onNewToken(token: String) {
        // A delayed callback can contain an obsolete token; fetch the SDK current value.
        try { runBlocking { withTimeout(8_000) { PhoneFcmTokens.onTokenChanged(applicationContext)?.join() } } }
        catch (_: Exception) { /* Startup recovery reads the SDK again without logging tokens. */ }
    }
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.notification != null) return
        val raw = message.data.takeIf { it.keys == setOf("cmux") }?.get("cmux") ?: return
        try {
            runBlocking { withTimeout(8_000) { PhoneFcmWork.enqueue(applicationContext, raw, message.priority == RemoteMessage.PRIORITY_HIGH) } }
        } catch (_: Exception) {
            // No payload, token, identity or credential logging. If persistence
            // succeeded but scheduling failed, normal service startup recovers it.
        }
    }
}

internal fun phoneFcmDataOnlyIntent(intent: Intent): Intent {
    val unsafe = intent.extras?.keySet()?.any { it.startsWith("gcm.n.") || it.startsWith("gcm.notification.") } == true
    if (!unsafe) return intent
    val clean = Bundle().apply { intent.getStringExtra("google.message_id")?.let { putString("google.message_id", it) } }
    return Intent(intent).replaceExtras(clean)
}
