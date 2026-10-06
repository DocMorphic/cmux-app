package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Contains private cleanup keys and provider tokens. Never log or put in Android saved-instance state. */
internal class QueuedHelperMaintenance(private val encoded: String) {
    fun json() = JSONObject(encoded)
    val id get() = json().getJSONObject("receipt").getString("id")
    val operation get() = json().getJSONObject("operation")
    val requestID get() = operation.getString("id")
    val action get() = operation.getString("action")
    val aborting get() = operation.optBoolean("aborting")
    val response get() = operation.optJSONObject("response")
    val retryAt get() = operation.optLong("retry_at")
    val receipt get() = json().getJSONObject("receipt")
    val phone get() = PhonePushIdentity.parse(json().getJSONObject("phone_private"))
    val binding get() = json().let { row -> PhonePushHelperBinding(PhonePushPeer.parse(row.getJSONObject("helper_peer")),
        PhonePushPeer.parse(receipt.getJSONObject("native")), receipt.getString("native_epoch"),
        PhoneFcmProject.parse(receipt.getJSONObject("project")), receipt.getString("helper_epoch")) }
    fun session(current: () -> Boolean, now: () -> Long) = PhoneHelperMaintenance(receipt.getString("endpoint"),
        receipt.getString("registration"), receipt.getString("generation"), binding, phone, action,
        operation.opt("token") as? String, current, now, requestID)
}

/** Separate Keystore store survives logout only to settle/remove registrations; never grants delivery trust. */
internal class PhoneHelperMaintenanceQueue(private val state: JSONObject, private val now: () -> Long = System::currentTimeMillis) {
    private fun rows() = state.optJSONArray(KEY)?.let { array ->
        (0 until minOf(array.length(), 512)).mapNotNull(array::optJSONObject)
    }.orEmpty()
    private fun save(rows: List<JSONObject>) {
        if (rows.isEmpty()) state.remove(KEY) else state.put(KEY, JSONArray(rows))
    }
    private fun team(receipt: JSONObject) = NativeTeamScope(receipt.getString("login"), receipt.getString("user"), receipt.getString("team"), 0)
    private fun receiptRows(account: JSONObject) = account.optJSONArray(PhoneHelperEnrollmentState.RECEIPTS)?.let { array ->
        (0 until array.length()).mapNotNull(array::optJSONObject)
    }.orEmpty()
    /** Capture verified receipts before account keys/pins can be erased. Existing ledgers never roll back to an older account generation. */
    fun preserve(account: JSONObject?) {
        if (account == null || !account.has(PhoneHelperEnrollmentState.RECEIPTS)) return
        val checked = JSONObject(account.toString())
        PhoneHelperEnrollmentState(checked, now).prune()
        val retained = rows().toMutableList()
        for (receipt in receiptRows(checked)) {
            if (retained.any { it.getJSONObject("receipt").getString("id") == receipt.getString("id") }) continue
            val owner = team(receipt)
            val binding = PhonePushHelperState(checked).binding(owner, receipt.getString("origin")) ?: continue
            val phone = PhonePushKeyState(checked).existingIdentity(owner.login) ?: continue
            check(retained.size < 512) { "Push cleanup storage is full" }
            retained += JSONObject().put("receipt", JSONObject(receipt.toString())).put("helper_peer", binding.peer.wire())
                .put("phone_private", phone.wire())
        }
        save(retained)
    }
    private fun owns(row: JSONObject, account: JSONObject): Boolean = runCatching {
        val receipt = row.getJSONObject("receipt"); val owner = team(receipt)
        val local = receiptRows(account).singleOrNull { it.optString("id") == receipt.getString("id") } ?: return@runCatching false
        val helper = PhonePushHelperState(account).binding(owner, local.getString("origin")) ?: return@runCatching false
        helper.epoch == receipt.getString("helper_epoch") && helper.macEpoch == receipt.getString("native_epoch") &&
            helper.peer == PhonePushPeer.parse(row.getJSONObject("helper_peer")) && helper.macPeer == PhonePushPeer.parse(receipt.getJSONObject("native")) &&
            PhonePushKeyState(account).existingIdentity(owner.login)?.descriptor() == PhonePushIdentity.parse(row.getJSONObject("phone_private")).descriptor()
    }.getOrDefault(false)
    private fun grantMatches(row: JSONObject, grant: PhoneFcmTokenGrant?) = row.getJSONObject("receipt").let {
        grant != null && grant.login == it.getString("login") && grant.project == PhoneFcmProject.parse(it.getJSONObject("project"))
    }
    private fun awaitingAccount(row: JSONObject, account: JSONObject, grant: PhoneFcmTokenGrant?): Boolean {
        if (!grantMatches(row, grant)) return false
        val receipt = row.getJSONObject("receipt")
        return PhoneHelperEnrollmentState(account, now).pending().any { it.id == receipt.getString("id") && it.team.login == receipt.getString("login") }
    }
    /** Timestamp the proposed account removal before its commit, without treating a failed save as revocation. */
    fun stageRetirement(account: JSONObject) {
        val pendingIds = PhoneHelperEnrollmentState(JSONObject(account.toString()), now).pending().map { it.id }.toSet()
        for (row in rows()) {
            if (!owns(row, account) && row.getJSONObject("receipt").getString("id") !in pendingIds) {
                if (!row.has("retirement_at")) row.put("retirement_at", now())
            } else if (!row.has("cleanup_deadline")) row.remove("retirement_at")
        }
    }
    private fun desiredMatches(operation: JSONObject, retired: Boolean, token: PhoneFcmTokenSnapshot?): Boolean =
        if (retired) operation.getString("action") == "revoke" else token != null && operation.getString("action") == "renew" &&
            operation.optString("grant") == token.grant.epoch && operation.optString("revision") == token.revision && operation.optString("token") == token.token
    private fun operation(retired: Boolean, token: PhoneFcmTokenSnapshot?) = JSONObject()
        .put("id", UUID.randomUUID().toString()).put("action", if (retired) "revoke" else "renew")
        .put("deadline", now() + DAY).apply {
            if (!retired) { val live = checkNotNull(token); put("token", live.token); put("grant", live.grant.epoch); put("revision", live.revision) }
        }
    /** Ledger is saved before the account copy. Re-running this repairs an interrupted account receipt update. */
    fun reconcile(account: JSONObject, grant: PhoneFcmTokenGrant?, snapshot: PhoneFcmTokenSnapshot?) {
        preserve(account)
        val token = snapshot?.takeIf { it.grant == grant }
        val retained = mutableListOf<JSONObject>()
        for (row in rows()) {
            val receipt = row.getJSONObject("receipt")
            val owned = owns(row, account)
            if (!owned && awaitingAccount(row, account, grant) && !row.has("cleanup_deadline")) { retained += row; continue }
            val retired = row.has("cleanup_deadline") || !owned || !grantMatches(row, grant)
            if (retired && !row.has("cleanup_deadline")) row.put("cleanup_deadline", row.optLong("retirement_at", now()) + DAY)
            if (!retired) row.remove("retirement_at")
            if (retired && owned) {
                PhonePushHelperState(account).forget(team(receipt), receipt.getString("origin"))
                val kept = receiptRows(account).filterNot { it.optString("id") == receipt.getString("id") }
                if (kept.isEmpty()) account.remove(PhoneHelperEnrollmentState.RECEIPTS)
                else account.put(PhoneHelperEnrollmentState.RECEIPTS, JSONArray(kept))
            }
            if (row.optLong("cleanup_deadline", Long.MAX_VALUE) <= now()) continue
            var pending = row.optJSONObject("operation")
            if (pending != null && pending.getLong("deadline") <= now()) {
                row.remove("operation"); row.put("failure", "expired"); pending = null
            }
            if (row.has("failure")) {
                if (retired) continue
            } else if (pending != null) {
                if (!desiredMatches(pending, retired, token)) {
                    if (pending.has("response")) pending.put("aborting", true)
                    else {
                        row.remove("operation")
                        if (retired || token != null) row.put("operation", operation(retired, token))
                    }
                }
            } else if (retired || token != null && (receipt.optString("grant") != token.grant.epoch || receipt.optString("token_revision") != token.revision)) {
                row.put("operation", operation(retired, token))
            }
            if (owned) receiptRows(account).singleOrNull { it.optString("id") == receipt.getString("id") }?.apply {
                put("generation", receipt.getString("generation")).put("grant", receipt.getString("grant"))
                    .put("token_revision", receipt.getString("token_revision"))
                if (row.has("failure")) put("maintenance_error", true) else remove("maintenance_error")
                put("maintenance_pending", row.has("operation"))
            }
            retained += row
        }
        save(retained)
    }
    fun pending() = rows().filter { it.has("operation") }.map { QueuedHelperMaintenance(it.toString()) }
    fun current(item: QueuedHelperMaintenance, step: String? = null): Boolean {
        val live = rows().singleOrNull { it.getJSONObject("receipt").getString("id") == item.id }?.optJSONObject("operation") ?: return false
        if (live.getString("id") != item.requestID || live.getLong("deadline") <= now()) return false
        return when (step) {
            "maintain.begin" -> !live.has("response") && !live.optBoolean("aborting")
            "maintain.finish" -> live.has("response") && !live.optBoolean("aborting")
            "maintain.abort" -> live.has("response") && live.optBoolean("aborting")
            else -> true
        }
    }
    private fun change(item: QueuedHelperMaintenance, action: (JSONObject, JSONObject) -> Unit) {
        if (!current(item)) return
        rows().single { it.getJSONObject("receipt").getString("id") == item.id }.let { action(it, it.getJSONObject("operation")) }
    }
    fun checkpoint(item: QueuedHelperMaintenance, response: JSONObject) = change(item) { _, operation ->
        require(response.toString().toByteArray().size <= PhoneHelperHttp.MAX_BODY)
        operation.put("response", JSONObject(response.toString()))
    }
    fun retry(item: QueuedHelperMaintenance, time: Long) = change(item) { _, operation -> operation.put("retry_at", time.coerceAtMost(operation.getLong("deadline"))) }
    fun restartChallenge(item: QueuedHelperMaintenance) = change(item) { row, operation ->
        if (operation.optBoolean("aborting")) row.remove("operation")
        else { operation.remove("response"); operation.remove("retry_at") }
    }
    fun reject(item: QueuedHelperMaintenance) = change(item) { row, _ -> row.remove("operation"); row.put("failure", "rejected") }
    fun complete(item: QueuedHelperMaintenance, receipt: PhoneHelperMaintenanceReceipt?, aborted: Boolean) {
        if (!current(item)) return
        if (receipt != null && item.action == "revoke") { save(rows().filterNot { it.getJSONObject("receipt").getString("id") == item.id }); return }
        change(item) { row, operation ->
            if (receipt != null) {
                check(item.action == "renew" && receipt.registrationID == item.receipt.getString("registration"))
                row.getJSONObject("receipt").put("generation", checkNotNull(receipt.generation))
                    .put("grant", operation.getString("grant")).put("token_revision", operation.getString("revision"))
            } else check(aborted)
            row.remove("operation")
        }
    }
    companion object { const val KEY = "phone_helper_maintenance"; private const val DAY = 86_400_000L }
}
