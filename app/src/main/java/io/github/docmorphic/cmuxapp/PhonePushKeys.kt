package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal data class PhonePushDescriptor(val installationID: String, val keyID: String, val publicKey: String) {
    fun wire() = JSONObject().put("version", 1).put("algorithm", "rawX25519")
        .put("installationID", installationID).put("keyID", keyID).put("publicKey", publicKey)
    companion object {
        fun parse(value: JSONObject): PhonePushDescriptor {
            require(value.opt("version") == 1 && value.opt("algorithm") == "rawX25519") { "Invalid push descriptor" }
            fun field(name: String) = checkNotNull(PhonePushTuple.field(value, name)).also { require(it.isNotBlank()) }
            val key = field("publicKey")
            require(key.length == 44) { "Invalid push public key" }
            val bytes = Base64.getDecoder().decode(key)
            require(bytes.size == 32) { "Invalid push public key" }
            // Reject low-order points before pinning. This fixed validation scalar is not a stored key.
            X25519PrivateKeyParameters(ByteArray(32) { 1 }).generateSecret(X25519PublicKeyParameters(bytes), ByteArray(32), 0)
            return PhonePushDescriptor(field("installationID"), field("keyID"), key)
        }
    }
}

internal class PhonePushIdentity(val installationID: String, val keyID: String, val privateKey: ByteArray) {
    fun descriptor() = PhonePushDescriptor(installationID, keyID, Base64.getEncoder().encodeToString(
        X25519PrivateKeyParameters(privateKey).generatePublicKey().encoded))
    fun wire() = JSONObject().put("installationID", installationID).put("keyID", keyID)
        .put("privateKey", Base64.getEncoder().encodeToString(privateKey))
    companion object {
        fun generate() = PhonePushIdentity(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            X25519PrivateKeyParameters(SecureRandom()).encoded)
        fun parse(value: JSONObject): PhonePushIdentity {
            fun field(name: String) = checkNotNull(PhonePushTuple.field(value, name))
            val encoded = field("privateKey"); require(encoded.length == 44)
            val key = Base64.getDecoder().decode(encoded); require(key.size == 32)
            return PhonePushIdentity(field("installationID"), field("keyID"), key)
        }
    }
}

internal data class PhonePushPeer(val tuple: PhonePushTuple, val descriptor: PhonePushDescriptor) {
    fun wire() = JSONObject().put("tuple", tuple.wire()).put("descriptor", descriptor.wire())
    companion object {
        fun parse(value: JSONObject) = PhonePushPeer(PhonePushTuple.parse(value.getJSONObject("tuple")),
            PhonePushDescriptor.parse(value.getJSONObject("descriptor")))
    }
}

/** Lives inside the account's Keystore-encrypted transaction; no plaintext key preferences. */
internal class PhonePushKeyState(private val state: JSONObject) {
    fun existingIdentity(login: String): PhonePushIdentity? {
        if (login != currentLogin()) return null
        val root = state.optJSONObject(KEY)?.takeIf { it.optString("login") == login } ?: return null
        return root.optJSONObject("identity")?.let { runCatching { PhonePushIdentity.parse(it) }.getOrNull() }
    }
    fun canonicalOrigin(team: NativeTeamScope, origin: String): String? =
        if (team.login == currentLogin()) owner(origin, team.userId, team.teamId)?.origin else null

    fun identity(login: String): PhonePushIdentity {
        require(login == currentLogin()) { "Account changed during push setup" }
        prune()
        val root = state.optJSONObject(KEY) ?: JSONObject().put("login", login).also { state.put(KEY, it) }
        root.optJSONObject("identity")?.let { return PhonePushIdentity.parse(it) }
        return PhonePushIdentity.generate().also { root.put("identity", it.wire()) }
    }

    fun pin(team: NativeTeamScope, origin: String, peer: PhonePushPeer) {
        require(team.login == currentLogin()) { "Account changed during push setup" }
        val owner = owner(origin, team.userId, team.teamId) ?: error("Push computer is no longer admitted")
        val identity = identity(team.login)
        require(peer.tuple.accountID == team.userId && (peer.tuple.teamID == null || peer.tuple.teamID == team.teamId) &&
            peer.tuple.iosInstallationID == identity.installationID)
        val root = state.getJSONObject(KEY)
        val previous = rows().singleOrNull { it.optString("origin") == owner.origin }
        val epoch = previous?.takeIf { runCatching { PhonePushPeer.parse(it) == peer }.getOrDefault(false) }
            ?.optString("epoch")?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
        val rows = rows().filterNot { it.optString("origin") == owner.origin }
        root.put("peers", JSONArray((rows + peer.wire().put("origin", owner.origin)
            .put("user", team.userId).put("team", team.teamId).put("epoch", epoch)).takeLast(128)))
    }

    /** Local enrollment incarnation; forget/re-add or a changed key retires old actions. */
    fun peerEpoch(team: NativeTeamScope, origin: String): String? {
        if (peer(team, origin) == null) return null
        val canonical = canonicalOrigin(team, origin) ?: return null
        return rows().singleOrNull { it.optString("origin") == canonical }?.optString("epoch")?.takeIf { it.isNotBlank() }
    }

    fun peer(team: NativeTeamScope, origin: String): PhonePushPeer? {
        prune()
        if (team.login != currentLogin()) return null
        val owner = owner(origin, team.userId, team.teamId) ?: return null
        return rows().singleOrNull { it.optString("origin") == owner.origin && it.optString("user") == team.userId &&
            it.optString("team") == team.teamId }?.let { runCatching { PhonePushPeer.parse(it) }.getOrNull() }
    }

    fun prune() {
        val root = state.optJSONObject(KEY) ?: return
        if (currentLogin() == null || root.optString("login") != currentLogin()) { state.remove(KEY); return }
        val retained = rows().mapNotNull { row ->
            val owner = owner(row.optString("origin"), row.optString("user"), row.optString("team")) ?: return@mapNotNull null
            JSONObject(row.toString()).put("origin", owner.origin)
        }
        // Conflicting aliases need a new authenticated exchange, never arbitrary key selection.
        val groups = retained.groupBy { it.optString("origin") }
        root.put("peers", JSONArray(groups.values.filter { it.size == 1 }.map { it.single() }.takeLast(128)))
    }
    private fun rows(): List<JSONObject> = state.optJSONObject(KEY)?.optJSONArray("peers")?.let { array ->
        (0 until minOf(array.length(), 128)).mapNotNull(array::optJSONObject)
    }.orEmpty()
    private fun currentLogin(): String? = state.optString("task_session").takeIf {
        it.isNotBlank() && state.optString("refresh_token").isNotBlank()
    }
    private fun owner(origin: String, user: String, team: String): NativeCredentialStore.PairedMac? {
        if (user.isBlank() || team.isBlank()) return null
        val array = state.optJSONArray("pairings") ?: return null
        val grants = TailscaleGrantStore({ state }, { error("read only") })
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(NativePairingRecords::decode) }
            .singleOrNull { it.ownsOrigin(origin) && NativePairingRecords.owner(it, grants) == (user to team) }
    }
    companion object { const val KEY = "phone_push_keys" }
}

/** Optional setup on the existing authenticated host connection; feed/terminal readiness never waits. */
internal suspend fun exchangePhonePushKeys(client: MobileRpcClient, status: JSONObject,
    mac: NativeCredentialStore.PairedMac, team: NativeTeamScope, buildID: String,
    permits: () -> Boolean, identity: () -> PhonePushIdentity, pin: (PhonePushPeer) -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) }) {
    val capabilities = status.optJSONArray("capabilities") ?: return
    if ((0 until capabilities.length()).none { capabilities.opt(it) == "phone_push.keys.exchange.v1" }) return
    if (!permits()) return
    mac.requireMatchingHost(status)
    val instance = (status.opt("mac_instance_tag") as? String)?.takeIf { it.isNotBlank() } ?: return
    val namespace = (status.opt("mac_client_namespace") as? String)?.takeIf { it.startsWith("mac:") } ?: return
    require(buildID.isNotBlank() && buildID.length <= 1024)
    repeat(3) { attempt ->
        try {
            if (!permits()) return
            val local = identity()
            val response = withTimeout(3000) { client.exchangePhonePushKey(buildID, local.descriptor()) }
            if (!permits()) return
            require(response.opt("version") == 1 && response.opt("hpke_envelope_version") == 2)
            fun field(name: String) = checkNotNull(PhonePushTuple.field(response, name)).also { require(it.isNotBlank()) }
            val account = field("account_id"); val device = field("mac_device_id")
            val tag = field("mac_instance_tag"); val build = field("mac_build_id")
            val responseTeam = PhonePushTuple.field(response, "team_id", optional = true)
            require(account == team.userId && tag == instance && namespace == "mac:" + build.lowercase(Locale.ROOT) &&
                (responseTeam == null || responseTeam == team.teamId)) { "Push key response contradicts admitted host" }
            // The host-status device is a directory identity; the response device is its physical push identity.
            val peer = PhonePushPeer(PhonePushTuple(account, responseTeam, buildID, local.installationID, device, tag, build),
                PhonePushDescriptor.parse(response.getJSONObject("descriptor")))
            if (permits()) pin(peer)
            return
        } catch (_: Exception) { currentCoroutineContext().ensureActive() }
        if (attempt < 2) pause(1000L shl attempt)
    }
}
