package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

/** Only authenticated plaintext can create this value. Its default description excludes content. */
internal class PhonePushMessage private constructor(
    val team: NativeTeamScope, val origin: String, val peer: PhonePushPeer, val recipientKeyID: String, val peerEpoch: String?,
    val replyPeer: PhonePushPeer, val helperEpoch: String?,
    val correlationID: String, val expiresAtMillis: Long, val badgeCount: Int,
    val notification: NativeNotification?, val hasNotificationID: Boolean, val canReply: Boolean,
    val dismissedIDs: List<String>
) {
    fun isFresh(now: Long) = now >= 0 && now < expiresAtMillis
    fun permits(state: JSONObject): Boolean {
        val keys = PhonePushKeyState(state)
        if (keys.existingIdentity(team.login)?.keyID != recipientKeyID || keys.peer(team, origin) != replyPeer ||
            keys.peerEpoch(team, origin) != peerEpoch) return false
        if (helperEpoch != null) {
            val helper = PhonePushHelperState(state).binding(team, origin) ?: return false
            if (helper.epoch != helperEpoch || helper.peer != peer || helper.macPeer != replyPeer) return false
        } else if (peer != replyPeer) return false
        val rows = state.optJSONArray("pairings") ?: return false
        val mac = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(NativePairingRecords::decode) }
            .singleOrNull { it.ownsOrigin(origin) } ?: return false
        return NativePairingRecords.usable(mac, team, TailscaleGrantStore({ state }, { error("read only") }))
    }
    companion object {
        /** The cmux dictionary, not aps, supplies ciphertext. Outer plaintext is never trusted.
         * The caller independently admits the team/Mac before opening and again before delivery. */
        fun open(raw: String, state: JSONObject, team: NativeTeamScope, origin: String,
            buildID: String, now: Long): PhonePushMessage {
            require(raw.length <= 1024 * 1024 && raw.toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
            val keys = PhonePushKeyState(state)
            val identity = checkNotNull(keys.existingIdentity(team.login))
            val canonical = checkNotNull(keys.canonicalOrigin(team, origin))
            val replyPeer = checkNotNull(keys.peer(team, canonical))
            val helper = PhonePushHelperState(state).binding(team, canonical)
            require(replyPeer.tuple.iosBuildID == buildID && replyPeer.tuple.accountID == team.userId &&
                (replyPeer.tuple.teamID == null || replyPeer.tuple.teamID == team.teamId))
            val input = MobileJson.objectValue(raw, requireComplete = true).getJSONArray("encryptedPayloads")
            require(input.length() in 1..200)
            val candidates = (0 until input.length()).mapNotNull { i -> input.optJSONObject(i)?.let {
                runCatching { PhonePushEnvelope.parse(it) }.getOrNull()
            } }.filter { it.installationID == identity.installationID && it.tuple == replyPeer.tuple }
            // Multiple envelopes for the same admitted recipient are ambiguous, even if one opens.
            val envelope = candidates.single()
            val peer = when (envelope.senderKeyID) {
                replyPeer.descriptor.keyID -> replyPeer
                helper?.peer?.descriptor?.keyID -> checkNotNull(helper).peer
                else -> error("Push sender is not enrolled")
            }
            val helperEpoch = helper?.takeIf { it.peer == peer }?.epoch
            val bytes = PhonePushCrypto.decrypt(envelope, peer.tuple, identity.installationID, identity.keyID,
                peer.descriptor.keyID, Base64.getDecoder().decode(peer.descriptor.publicKey), identity.privateKey)
            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val payload = MobileJson.objectValue(decoded, requireComplete = true)
            fun string(name: String, max: Int, optional: Boolean = false): String? {
                if (optional && (!payload.has(name) || payload.isNull(name))) return null
                return (payload.opt(name) as? String)?.also { require(it.length <= max) } ?: error("Invalid push field")
            }
            fun id(name: String, optional: Boolean = false) = string(name, 200, optional)?.also {
                require(it.isNotBlank() && it == it.trim())
            }
            fun boolean(name: String) = payload.opt(name) as? Boolean ?: error("Invalid push flag")
            fun integer(name: String): Long = when (val value = payload.opt(name)) {
                is Int -> value.toLong()
                is Long -> value
                else -> error("Invalid push integer")
            }
            val correlation = checkNotNull(id("correlationId"))
            require(UUID.fromString(correlation).toString() == correlation)
            val expiration = integer("expirationEpochSeconds")
            require(expiration in 1..Long.MAX_VALUE / 1000 && now >= 0 && expiration * 1000 > now)
            val badge = integer("badgeCount"); require(badge in 0..Int.MAX_VALUE.toLong())
            val hidden = boolean("hideContent")
            // Even authenticated plaintext must not contradict its pinned envelope identity.
            for ((name, expected) in listOf("macDeviceId" to peer.tuple.macDeviceID,
                "macInstanceTag" to peer.tuple.macInstanceTag, "macBuildID" to peer.tuple.macBuildID,
                "macInstallationID" to peer.descriptor.installationID, "macPushPublicKey" to peer.descriptor.publicKey)) {
                if (payload.has(name)) require(string(name, 1024) == expected)
            }
            var item: NativeNotification? = null; var hasID = false; var reply = false
            var dismissed = emptyList<String>()
            when (string("kind", 16)) {
                "notify" -> {
                    val notificationID = id("notificationId", optional = true); hasID = notificationID != null
                    val workspace = id("workspaceId", optional = true); val surface = id("surfaceId", optional = true)
                    val retarget = boolean("retargetsToLiveSurfaceOwner")
                    val category = string("category", 200); val shape = string("replyShape", 200)
                    val title = string("title", 120)!!; val subtitle = string("subtitle", 120)!!; val body = string("body", 500)!!
                    item = NativeNotification(notificationID ?: "push.$correlation", workspace.orEmpty(), surface,
                        if (hidden) "cmux" else title, if (hidden) "New terminal activity" else body, false,
                        subtitle = if (hidden) "" else subtitle, retargetsToLiveSurfaceOwner = retarget)
                    reply = category == "cmux.terminal.reply" && shape == "text" && surface != null && (workspace != null || retarget)
                }
                "dismiss" -> {
                    val ids = payload.getJSONArray("notificationIds"); require(ids.length() in 1..4096)
                    dismissed = (0 until ids.length()).map { i -> (ids.opt(i) as? String)?.also {
                        require(it.isNotBlank() && it == it.trim() && it.length <= 200)
                    } ?: error("Invalid dismissed notification") }.distinct()
                }
                else -> error("Unknown push operation")
            }
            return PhonePushMessage(team, canonical, peer, identity.keyID, keys.peerEpoch(team, canonical), replyPeer, helperEpoch, correlation, expiration * 1000,
                badge.toInt(), item, hasID, reply, dismissed).also { require(it.permits(state)) }
        }
    }
}
