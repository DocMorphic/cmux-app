package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun phoneEnrollmentFrame(domain: String, fields: List<String?>): ByteArray = ByteArrayOutputStream().use { buffer ->
    DataOutputStream(buffer).use { stream ->
        (listOf(domain) + fields).forEach { value ->
            if (value == null) stream.writeInt(-1) else {
                require(Charsets.UTF_8.newEncoder().canEncode(value))
                val bytes = value.toByteArray(Charsets.UTF_8); require(bytes.size <= 16_384)
                stream.writeInt(bytes.size); stream.write(bytes)
            }
        }
    }
    buffer.toByteArray()
}
internal data class PhoneHelperEnrollmentReceipt(val registrationID: String, val generation: String, val binding: PhonePushHelperBinding)

/** User-confirmed offer only. Transport must use this exact HTTPS endpoint without redirects.
 * No offer/network response can select the account, native Mac peer, token project or phone private key.
 */
internal class PhoneHelperEnrollment(
    rawOffer: String, private val team: NativeTeamScope, private val origin: String,
    private val native: PhonePushPeer, private val nativeEpoch: String,
    private val phone: PhonePushIdentity, private val token: PhoneFcmTokenSnapshot,
    private val current: () -> Boolean, private val now: () -> Long = System::currentTimeMillis,
    private val requestID: String = UUID.randomUUID().toString()
) : AutoCloseable {
    private val offer = MobileJson.objectValue(rawOffer.also { require(it.toByteArray().size <= 8192) }, requireComplete = true)
    val endpoint: String = offer.getString("endpoint")
    private val offerID = offer.getString("offerID")
    private val expires = offer.getLong("expiresAt")
    private val helper = PhonePushDescriptor.parse(offer.getJSONObject("helper"))
    private val project = PhoneFcmProject.parse(offer.getJSONObject("project"))
    private val secret = decode(offer.getString("secret"), 32)
    private val offerDigest: String
    private val requestDigest: String
    private val request: JSONObject
    private var challenge: ByteArray? = null
    private var closed = false
    init {
        exact(offer, setOf("version", "offerID", "expiresAt", "endpoint", "project", "phoneBuildID", "mac", "native", "helper", "secret"))
        require(offer.opt("version") == 1 && uuid(offerID) && uuid(requestID))
        require(expires > 0 && offer.opt("expiresAt") is Number && offer.get("expiresAt").toString() == expires.toString())
        val uri = URI(endpoint)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.path == "/v1/push/enroll" && endpoint == "https://${uri.rawAuthority}/v1/push/enroll")
        exact(offer.getJSONObject("project"), setOf("project", "application", "sender"))
        val mac = offer.getJSONObject("mac"); exact(mac, MAC_FIELDS.toSet())
        val tuple = native.tuple
        require(tuple.accountID == team.userId && (tuple.teamID == null || tuple.teamID == team.teamId) &&
            tuple.iosInstallationID == phone.installationID && tuple.iosBuildID == offer.getString("phoneBuildID"))
        val expected = listOf(tuple.accountID, tuple.teamID, tuple.macDeviceID, tuple.macInstanceTag, tuple.macBuildID)
        require(expected.filterIndexed { index, _ -> index != 1 }.all { it != null && it.isNotBlank() && it.length <= 1024 })
        require(MAC_FIELDS.map { if (mac.isNull(it)) null else mac.getString(it) } == expected)
        require(PhonePushDescriptor.parse(offer.getJSONObject("native")) == native.descriptor && nativeEpoch.isNotBlank())
        require(helper.installationID != native.descriptor.installationID && helper.keyID != native.descriptor.keyID && helper.publicKey != native.descriptor.publicKey)
        require(token.grant.login == team.login && token.grant.project == project && PhoneFcmTokenState.validToken(token.token))
        offerDigest = digest(phoneEnrollmentFrame("cmux-app.helper.offer.v1", listOf(offerID, expires.toString(), endpoint,
            project.project, project.application, project.sender, tuple.iosBuildID) + expected + fields(native.descriptor) + fields(helper)))
        requestDigest = digest(phoneEnrollmentFrame("cmux-app.helper.request.v1", listOf(offerDigest, requestID) + fields(phone.descriptor()) + token.token))
        request = JSONObject().put("offerID", offerID).put("requestID", requestID).put("phone", phone.descriptor().wire())
            .put("token", token.token).put("proof", proof(secret, "cmux-app.helper.begin.v1", listOf(offerDigest, requestDigest)))
        checkCurrent()
    }
    private fun checkCurrent() {
        val time = now()
        check(!closed && current() && time >= 0 && expires > time && expires - time <= 300_000) { "Helper enrollment is no longer current" }
    }
    fun begin(): JSONObject { checkCurrent(); return JSONObject(request.toString()) }
    fun finish(response: JSONObject): JSONObject {
        checkCurrent(); exact(response, setOf("offerID", "requestID", "envelope"))
        require(response.getString("offerID") == offerID && response.getString("requestID") == requestID)
        val envelope = PhonePushEnvelope.parse(response.getJSONObject("envelope"))
        val plaintext = PhonePushCrypto.decrypt(envelope, native.tuple, phone.installationID, phone.keyID,
            helper.keyID, Base64.getDecoder().decode(helper.publicKey), phone.privateKey)
        try {
            require(plaintext.size <= 8192)
            val decoded = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plaintext)).toString()
            val value = MobileJson.objectValue(decoded, requireComplete = true)
            exact(value, setOf("version", "kind", "offerID", "requestID", "offerDigest", "requestDigest", "challenge", "expiresAt"))
            require(value.opt("version") == 1 && value.getString("kind") == "cmux-app.helper.enrollment" &&
                value.getString("offerID") == offerID && value.getString("requestID") == requestID &&
                value.getString("offerDigest") == offerDigest && value.getString("requestDigest") == requestDigest &&
                value.get("expiresAt").toString() == expires.toString())
            val received = decode(value.getString("challenge"), 32)
            challenge?.let { require(MessageDigest.isEqual(it, received)) { "Helper changed an active challenge" } }
            challenge = received
        } finally { plaintext.fill(0) }
        checkCurrent()
        return JSONObject().put("offerID", offerID).put("requestID", requestID)
            .put("proof", proof(checkNotNull(challenge), "cmux-app.helper.finish.v1", listOf(offerDigest, requestDigest)))
    }
    /** Run inside the account transaction, with current() rechecking the captured token snapshot too. */
    fun confirm(response: JSONObject, state: JSONObject): PhoneHelperEnrollmentReceipt {
        checkCurrent(); exact(response, setOf("offerID", "requestID", "registration", "proof"))
        require(response.getString("offerID") == offerID && response.getString("requestID") == requestID)
        val registration = response.getJSONObject("registration"); exact(registration, setOf("id", "generation"))
        val id = registration.getString("id"); val generation = registration.getString("generation")
        require(uuid(id) && uuid(generation))
        val expected = proof(checkNotNull(challenge), "cmux-app.helper.ack.v1", listOf(offerDigest, requestDigest, id, generation))
        require(MessageDigest.isEqual(decode(response.getString("proof"), 32), decode(expected, 32)))
        checkCurrent()
        val binding = PhonePushHelperState(state).pin(team, origin, native, nativeEpoch, helper, project)
        return PhoneHelperEnrollmentReceipt(id, generation, binding)
    }
    override fun close() { closed = true; secret.fill(0); challenge?.fill(0); challenge = null }
    companion object {
        private val MAC_FIELDS = listOf("accountID", "teamID", "macDeviceID", "macInstanceTag", "macBuildID")
        private fun fields(value: PhonePushDescriptor) = listOf(value.installationID, value.keyID, value.publicKey)
        private fun uuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        private fun exact(value: JSONObject, keys: Set<String>) { require(value.keys().asSequence().toSet() == keys) }
        private fun decode(value: String, count: Int): ByteArray {
            require(value.length <= (count + 2) / 3 * 4)
            return Base64.getUrlDecoder().decode(value).also { require(it.size == count && Base64.getUrlEncoder().withoutPadding().encodeToString(it) == value) }
        }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun proof(key: ByteArray, domain: String, fields: List<String?>): String {
            val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(key, "HmacSHA256"))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(phoneEnrollmentFrame(domain, fields)))
        }
    }
}
