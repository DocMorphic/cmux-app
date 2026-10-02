package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal fun pairingOrigin(code: String, deviceId: String = "", instanceTag: String? = null): String =
    MessageDigest.getInstance("SHA-256").digest(JSONArray().put(code).put(deviceId)
        .put(instanceTag ?: JSONObject.NULL).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Only this random identifier crosses the Android notification/Intent boundary. */
internal data class NotificationDestination(
    val routeId: String, val origin: String, val notificationId: String,
    val workspaceId: String, val surfaceId: String?, val retarget: Boolean, val login: String? = null
) {
    fun notification() = NativeNotification(notificationId, workspaceId, surfaceId, "", "", false,
        retargetsToLiveSurfaceOwner = retarget)
    fun json() = JSONObject().put("route", routeId).put("origin", origin).put("id", notificationId)
        .put("workspace", workspaceId).put("surface", surfaceId).put("retarget", retarget).put("login", login)

    companion object {
        fun parse(value: JSONObject) = NotificationDestination(value.getString("route"),
            value.getString("origin"), value.getString("id"), value.getString("workspace"),
            value.optString("surface").takeIf { !value.isNull("surface") && it.isNotBlank() },
            value.optBoolean("retarget"), value.opt("login") as? String)
    }
}

/** Deterministic state machine; its JSON is persisted in Android Keystore encrypted storage. */
internal class NativeNotificationLedger(private val state: JSONObject) {
    private val origins = state.optJSONObject("origins") ?: JSONObject().also { state.put("origins", it) }
    private val routes = state.optJSONObject("routes") ?: JSONObject().also { state.put("routes", it) }

    fun destination(routeId: String): NotificationDestination? = routes.optJSONObject(routeId)?.let {
        runCatching { NotificationDestination.parse(it) }.getOrNull()
    }
    fun destinations(): List<NotificationDestination> = routes.keys().asSequence().mapNotNull(::destination).toList()

    /** Idempotent repair; existing Android pending intents retain their route UUIDs. */
    fun coalesce(origin: String, aliases: Set<String>) {
        val previous = aliases - origin
        if (previous.isEmpty()) return
        if (origins.has(origin) || previous.any(origins::has)) {
            origins.put(origin, JSONArray((seen(origin) + previous.sorted().flatMap(::seen)).distinct().take(SEEN_LIMIT)))
        }
        previous.forEach(origins::remove)
        routes.keys().asSequence().toList().forEach { id ->
            routes.optJSONObject(id)?.takeIf { it.optString("origin") in previous }?.put("origin", origin)
        }
    }

    /** First observation establishes a quiet baseline, separately for each paired Mac. */
    fun baseline(origin: String, feed: List<NativeNotification>): Boolean {
        if (origins.has(origin)) return false
        origins.put(origin, JSONArray(feed.map { it.id }.distinct().take(SEEN_LIMIT)))
        return true
    }
    fun unseen(origin: String, feed: List<NativeNotification>): List<NativeNotification> {
        val seen = seen(origin).toSet()
        return feed.filter { !it.isRead && it.id !in seen }
    }
    fun acknowledge(origin: String, ids: List<String>) {
        origins.put(origin, JSONArray((ids + seen(origin)).distinct().take(SEEN_LIMIT)))
    }
    private fun seen(origin: String): List<String> {
        val array = origins.optJSONArray(origin) ?: return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }
    /** Persist before posting, acknowledge only after NotificationManager accepts the post. */
    fun stage(origin: String, item: NativeNotification, login: String? = null): NotificationDestination {
        val previous = destinations().firstOrNull { it.origin == origin && it.notificationId == item.id && it.login == login }
        val next = NotificationDestination(previous?.routeId ?: UUID.randomUUID().toString(), origin,
            item.id, item.workspaceId, item.surfaceId, item.retargetsToLiveSurfaceOwner, login)
        routes.put(next.routeId, next.json().put("updated", System.currentTimeMillis()))
        return next
    }
    fun prune(validOrigins: Set<String>): List<String> {
        origins.keys().asSequence().toList().filter { it !in validOrigins }.forEach(origins::remove)
        val expired = routes.keys().asSequence().toList().filter {
            routes.optJSONObject(it)?.optString("origin") !in validOrigins
        }.toMutableSet()
        val retained = routes.keys().asSequence().filter { it !in expired }.sortedWith(
            compareByDescending<String> { routes.optJSONObject(it)?.optLong("updated") ?: 0 }
                .thenBy { it }).toList()
        expired.addAll(retained.drop(ROUTE_LIMIT))
        expired.forEach(routes::remove)
        return expired.toList()
    }
    companion object {
        const val SEEN_LIMIT = 4096
        const val ROUTE_LIMIT = 512
    }
}
