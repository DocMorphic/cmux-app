package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

internal enum class PhonePushAdmission { ACCEPTED, DUPLICATE, DISMISSED, FULL, RETIRED, EXPIRED }

/** Account-encrypted replay IDs/tombstones. Never evict an unexpired entry to admit another. */
internal class PhonePushInbox(private val state: JSONObject) {
    private fun root() = state.optJSONObject(KEY)
    private fun rows(name: String) = root()?.optJSONArray(name)?.let { array ->
        (0 until minOf(array.length(), CAPACITY)).mapNotNull(array::optJSONObject)
    }.orEmpty()
    private fun login() = state.optString("task_session").takeIf {
        it.isNotBlank() && state.optString("refresh_token").isNotBlank()
    }
    fun prune(now: Long = System.currentTimeMillis()) {
        val root = root() ?: return
        val login = login()
        if (login == null || root.optString("login") != login) { state.remove(KEY); return }
        val keys = PhonePushKeyState(state)
        val owners = mutableMapOf<Triple<String, String, String>, String?>()
        for (name in listOf("events", "dismissed")) {
            root.put(name, JSONArray(rows(name).mapNotNull { row ->
                if (row.optLong("expires") <= now) return@mapNotNull null
                val team = NativeTeamScope(login, row.optString("user"), row.optString("team"), 0)
                val key = Triple(team.userId, team.teamId, row.optString("origin"))
                if (!owners.containsKey(key)) owners[key] = keys.canonicalOrigin(team, key.third)
                val origin = owners[key] ?: return@mapNotNull null
                row.put("origin", origin)
            }))
        }
        if (rows("events").isEmpty() && rows("dismissed").isEmpty()) state.remove(KEY)
    }
    fun admit(message: PhonePushMessage, now: Long): PhonePushAdmission {
        prune(now)
        if (!message.permits(state)) return PhonePushAdmission.RETIRED
        if (!message.isFresh(now)) return PhonePushAdmission.EXPIRED
        val origin = checkNotNull(PhonePushKeyState(state).canonicalOrigin(message.team, message.origin))
        val events = rows("events"); val dismissed = rows("dismissed")
        fun matches(row: JSONObject, id: String) = row.optString("origin") == origin && row.optString("id") == id
        if (events.any { matches(it, message.correlationID) }) return PhonePushAdmission.DUPLICATE
        if (message.hasNotificationID && message.notification?.let { n -> dismissed.any { matches(it, n.id) } } == true)
            return PhonePushAdmission.DISMISSED
        val existingIDs = dismissed.filter { it.optString("origin") == origin }.map { it.optString("id") }.toSet()
        val incomingIDs = message.dismissedIDs.toSet()
        val additions = message.dismissedIDs.filter { it !in existingIDs }
        if (events.size >= CAPACITY || dismissed.size + additions.size > CAPACITY) return PhonePushAdmission.FULL
        fun row(id: String) = JSONObject().put("origin", origin).put("id", id).put("user", message.team.userId)
            .put("team", message.team.teamId).put("expires", message.expiresAtMillis)
        val value = root() ?: JSONObject().put("login", message.team.login).also { state.put(KEY, it) }
        value.put("events", JSONArray(events + row(message.correlationID)))
        dismissed.filter { it.optString("origin") == origin && it.optString("id") in incomingIDs }
            .forEach { it.put("expires", maxOf(it.optLong("expires"), message.expiresAtMillis)) }
        value.put("dismissed", JSONArray(dismissed + additions.map(::row)))
        return PhonePushAdmission.ACCEPTED
    }
    companion object { const val KEY = "phone_push_inbox"; const val CAPACITY = 4096 }
}
