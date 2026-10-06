package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Only kept inside account encryption. Deliberately has no data-class toString exposing the offer. */
internal class PendingPhoneHelperEnrollment(private val encoded: String) {
    internal fun json() = JSONObject(encoded)
    val id get() = json().getString("id")
    val team get() = json().let { NativeTeamScope(it.getString("login"), it.getString("user"), it.getString("team"), 0) }
    val origin get() = json().getString("origin")
    val offer get() = json().getString("offer")
    val requestID get() = json().getString("request")
    val expiresAt get() = JSONObject(offer).getLong("expiresAt")
    val retryAt get() = json().optLong("retry_at")
    val response get() = json().optJSONObject("response")
    val native get() = PhonePushPeer.parse(json().getJSONObject("native"))
    val nativeEpoch get() = json().getString("native_epoch")
    val phone get() = PhonePushDescriptor.parse(json().getJSONObject("phone"))
    fun matches(token: PhoneFcmTokenSnapshot?) = token != null && json().let {
        token.grant.login == team.login && token.grant.epoch == it.getString("grant") &&
            token.revision == it.getString("token_revision") && token.grant.project == PhoneFcmProject.parse(it.getJSONObject("project"))
    }
}

/** Mutations must be committed by the caller before network writes; receipts and pins share one transaction. */
internal class PhoneHelperEnrollmentState(private val state: JSONObject, private val now: () -> Long = System::currentTimeMillis) {
    private fun rows(key: String) = state.optJSONArray(key)?.let { array ->
        (0 until minOf(array.length(), 128)).mapNotNull(array::optJSONObject)
    }.orEmpty()
    private fun save(key: String, rows: List<JSONObject>) {
        if (rows.isEmpty()) state.remove(key) else state.put(key, JSONArray(rows))
    }
    private fun nativeCurrent(item: PendingPhoneHelperEnrollment): Boolean = runCatching {
        val keys = PhonePushKeyState(state)
        keys.canonicalOrigin(item.team, item.origin) != null && keys.peer(item.team, item.origin) == item.native &&
            keys.peerEpoch(item.team, item.origin) == item.nativeEpoch &&
            keys.existingIdentity(item.team.login)?.descriptor() == item.phone
    }.getOrDefault(false)
    fun prune() {
        val time = now()
        val valid = rows(KEY).mapNotNull { row -> runCatching {
            val item = PendingPhoneHelperEnrollment(row.toString())
            if (!nativeCurrent(item) || time < 0 || item.expiresAt <= time || item.expiresAt - time > 300_000) null
            else JSONObject(row.toString()).put("origin", PhonePushKeyState(state).canonicalOrigin(item.team, item.origin))
        }.getOrNull() }
        save(KEY, valid.groupBy { it.getString("origin") }.values.filter { it.size == 1 }.map { it.single() }.take(8))
        save(RECEIPTS, rows(RECEIPTS).filter { row -> runCatching {
            val team = NativeTeamScope(row.getString("login"), row.getString("user"), row.getString("team"), 0)
            val binding = PhonePushHelperState(state).binding(team, row.getString("origin"))
            binding != null && binding.epoch == row.getString("helper_epoch") && binding.macEpoch == row.getString("native_epoch") &&
                PhonePushKeyState(state).existingIdentity(team.login)?.descriptor() == PhonePushDescriptor.parse(row.getJSONObject("phone"))
        }.getOrDefault(false) })
    }
    fun pending(): List<PendingPhoneHelperEnrollment> { prune(); return rows(KEY).map { PendingPhoneHelperEnrollment(it.toString()) } }
    fun current(item: PendingPhoneHelperEnrollment, token: PhoneFcmTokenSnapshot?): Boolean =
        item.matches(token) && pending().any { it.id == item.id && it.requestID == item.requestID }

    /** Explicitly confirmed offer; replacing a pending attempt never removes an existing helper pin. */
    fun prepare(rawOffer: String, team: NativeTeamScope, origin: String, token: PhoneFcmTokenSnapshot,
        requestID: String = UUID.randomUUID().toString()): PendingPhoneHelperEnrollment {
        prune()
        val keys = PhonePushKeyState(state)
        val canonical = checkNotNull(keys.canonicalOrigin(team, origin))
        val peer = checkNotNull(keys.peer(team, canonical)); val epoch = checkNotNull(keys.peerEpoch(team, canonical))
        val phone = checkNotNull(keys.existingIdentity(team.login))
        PhoneHelperEnrollment(rawOffer, team, canonical, peer, epoch, phone, token, { true }, now, requestID).use { it.begin() }
        val retained = rows(KEY).filterNot { it.getString("origin") == canonical }
        check(retained.size < 8) { "Too many pending helper enrollments" }
        val row = JSONObject().put("id", UUID.randomUUID().toString()).put("login", team.login).put("user", team.userId)
            .put("team", team.teamId).put("origin", canonical).put("offer", rawOffer).put("request", requestID)
            .put("native", peer.wire()).put("native_epoch", epoch).put("phone", phone.descriptor().wire())
            .put("project", token.grant.project.json()).put("grant", token.grant.epoch).put("token_revision", token.revision)
        save(KEY, retained + row)
        return PendingPhoneHelperEnrollment(row.toString())
    }
    fun checkpoint(item: PendingPhoneHelperEnrollment, token: PhoneFcmTokenSnapshot, response: JSONObject): Boolean {
        if (!current(item, token)) return false
        require(response.toString().toByteArray().size <= PhoneHelperHttp.MAX_BODY)
        save(KEY, rows(KEY).map { if (it.getString("id") == item.id) JSONObject(it.toString()).put("response", response) else it })
        return true
    }
    fun retry(item: PendingPhoneHelperEnrollment, deadline: Long) {
        prune()
        save(KEY, rows(KEY).map { if (it.getString("id") == item.id)
            JSONObject(it.toString()).put("retry_at", maxOf(it.optLong("retry_at"), deadline.coerceAtMost(item.expiresAt))) else it })
    }
    fun remove(item: PendingPhoneHelperEnrollment) { save(KEY, rows(KEY).filterNot { it.optString("id") == item.id }) }
    fun retainForToken(token: PhoneFcmTokenSnapshot?) {
        prune(); save(KEY, rows(KEY).filter { PendingPhoneHelperEnrollment(it.toString()).matches(token) })
    }
    fun confirm(item: PendingPhoneHelperEnrollment, token: PhoneFcmTokenSnapshot, session: PhoneHelperEnrollment,
        ack: JSONObject): PhoneHelperEnrollmentReceipt {
        check(current(item, token)) { "Helper enrollment was replaced" }
        // Verify in a detached copy: an invalid receipt or capacity failure cannot leave a partial pin.
        val candidate = JSONObject(state.toString())
        val receipt = session.confirm(ack, candidate)
        val canonical = checkNotNull(PhonePushKeyState(candidate).canonicalOrigin(item.team, item.origin))
        val rows = rows(RECEIPTS).filterNot { PhonePushKeyState(candidate).canonicalOrigin(
            NativeTeamScope(it.getString("login"), it.getString("user"), it.getString("team"), 0), it.getString("origin")) == canonical }
        check(rows.size < 128)
        val row = item.json().apply { remove("offer"); remove("response"); remove("retry_at") }
            .put("origin", canonical).put("endpoint", session.endpoint).put("registration", receipt.registrationID)
            .put("generation", receipt.generation).put("helper_epoch", receipt.binding.epoch)
        state.put(PhonePushHelperState.KEY, candidate.getJSONArray(PhonePushHelperState.KEY))
        save(RECEIPTS, rows + row); remove(item)
        return receipt
    }
    companion object {
        const val KEY = "phone_helper_enrollments"
        const val RECEIPTS = "phone_helper_receipts"
    }
}

/** Each callback is one durable account transaction under the shared account/token lock. */
internal class PhoneHelperEnrollmentRecovery(
    private val transaction: ((JSONObject, PhoneFcmTokenSnapshot?) -> Unit) -> Unit,
    private val send: suspend (String, () -> Boolean, String, JSONObject) -> PhoneHelperHttpResult,
    private val now: () -> Long = System::currentTimeMillis,
    private val inspect: ((JSONObject, PhoneFcmTokenSnapshot?) -> Unit) -> Unit = transaction
) {
    suspend fun runPass(): Boolean {
        var pending = emptyList<PendingPhoneHelperEnrollment>()
        transaction { state, token -> PhoneHelperEnrollmentState(state, now).apply { retainForToken(token); pending = pending() } }
        for (item in pending) {
            currentCoroutineContext().ensureActive()
            if (item.retryAt > now()) continue
            var token: PhoneFcmTokenSnapshot? = null; var phone: PhonePushIdentity? = null
            inspect { state, live -> if (PhoneHelperEnrollmentState(state, now).current(item, live)) {
                token = live; phone = PhonePushKeyState(state).existingIdentity(item.team.login)
            } }
            val captured = token ?: continue
            fun current(): Boolean {
                var admitted = false
                inspect { state, live -> admitted = live == captured && PhoneHelperEnrollmentState(state, now).current(item, live) }
                return admitted
            }
            try {
                PhoneHelperEnrollment(item.offer, item.team, item.origin, item.native, item.nativeEpoch,
                    checkNotNull(phone), captured, ::current, now, item.requestID).use { session ->
                    var response = item.response
                    if (response == null) {
                        when (val result = send(session.endpoint, ::current, "begin", session.begin())) {
                            is PhoneHelperHttpResult.Success -> {
                                session.finish(result.body) // Authenticate before persisting ciphertext.
                                var stored = false
                                transaction { state, live -> if (live == captured) stored = PhoneHelperEnrollmentState(state, now).checkpoint(item, captured, result.body) }
                                if (!stored) return@use
                                response = result.body
                            }
                            else -> { handle(item, result); return@use }
                        }
                    }
                    val proof = session.finish(checkNotNull(response))
                    when (val result = send(session.endpoint, ::current, "finish", proof)) {
                        is PhoneHelperHttpResult.Success -> transaction { state, live ->
                            if (live == captured && PhoneHelperEnrollmentState(state, now).current(item, live))
                                PhoneHelperEnrollmentState(state, now).confirm(item, captured, session, result.body)
                        }
                        else -> handle(item, result)
                    }
                }
            } catch (failure: IllegalArgumentException) {
                transaction { state, _ -> PhoneHelperEnrollmentState(state, now).remove(item) }
            } catch (failure: IllegalStateException) {
                // Storage failures must propagate for worker retry; retirement is safe to discard.
                if (current()) throw failure
                transaction { state, _ -> PhoneHelperEnrollmentState(state, now).remove(item) }
            }
        }
        var remaining = false
        transaction { state, token -> PhoneHelperEnrollmentState(state, now).apply { retainForToken(token); remaining = pending().isNotEmpty() } }
        return remaining
    }
    private fun handle(item: PendingPhoneHelperEnrollment, result: PhoneHelperHttpResult) = transaction { state, _ ->
        val records = PhoneHelperEnrollmentState(state, now)
        if (result is PhoneHelperHttpResult.Retry) records.retry(item, result.notBeforeMillis) else records.remove(item)
    }
}
