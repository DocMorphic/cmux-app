package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Callbacks must fence the owning login, saved Mac and team when they access delivery state. */
internal data class NativeNotificationSync(
    val delivered: () -> List<String>,
    val handled: (List<String>) -> Unit
)

/** Swift decodes a string array: never coerce numbers/objects into notification identifiers. */
internal fun notificationSyncIDs(payload: JSONObject, key: String, limit: Int = 4096): List<String>? {
    val array = payload.optJSONArray(key) ?: return null
    if (array.length() > limit) return null
    val ids = ArrayList<String>()
    for (index in 0 until array.length()) {
        val value = array.opt(index) as? String ?: return null
        if (value.length > 1024) return null
        value.trim().takeIf { it.isNotEmpty() }?.let(ids::add)
    }
    return ids.distinct()
}

/** Reconnect and periodic catch-up use actual delivered banners, never absence from a truncated feed. */
internal suspend fun reconcileNativeNotifications(sync: NativeNotificationSync,
    request: suspend (List<String>) -> JSONObject) {
    try {
        withTimeout(10_000) {
            for (batch in sync.delivered().distinct().take(NativeNotificationLedger.ROUTE_LIMIT).chunked(256)) {
                val result = request(batch)
                // A response may only clear IDs this request asked this host about.
                val handled = notificationSyncIDs(result, "handled_ids", 256) ?: continue
                val requested = batch.toSet()
                sync.handled(handled.filter { it in requested })
            }
        }
    } catch (_: Exception) {
        // Older hosts and temporary RPC failures must not stop notification feed delivery.
        currentCoroutineContext().ensureActive()
    }
}
