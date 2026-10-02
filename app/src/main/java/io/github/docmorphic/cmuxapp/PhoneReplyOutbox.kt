package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal enum class ReplyEnqueueResult { QUEUED, DUPLICATE, CONFLICT, FULL, EXPIRED, RETIRED }
internal data class PhoneReplyReceipt(val replyID: String, val login: String, val userID: String, val teamID: String,
    val origin: String, val digest: String, val createdAtMillis: Long, val status: String) {
    fun wire() = JSONObject().put("reply_id", replyID).put("login", login).put("user", userID).put("team", teamID)
        .put("origin", origin).put("digest", digest).put("created_at", createdAtMillis).put("status", status)
}

/** Mutate only inside the encrypted account transaction. No plaintext reply text is retained. */
internal class PhoneReplyOutbox(private val state: JSONObject) {
    private fun root() = state.optJSONObject(KEY)
    private fun pendingRaw() = root()?.optJSONArray("pending")?.let { array ->
        (0 until minOf(array.length(), CAPACITY)).mapNotNull { index -> array.optJSONObject(index)?.let {
            runCatching { PreparedPhoneReply.restore(it) }.getOrNull()
        } }
    }.orEmpty()
    private fun receiptRaw(): List<PhoneReplyReceipt> = root()?.optJSONArray("receipts")?.let { array ->
        (0 until minOf(array.length(), RECEIPTS)).mapNotNull { index -> runCatching {
            val row = array.getJSONObject(index)
            fun id(name: String) = (row.opt(name) as? String)?.also { require(it.isNotBlank() && it.length <= 128) }
                ?: error("Invalid reply receipt")
            val created = row.opt("created_at").let { if (it is Long) it else if (it is Int) it.toLong() else error("Invalid time") }
            val status = id("status"); require(status in STATUSES && created > 0)
            PhoneReplyReceipt(id("reply_id"), id("login"), id("user"), id("team"), id("origin"), id("digest"), created, status)
        }.getOrNull() }
    }.orEmpty()
    private fun team(reply: PreparedPhoneReply) = NativeTeamScope(reply.login, checkNotNull(reply.peer.tuple.accountID), reply.teamID, 0)
    private fun ownerCurrent(team: NativeTeamScope, origin: String) =
        PhonePushKeyState(state).canonicalOrigin(team, origin) != null
    fun permits(reply: PreparedPhoneReply): Boolean {
        val keys = PhonePushKeyState(state)
        return ownerCurrent(team(reply), reply.origin) && keys.peer(team(reply), reply.origin) == reply.peer &&
            (reply.peerEpoch == null || keys.peerEpoch(team(reply), reply.origin) == reply.peerEpoch) &&
            keys.existingIdentity(reply.login)?.let { it.keyID == reply.senderKeyID && it.installationID == reply.peer.tuple.iosInstallationID } == true
    }
    fun waiting(now: Long): List<PreparedPhoneReply> { prune(now); return pendingRaw() }

    fun pending(now: Long): List<PreparedPhoneReply> {
        prune(now)
        if (now < (root()?.optLong("not_before") ?: 0)) return emptyList()
        return pendingRaw()
    }
    fun receipts(now: Long): List<PhoneReplyReceipt> { prune(now); return receiptRaw() }

    fun enqueue(reply: PreparedPhoneReply, now: Long): ReplyEnqueueResult {
        prune(now)
        if (!permits(reply)) return ReplyEnqueueResult.RETIRED
        val previous = pendingRaw().firstOrNull { it.replyID == reply.replyID }
        val receipt = receiptRaw().firstOrNull { it.replyID == reply.replyID }
        if (previous != null) return if (same(previous, reply)) ReplyEnqueueResult.DUPLICATE else ReplyEnqueueResult.CONFLICT
        if (receipt != null) return if (receipt.digest == digest(reply) && receipt.login == reply.login &&
            receipt.userID == reply.peer.tuple.accountID && receipt.teamID == reply.teamID && receipt.origin == reply.origin)
            ReplyEnqueueResult.DUPLICATE else ReplyEnqueueResult.CONFLICT
        if (!reply.isFresh(now)) return ReplyEnqueueResult.EXPIRED
        val pending = pendingRaw()
        if (pending.size >= CAPACITY) return ReplyEnqueueResult.FULL
        save(pending + reply, receiptRaw(), now)
        return ReplyEnqueueResult.QUEUED
    }

    fun finish(reply: PreparedPhoneReply, result: PhoneReplyRelayResult, now: Long) {
        // Allow an already in-flight 2xx to confirm just after the local retry deadline.
        // Never acknowledge a replacement packet with the same opaque reply ID.
        val match = pendingRaw().singleOrNull { same(it, reply) }
        if (match == null) {
            // A credential refresh can expire the queued row while its final HTTP
            // attempt is in flight. Confirm only the matching retained receipt.
            val receipt = receiptRaw().singleOrNull { it.replyID == reply.replyID && it.login == reply.login &&
                it.userID == reply.peer.tuple.accountID && it.teamID == reply.teamID && it.origin == reply.origin &&
                it.digest == digest(reply) && it.createdAtMillis == reply.createdAtMillis &&
                now >= it.createdAtMillis && now - it.createdAtMillis < 900_000 }
            if (result == PhoneReplyRelayResult.Accepted && receipt != null && permits(reply)) {
                save(pendingRaw(), receiptRaw().map { if (it == receipt) it.copy(status = "accepted") else it }, now)
            }
            prune(now)
            return
        }
        if (!permits(match)) { prune(now); return }
        when (result) {
            is PhoneReplyRelayResult.Retry -> {
                if (reply.isFresh(now)) {
                    val until = maxOf(root()?.optLong("not_before") ?: 0, result.notBeforeMillis, now + 1000)
                    state.getJSONObject(KEY).put("not_before", until)
                }
                prune(now)
            }
            PhoneReplyRelayResult.Retired -> prune(now) // Runtime/team changes can be transient; no unadmitted send.
            else -> {
                val status = when (result) {
                    PhoneReplyRelayResult.Accepted -> "accepted"
                    PhoneReplyRelayResult.SignInRequired -> "sign_in_required"
                    is PhoneReplyRelayResult.Rejected -> "rejected"
                    else -> "unconfirmed"
                }
                val receipt = receipt(match, status)
                save(pendingRaw().filterNot { same(it, match) }, (receiptRaw() + receipt).takeLast(RECEIPTS), now)
                prune(now)
            }
        }
    }

    /** Credential mutation runs this atomically, including forget/re-add and key rotation. */
    fun prune(now: Long = System.currentTimeMillis()) {
        val root = root() ?: return
        if (state.optString("refresh_token").isBlank() || root.optString("login") != state.optString("task_session") ||
            root.optString("login").isBlank()) { state.remove(KEY); return }
        val receipts = receiptRaw().filter { retained(it, now) &&
            ownerCurrent(NativeTeamScope(it.login, it.userID, it.teamID, 0), it.origin) }.toMutableList()
        val pending = pendingRaw().filter { reply ->
            if (!ownerCurrent(team(reply), reply.origin)) return@filter false
            if (!permits(reply) || !reply.isFresh(now)) {
                receipts += receipt(reply, "unconfirmed").copy(createdAtMillis = minOf(reply.createdAtMillis, now))
                false
            } else true
        }
        save(pending, receipts.filter { retained(it, now) }.distinctBy { it.login to it.replyID }.takeLast(RECEIPTS), now)
    }
    // A regular WorkManager notice may first execute well after the Mac inbox's
    // 15-minute lifetime. Retain only content-free failures long enough to report
    // that outcome; this never extends the send window or acceptance deadline.
    private fun retained(receipt: PhoneReplyReceipt, now: Long) = receipt.createdAtMillis <= now &&
        now - receipt.createdAtMillis < if (receipt.status == "accepted") 900_000 else FAILURE_RETENTION
    private fun save(pending: List<PreparedPhoneReply>, receipts: List<PhoneReplyReceipt>, now: Long) {
        if (pending.isEmpty() && receipts.isEmpty() && (root()?.optLong("not_before") ?: 0) <= now) { state.remove(KEY); return }
        val value = root() ?: JSONObject().put("login", state.optString("task_session")).also { state.put(KEY, it) }
        value.put("pending", JSONArray(pending.map { it.persisted() }))
            .put("receipts", JSONArray(receipts.map { it.wire() }))
    }
    private fun receipt(reply: PreparedPhoneReply, status: String) = PhoneReplyReceipt(reply.replyID, reply.login,
        checkNotNull(reply.peer.tuple.accountID), reply.teamID, reply.origin, digest(reply), reply.createdAtMillis, status)
    private fun same(a: PreparedPhoneReply, b: PreparedPhoneReply) = a.replyID == b.replyID && a.login == b.login &&
        a.origin == b.origin && a.teamID == b.teamID && a.peer == b.peer && a.peerEpoch == b.peerEpoch &&
        a.senderKeyID == b.senderKeyID && a.createdAtMillis == b.createdAtMillis && a.body == b.body
    private fun digest(reply: PreparedPhoneReply) = MessageDigest.getInstance("SHA-256").digest(reply.body.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    companion object {
        const val KEY = "phone_reply_outbox"
        const val CAPACITY = 20
        private const val RECEIPTS = 128
        private const val FAILURE_RETENTION = 7 * 24 * 60 * 60 * 1000L
        private val STATUSES = setOf("accepted", "unconfirmed", "rejected", "sign_in_required")
    }
}

/** One serial pass; persist every result before moving on. Account/team admission remains a runtime gate. */
internal suspend fun drainPhoneReplies(
    pending: () -> List<PreparedPhoneReply>, permits: (PreparedPhoneReply) -> Boolean,
    send: suspend (PreparedPhoneReply) -> PhoneReplyRelayResult,
    finish: (PreparedPhoneReply, PhoneReplyRelayResult) -> Unit
) {
    val visited = mutableSetOf<String>()
    repeat(PhoneReplyOutbox.CAPACITY) {
        currentCoroutineContext().ensureActive()
        val reply = pending().firstOrNull { it.replyID !in visited && permits(it) } ?: return
        visited.add(reply.replyID)
        val result = send(reply)
        currentCoroutineContext().ensureActive()
        if (!permits(reply)) return
        finish(reply, result)
        if (result is PhoneReplyRelayResult.Retry || result == PhoneReplyRelayResult.Retired) return
    }
}
