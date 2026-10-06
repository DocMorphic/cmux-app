package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal enum class PhoneReplySubmission { QUEUED, ALREADY_QUEUED, INVALID_TEXT, FULL, RETIRED, CONFLICT }

/** Ready/consumed action grants live in the same encrypted transaction as the reply outbox. */
internal class PhoneReplyActions(private val state: JSONObject) {
    private fun root() = state.optJSONObject(KEY)
    private fun rows() = root()?.optJSONArray("items")?.let { array ->
        (0 until minOf(array.length(), CAPACITY)).mapNotNull(array::optJSONObject)
    }.orEmpty()
    private fun login() = state.optString("task_session").takeIf {
        it.isNotBlank() && state.optString("refresh_token").isNotBlank()
    }
    private fun scope(row: JSONObject) = NativeTeamScope(row.getString("login"), row.getString("user"), row.getString("team"), 0)
    private fun helperEpoch(row: JSONObject): String? = if (!row.has("helper_epoch")) null else
        row.getString("helper_epoch").also { require(it.isNotBlank() && it.length <= 128) }
    private fun replyPeer(row: JSONObject) = PhonePushPeer.parse(row.getJSONObject(
        if (helperEpoch(row) == null) "peer" else "reply_peer"))
    private fun valid(row: JSONObject): Boolean = runCatching {
        val team = scope(row); val keys = PhonePushKeyState(state); val origin = row.getString("origin")
        val peer = replyPeer(row)
        val helperEpoch = helperEpoch(row)
        if (helperEpoch != null) {
            val helper = PhonePushHelperState(state).binding(team, origin) ?: return@runCatching false
            if (helper.epoch != helperEpoch || helper.macPeer != peer ||
                helper.peer != PhonePushPeer.parse(row.getJSONObject("peer"))) return@runCatching false
        }
        val pairings = state.optJSONArray("pairings") ?: return@runCatching false
        val mac = (0 until pairings.length()).mapNotNull { pairings.optJSONObject(it)?.let(NativePairingRecords::decode) }
            .singleOrNull { it.ownsOrigin(origin) } ?: return@runCatching false
        team.login == login() && keys.peer(team, origin) == peer && keys.peerEpoch(team, origin) == row.getString("epoch") &&
            NativePairingRecords.usable(mac, team, TailscaleGrantStore({ state }, { error("read only") })) &&
            keys.existingIdentity(team.login)?.let { it.keyID == row.getString("phone_key") && it.installationID == peer.tuple.iosInstallationID } == true
    }.getOrDefault(false)
    fun prune() {
        if (root() == null) return
        if (login() == null || root()?.optString("login") != login()) { state.remove(KEY); return }
        save(rows().filter(::valid))
    }
    private fun save(items: List<JSONObject>) {
        if (items.isEmpty()) state.remove(KEY)
        else state.put(KEY, JSONObject().put("login", login()).put("items", JSONArray(items.takeLast(CAPACITY))))
    }
    fun stage(message: PhonePushMessage, destination: NotificationDestination, now: Long = System.currentTimeMillis()): String? {
        prune()
        val item = message.notification ?: return null
        val epoch = message.peerEpoch ?: return null // Older pins gain an epoch at their next authenticated exchange.
        val origin = PhonePushKeyState(state).canonicalOrigin(message.team, message.origin) ?: return null
        if (!message.canReply || !message.isFresh(now) || !message.permits(state) || destination.login != message.team.login ||
            destination.origin != origin || destination.notificationId != item.id || destination.workspaceId != item.workspaceId ||
            destination.surfaceId != item.surfaceId || destination.retarget != item.retargetsToLiveSurfaceOwner) return null
        val previous = rows().singleOrNull { it.optString("route") == destination.routeId && it.optString("correlation") == message.correlationID }
        if (previous != null) return previous.getString("action").takeUnless { previous.optBoolean("consumed") }
        val id = UUID.randomUUID().toString()
        val row = JSONObject().put("action", id).put("route", destination.routeId).put("correlation", message.correlationID)
            .put("login", message.team.login).put("user", message.team.userId).put("team", message.team.teamId)
            .put("origin", origin).put("epoch", epoch).put("peer", message.peer.wire())
            .put("phone_key", message.recipientKeyID).put("workspace", item.workspaceId.takeIf { it.isNotBlank() })
            .put("surface", item.surfaceId).put("retarget", item.retargetsToLiveSurfaceOwner).put("consumed", false)
        if (message.helperEpoch != null) row.put("helper_epoch", message.helperEpoch).put("reply_peer", message.replyPeer.wire())
        // For helper rows, peer remains the helper: older clients reject that pin instead of losing the helper fence.
        save(rows().filterNot { it.optString("route") == destination.routeId } + row)
        return id
    }
    fun directTarget(routeID: String, actionID: String): PhoneReplyDirectTarget? {
        prune()
        val row = rows().singleOrNull { it.optString("action") == actionID && it.optString("route") == routeID &&
            !it.optBoolean("consumed") } ?: return null
        return PhoneReplyDirectTarget(scope(row), row.getString("origin"), row.getString("epoch"),
            replyPeer(row), row.opt("workspace") as? String,
            row.getString("surface"), row.getBoolean("retarget"))
    }
    fun submit(routeID: String, actionID: String, text: String, now: Long, direct: PhoneReplyDirectTarget? = null): PhoneReplySubmission {
        prune()
        val row = rows().singleOrNull { it.optString("action") == actionID && it.optString("route") == routeID }
            ?: return PhoneReplySubmission.RETIRED
        if (row.optBoolean("consumed")) return PhoneReplySubmission.ALREADY_QUEUED
        if (text.isBlank() || text.length > 8192) return PhoneReplySubmission.INVALID_TEXT
        if (direct != null && directTarget(routeID, actionID) != direct) return PhoneReplySubmission.RETIRED
        val scope = scope(row); val local = PhonePushKeyState(state).existingIdentity(scope.login) ?: return PhoneReplySubmission.RETIRED
        val prepared = runCatching { PreparedPhoneReply.prepare(actionID, scope, row.getString("origin"), replyPeer(row),
            local, row.opt("workspace") as? String, row.getString("surface"), row.getBoolean("retarget"), text, now).boundTo(row.getString("epoch")).withHelperFence(helperEpoch(row)).withDirectFence(direct != null)
        }.getOrElse { return PhoneReplySubmission.INVALID_TEXT }
        return when (PhoneReplyOutbox(state).enqueue(prepared, now)) {
            ReplyEnqueueResult.QUEUED, ReplyEnqueueResult.DUPLICATE -> {
                row.put("consumed", true); save(rows())
                PhoneReplySubmission.QUEUED
            }
            ReplyEnqueueResult.FULL -> PhoneReplySubmission.FULL
            ReplyEnqueueResult.CONFLICT -> PhoneReplySubmission.CONFLICT
            else -> PhoneReplySubmission.RETIRED
        }
    }
    companion object { const val KEY = "phone_reply_actions"; const val CAPACITY = 512 }
}
