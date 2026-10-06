package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class PhoneHelperMaintenanceReceipt(val registrationID: String?, val generation: String?)

/** Existing verified helper only. Durable caller fences the receipt, native/helper/phone keys and token intent. */
internal class PhoneHelperMaintenance(
    val endpoint: String, private val registrationID: String, generation: String,
    private val binding: PhonePushHelperBinding, private val phone: PhonePushIdentity,
    private val action: String, token: String?, private val current: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis, private val requestID: String = UUID.randomUUID().toString()
) : AutoCloseable {
    private val request: JSONObject
    internal val requestDigest: String
    private var challenge: ByteArray? = null
    private var expiresAt = 0L
    private var closed = false
    init {
        val url = endpoint.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
            url.encodedPath == "/v1/push/enroll" && url.toString() == endpoint)
        require(uuid(registrationID) && uuid(generation) && uuid(requestID))
        require(if (action == "renew") token != null && PhoneFcmTokenState.validToken(token) else action == "revoke" && token == null)
        require(binding.peer.tuple == binding.macPeer.tuple && binding.peer.tuple.iosInstallationID == phone.installationID)
        val tuple = binding.peer.tuple
        val recipient = JSONObject().put("installationID", phone.installationID).put("keyID", phone.keyID)
            .put("senderKeyID", binding.peer.descriptor.keyID).put("tuple", tuple.wire())
        val fields = listOf(endpoint, requestID, action, registrationID, generation, phone.installationID, phone.keyID,
            binding.peer.descriptor.keyID, tuple.accountID, tuple.teamID, tuple.iosBuildID, tuple.iosInstallationID,
            tuple.macDeviceID, tuple.macInstanceTag, tuple.macBuildID, token)
        require(listOf(tuple.accountID, tuple.macDeviceID, tuple.macInstanceTag, tuple.macBuildID).all { !it.isNullOrBlank() })
        requestDigest = MessageDigest.getInstance("SHA-256").digest(phoneEnrollmentFrame("cmux-app.helper.registration.request.v1", fields))
            .joinToString("") { "%02x".format(it) }
        request = JSONObject().put("requestID", requestID).put("registration", JSONObject().put("id", registrationID).put("generation", generation))
            .put("recipient", recipient).put("action", action).put("token", token ?: JSONObject.NULL)
        checkCurrent()
    }
    private fun checkCurrent() { check(!closed && current() && now() >= 0) { "Push registration changed" } }
    fun begin(): JSONObject { checkCurrent(); return JSONObject(request.toString()) }
    /** retry is allowed only for a challenge already saved durably before the first finish write. */
    fun finish(response: JSONObject, retry: Boolean = false): JSONObject {
        checkCurrent(); exact(response, setOf("requestID", "envelope")); require(response.getString("requestID") == requestID)
        val sender = binding.peer.descriptor
        val plaintext = PhonePushCrypto.decrypt(PhonePushEnvelope.parse(response.getJSONObject("envelope")), binding.peer.tuple,
            phone.installationID, phone.keyID, sender.keyID, Base64.getDecoder().decode(sender.publicKey), phone.privateKey)
        try {
            require(plaintext.size <= 8192)
            val value = MobileJson.objectValue(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plaintext)).toString(), requireComplete = true)
            exact(value, setOf("version", "kind", "requestID", "requestDigest", "challenge", "expiresAt"))
            require(value.opt("version") == 1 && value.getString("kind") == "cmux-app.helper.registration" &&
                value.getString("requestID") == requestID && value.getString("requestDigest") == requestDigest)
            val expiry = value.getLong("expiresAt"); val time = now()
            require(expiry > 0 && value.get("expiresAt").toString() == expiry.toString() && expiry - time <= 120_000 &&
                (expiry > time || retry && time - expiry <= 86_400_000))
            val received = decode(value.getString("challenge"))
            challenge?.let { require(MessageDigest.isEqual(it, received) && expiresAt == expiry); it.fill(0) }
            challenge = received; expiresAt = expiry
        } finally { plaintext.fill(0) }
        checkCurrent()
        return JSONObject().put("requestID", requestID).put("proof", proof("cmux-app.helper.registration.finish.v1", listOf(requestDigest)))
    }
    fun confirm(response: JSONObject): PhoneHelperMaintenanceReceipt {
        checkCurrent(); check(challenge != null && expiresAt - now() <= 120_000 && now() - expiresAt <= 86_400_000)
        exact(response, setOf("requestID", "action", "registration", "proof"))
        require(response.getString("requestID") == requestID && response.getString("action") == action)
        val receipt = if (action == "renew") {
            val value = response.getJSONObject("registration"); exact(value, setOf("id", "generation"))
            val id = value.getString("id"); val next = value.getString("generation")
            require(id == registrationID && uuid(next) && next != request.getJSONObject("registration").getString("generation"))
            PhoneHelperMaintenanceReceipt(id, next)
        } else {
            require(response.isNull("registration")); PhoneHelperMaintenanceReceipt(null, null)
        }
        require(MessageDigest.isEqual(decode(response.getString("proof")), decode(proof("cmux-app.helper.registration.ack.v1",
            listOf(requestDigest, receipt.registrationID, receipt.generation)))))
        checkCurrent(); return receipt
    }
    private fun proof(domain: String, fields: List<String?>): String {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(checkNotNull(challenge), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(phoneEnrollmentFrame(domain, fields)))
    }
    override fun close() { closed = true; challenge?.fill(0); challenge = null }
    companion object {
        private fun uuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        private fun exact(value: JSONObject, keys: Set<String>) { require(value.keys().asSequence().toSet() == keys) }
        private fun decode(value: String): ByteArray {
            require(value.length == 43)
            return Base64.getUrlDecoder().decode(value).also {
                require(it.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(it) == value)
            }
        }
    }
}
