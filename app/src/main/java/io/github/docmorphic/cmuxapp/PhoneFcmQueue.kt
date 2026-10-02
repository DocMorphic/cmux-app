package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class QueuedPhonePush(val id: String, val login: String, val raw: String, val expires: Long, val highPriority: Boolean = false)

/** Ciphertext waits inside the account's Keystore-encrypted transaction, never WorkManager Data. */
internal class PhoneFcmQueue(private val state: JSONObject) {
    enum class EnqueueResult { NEW, PRIORITY_UPGRADE, DUPLICATE, REJECTED }
    private fun login() = state.optString("task_session").takeIf { it.isNotBlank() && state.optString("refresh_token").isNotBlank() }
    fun waiting(now: Long): List<QueuedPhonePush> {
        val owner = login() ?: return emptyList()
        val rows = state.optJSONArray(KEY) ?: return emptyList()
        return (0 until minOf(rows.length(), CAPACITY)).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val raw = row.optString("raw"); val expires = row.optLong("expires")
            if (row.optString("login") != owner || expires <= now || expires - now > LIFETIME || !valid(raw)) null
            else QueuedPhonePush(row.optString("id"), owner, raw, expires, row.optBoolean("high_priority"))
        }
    }
    fun prune(now: Long = System.currentTimeMillis()) = save(waiting(now))
    private fun save(items: List<QueuedPhonePush>) {
        if (items.isEmpty()) state.remove(KEY)
        else state.put(KEY, JSONArray(items.map { JSONObject().put("id", it.id).put("login", it.login).put("raw", it.raw).put("expires", it.expires).put("high_priority", it.highPriority) }))
    }
    fun enqueue(raw: String, now: Long): Boolean = offer(raw, now) != EnqueueResult.REJECTED
    fun offer(raw: String, now: Long, highPriority: Boolean = false): EnqueueResult {
        val owner = login() ?: return EnqueueResult.REJECTED
        if (!valid(raw) || now < 0 || now > Long.MAX_VALUE - LIFETIME) return EnqueueResult.REJECTED
        val id = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        val items = waiting(now)
        val existing = items.firstOrNull { it.id == id }
        if (existing != null) {
            if (highPriority && !existing.highPriority) {
                save(items.map { if (it.id == id) it.copy(highPriority = true) else it })
                return EnqueueResult.PRIORITY_UPGRADE
            }
            return EnqueueResult.DUPLICATE // Never extend retention on redelivery.
        }
        if (items.size >= CAPACITY) return EnqueueResult.REJECTED
        save(items + QueuedPhonePush(id, owner, raw, now + LIFETIME, highPriority))
        return EnqueueResult.NEW
    }
    fun remove(item: QueuedPhonePush, now: Long) = save(waiting(now).filterNot { it.id == item.id && it.login == item.login })
    companion object {
        const val KEY = "phone_fcm_pending"
        const val CAPACITY = 64
        const val LIFETIME = 15 * 60_000L
        const val MAX_BYTES = 4096
        fun valid(raw: String): Boolean = raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES && runCatching {
            val value = MobileJson.objectValue(raw, requireComplete = true)
            value.length() == 1 && value.has("encryptedPayloads") && value.getJSONArray("encryptedPayloads").let { it.length() in 1..200 &&
                (0 until it.length()).all { i -> PhonePushEnvelope.parse(it.getJSONObject(i)); true } }
        }.getOrDefault(false)
    }
}

/** All candidate identity comes from saved pairings plus freshly verified membership. */
internal fun openQueuedPhonePush(item: QueuedPhonePush, state: JSONObject, membership: NativeAccountTeamsState,
    buildID: String, now: Long): PhonePushMessage? {
    val scope = membership.scope ?: return null
    if (membership.cached || membership.error != null || scope.login != item.login || state.optString("task_session") != item.login || item.expires <= now) return null
    val rows = state.optJSONArray("pairings") ?: return null
    if (rows.length() > 128) return null
    val messages = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(NativePairingRecords::decode) }
        .filter { it.accountUserId == scope.userId && membership.teams.any { team -> team.id == it.accountTeamId } }
        .distinctBy { it.origin }.mapNotNull { mac -> runCatching {
            PhonePushMessage.open(item.raw, state, scope.copy(teamId = checkNotNull(mac.accountTeamId)), mac.origin, buildID, now)
        }.getOrNull() }
    return messages.singleOrNull()
}
