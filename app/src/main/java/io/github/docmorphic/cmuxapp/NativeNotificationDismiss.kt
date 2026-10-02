package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

internal data class PendingNotificationDismiss(val login: String, val origin: String, val id: String) {
    fun json() = JSONObject().put("login", login).put("origin", origin).put("id", id)
}

/** Part of the encrypted account transaction: no message content or routing credentials. */
internal class NativeNotificationDismissOutbox(private val state: JSONObject) {
    fun pending(): List<PendingNotificationDismiss> {
        val rows = state.optJSONArray(KEY) ?: return emptyList()
        return (0 until minOf(rows.length(), CAPACITY)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val values = listOf("login", "origin", "id").map { row.opt(it) as? String }
            if (values.any { it.isNullOrBlank() || it.length > 1024 }) null
            else PendingNotificationDismiss(values[0]!!, values[1]!!, values[2]!!)
        }.distinct()
    }
    fun enqueue(route: NotificationDestination) {
        if (!route.dismissible) return
        prune()
        val login = login() ?: return
        if (route.login != login) return
        val owner = owners().singleOrNull { it.ownsOrigin(route.origin) } ?: return
        if (route.notificationId.isBlank() || route.notificationId.length > 1024) return
        val next = PendingNotificationDismiss(login, owner.origin, route.notificationId)
        save((pending() + next).distinct().takeLast(CAPACITY))
    }
    fun acknowledge(sent: List<PendingNotificationDismiss>) = save(pending().filter { it !in sent })
    /** Run after every credential mutation so forgotten/replaced accounts cannot revive an outbox. */
    fun prune() {
        if (!state.has(KEY)) return
        val login = login(); val owners = owners()
        save(pending().mapNotNull { item ->
            if (item.login != login) return@mapNotNull null
            val owner = owners.singleOrNull { it.ownsOrigin(item.origin) } ?: return@mapNotNull null
            item.copy(origin = owner.origin)
        }.distinct())
    }
    private fun login() = state.optString("task_session").takeIf {
        it.isNotBlank() && state.optString("refresh_token").isNotBlank()
    }
    private fun owners(): List<NativeCredentialStore.PairedMac> {
        val rows = state.optJSONArray("pairings") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(NativePairingRecords::decode) }
    }
    private fun save(rows: List<PendingNotificationDismiss>) {
        if (rows.isEmpty()) state.remove(KEY) else state.put(KEY, JSONArray(rows.map { it.json() }))
    }
    companion object { const val KEY = "notification_dismisses"; const val CAPACITY = 128 }
}

/** One bounded pass; each owning Mac has an independent lease and retry outcome. */
internal suspend fun flushNativeNotificationDismissals(
    pending: List<PendingNotificationDismiss>, owners: List<NativeCredentialStore.PairedMac>,
    permits: (PendingNotificationDismiss, NativeCredentialStore.PairedMac) -> Boolean,
    connect: suspend (NativeCredentialStore.PairedMac) -> MobileRpcClient,
    acknowledge: (List<PendingNotificationDismiss>) -> Unit
) = coroutineScope {
    val groups = pending.mapNotNull { item -> owners.singleOrNull { it.ownsOrigin(item.origin) }?.let { it to item } }
        .groupBy({ it.first }, { it.second })
    val slots = Semaphore(4)
    groups.map { (mac, rows) -> launch {
        slots.withPermit {
            val admitted = rows.filter { permits(it, mac) }
            if (admitted.isEmpty()) return@withPermit
            var client: MobileRpcClient? = null
            try {
                withTimeout(10_000) {
                    client = connect(mac)
                    mac.requireMatchingHost(client!!.hostStatus())
                    val current = admitted.filter { permits(it, mac) }
                    if (current.isNotEmpty()) {
                        client!!.dismissNotifications(current.map { it.id }.distinct())
                        acknowledge(current.filter { permits(it, mac) })
                    }
                }
            } catch (_: Exception) {
                // Dismissal by immutable ID is idempotent. Keep unknown/failed
                // outcomes for the next pass; never drop another Mac's rows.
                currentCoroutineContext().ensureActive()
            } finally { client?.close() }
        }
    } }.joinAll()
}
