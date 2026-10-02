package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Created once per intentional reply and reused verbatim after uncertain HTTP outcomes. */
internal class PreparedPhoneReply private constructor(
    val replyID: String, val login: String, val origin: String, val teamID: String,
    val peer: PhonePushPeer, val senderKeyID: String, val createdAtMillis: Long, val body: String
) {
    // The phone's retry window is shorter than the Mac inbox's encrypted 15-minute lifetime.
    fun isFresh(nowMillis: Long) = nowMillis >= createdAtMillis && nowMillis - createdAtMillis < 120_000

    fun persisted() = JSONObject().put("version", 1).put("reply_id", replyID).put("login", login)
        .put("origin", origin).put("team_id", teamID).put("peer", peer.wire()).put("sender_key_id", senderKeyID)
        .put("created_at", createdAtMillis).put("body", body)

    companion object {
        /** Only restore from authenticated local storage; the exact body string is never re-encoded. */
        fun restore(value: JSONObject): PreparedPhoneReply {
            require(value.opt("version") == 1)
            fun id(name: String, limit: Int = 128) = (value.opt(name) as? String)?.also {
                require(it.isNotBlank() && it == it.trim() && it.length <= limit)
            } ?: error("Invalid saved reply")
            val replyID = id("reply_id", 64); val login = id("login"); val origin = id("origin")
            val team = id("team_id"); val sender = id("sender_key_id")
            val created = value.opt("created_at").let { if (it is Long) it else if (it is Int) it.toLong() else error("Invalid reply time") }
            require(created > 0 && created <= Long.MAX_VALUE - 900_000)
            val peer = PhonePushPeer.parse(value.getJSONObject("peer"))
            require(peer.tuple.accountID != null && peer.tuple.macDeviceID != null && peer.tuple.macInstanceTag != null && peer.tuple.macBuildID != null)
            val body = value.opt("body") as? String ?: error("Missing saved reply body")
            require(body.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
            val request = MobileJson.objectValue(body, requireComplete = true)
            require(request.keys().asSequence().toSet() == setOf("replyId", "macDeviceId", "macInstanceTag", "encryptedPayload"))
            require(request.opt("replyId") == replyID && request.opt("macDeviceId") == peer.tuple.macDeviceID &&
                request.opt("macInstanceTag") == peer.tuple.macInstanceTag)
            val envelope = request.getJSONObject("encryptedPayload")
            require(envelope.keys().asSequence().toSet() == setOf("version", "installationID", "keyID", "senderKeyID", "encapsulatedKey", "ciphertext", "tuple"))
            require(envelope.opt("version") == 2 && envelope.opt("installationID") == peer.descriptor.installationID &&
                envelope.opt("keyID") == peer.descriptor.keyID && envelope.opt("senderKeyID") == sender &&
                PhonePushTuple.parse(envelope.getJSONObject("tuple")) == peer.tuple)
            fun bytes(name: String, max: Int): ByteArray {
                val encoded = envelope.opt(name) as? String ?: error("Invalid saved reply envelope")
                require(encoded.length <= ((max + 2) / 3) * 4)
                return java.util.Base64.getDecoder().decode(encoded).also { require(it.size <= max) }
            }
            require(bytes("encapsulatedKey", 32).size == 32 && bytes("ciphertext", 64 * 1024).size >= 16)
            return PreparedPhoneReply(replyID, login, origin, team, peer, sender, created, body)
        }

        fun prepare(replyID: String, team: NativeTeamScope, origin: String, peer: PhonePushPeer,
            identity: PhonePushIdentity, workspaceID: String?, surfaceID: String,
            retarget: Boolean, text: String, nowMillis: Long): PreparedPhoneReply {
            fun id(value: String, limit: Int = 128) = require(value.isNotBlank() && value == value.trim() && value.length <= limit) {
                "Invalid reply destination"
            }
            id(replyID, 64); id(surfaceID); id(origin); id(team.login); id(team.userId); id(team.teamId)
            workspaceID?.let { id(it) }; require(retarget || workspaceID != null) { "Reply requires its original workspace" }
            require(text.isNotBlank() && text.length <= 8192) { "Reply must contain 1–8192 characters" }
            require(nowMillis > 0 && nowMillis <= Long.MAX_VALUE - 900_000) { "Invalid reply time" }
            val tuple = peer.tuple
            require(tuple.accountID == team.userId && (tuple.teamID == null || tuple.teamID == team.teamId) &&
                tuple.iosInstallationID == identity.installationID) { "Reply key belongs to another account or installation" }
            listOfNotNull(tuple.accountID, tuple.teamID, tuple.iosBuildID, tuple.iosInstallationID,
                tuple.macDeviceID, tuple.macInstanceTag, tuple.macBuildID, peer.descriptor.installationID,
                peer.descriptor.keyID, identity.keyID).forEach { id(it) }
            require(tuple.macDeviceID != null && tuple.macBuildID != null && tuple.macInstanceTag != null) { "Missing Mac reply identity" }
            PhonePushDescriptor.parse(peer.descriptor.wire())
            val plaintext = JSONObject().put("replyId", replyID).put("accountID", team.userId)
                .put("macDeviceId", tuple.macDeviceID).put("surfaceId", surfaceID)
                .put("workspaceId", workspaceID).put("retargetsToLiveSurfaceOwner", retarget)
                .put("text", text).put("issuedAtEpochSeconds", nowMillis / 1000.0)
                .put("expiresAtEpochSeconds", nowMillis / 1000.0 + 900)
            val envelope = PhonePushCrypto.encryptReply(plaintext.toString().toByteArray(Charsets.UTF_8), tuple, peer.descriptor, identity)
            val body = JSONObject().put("replyId", replyID).put("macDeviceId", tuple.macDeviceID)
                .put("macInstanceTag", tuple.macInstanceTag).put("encryptedPayload", envelope.wire()).toString()
            require(body.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Reply exceeds the encrypted relay size limit" }
            return PreparedPhoneReply(replyID, team.login, origin, team.teamId, peer, identity.keyID, nowMillis, body)
        }
    }
}

internal sealed interface PhoneReplyRelayResult {
    data object Accepted : PhoneReplyRelayResult // Inbox acceptance is not proof of terminal delivery.
    data object Expired : PhoneReplyRelayResult
    data object Retired : PhoneReplyRelayResult
    data object SignInRequired : PhoneReplyRelayResult
    data class Retry(val notBeforeMillis: Long) : PhoneReplyRelayResult
    data class Rejected(val status: Int) : PhoneReplyRelayResult
}

/** One encrypted attempt; caller owns the bounded retry lifecycle and user-visible failure notice. */
internal class PhoneReplyRelay(
    origin: HttpUrl, private val token: suspend () -> String?,
    private val permits: (PreparedPhoneReply) -> Boolean,
    base: OkHttpClient = OkHttpClient(), private val now: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val endpoint: HttpUrl
    private val client = base.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(15, TimeUnit.SECONDS).build()
    private val lock = Any()
    private val calls = mutableSetOf<Call>()
    private var closed = false
    private var retryAt = 0L
    init {
        require(origin.isHttps || origin.host in setOf("localhost", "127.0.0.1", "::1"))
        require(origin.username.isEmpty() && origin.password.isEmpty() && origin.query == null && origin.fragment == null)
        require(origin.encodedPath.endsWith('/'))
        endpoint = origin.newBuilder().addPathSegments("v1/replies/e2e").build()
    }

    suspend fun send(reply: PreparedPhoneReply): PhoneReplyRelayResult {
        fun ownership(): PhoneReplyRelayResult? = synchronized(lock) {
            if (closed || !runCatching { permits(reply) }.getOrDefault(false)) PhoneReplyRelayResult.Retired else null
        }
        fun gate(): PhoneReplyRelayResult? = synchronized(lock) {
            when {
                ownership() != null -> PhoneReplyRelayResult.Retired
                !reply.isFresh(now()) -> PhoneReplyRelayResult.Expired
                now() < retryAt -> PhoneReplyRelayResult.Retry(retryAt)
                else -> null
            }
        }
        gate()?.let { return it }
        try {
            val credential = token()
            gate()?.let { return it }
            if (credential.isNullOrBlank() || credential.length > 8192 || credential.any { it == '\r' || it == '\n' })
                return PhoneReplyRelayResult.SignInRequired
            val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $credential")
                .post(reply.body.toRequestBody("application/json".toMediaType())).build()
            val result = suspendCancellableCoroutine<PhoneReplyRelayResult> { continuation ->
                val call = client.newCall(request)
                val rejected = synchronized(lock) {
                    gate()?.also { continuation.resumeWith(Result.success(it)) } ?: run { calls += call; null }
                }
                if (rejected != null) return@suspendCancellableCoroutine
                continuation.invokeOnCancellation { call.cancel(); synchronized(lock) { calls -= call } }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        synchronized(lock) { calls -= call }
                        continuation.resumeWith(Result.success(gate() ?: PhoneReplyRelayResult.Retry(now() + 5000)))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val outcome = response.use {
                            when {
                                it.code == 429 -> synchronized(lock) {
                                    retryAt = maxOf(retryAt, retryDeadline(it.header("Retry-After"), now()))
                                    PhoneReplyRelayResult.Retry(retryAt)
                                }
                                it.isSuccessful -> PhoneReplyRelayResult.Accepted
                                it.code == 401 -> PhoneReplyRelayResult.SignInRequired
                                it.code == 408 || it.code >= 500 -> PhoneReplyRelayResult.Retry(now() + 5000)
                                else -> PhoneReplyRelayResult.Rejected(it.code)
                            }
                        }
                        synchronized(lock) { calls -= call }
                        // A late success cannot acknowledge work for a replacement login or forgotten Mac.
                        continuation.resumeWith(Result.success(ownership() ?: outcome))
                    }
                })
            }
            return result
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return gate() ?: PhoneReplyRelayResult.Retry(now() + 5000)
        }
    }

    override fun close() {
        val pending = synchronized(lock) { closed = true; calls.toList().also { calls.clear() } }
        pending.forEach(Call::cancel)
    }
    companion object {
        internal fun retryDeadline(value: String?, now: Long): Long {
            val raw = value?.trim()
            val seconds = raw?.toLongOrNull()?.takeIf { it >= 0 }
            val delay = seconds?.coerceAtMost((Long.MAX_VALUE - now) / 1000)?.times(1000)
                ?: raw?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli().coerceAtLeast(now) - now }.getOrNull() } ?: 60_000
            return now + delay
        }
    }
}
