package io.github.docmorphic.cmuxapp

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONObject
import java.util.Base64

/** Wire names retain iOS spelling for compatibility with cmux's v2 envelope. */
internal data class PhonePushTuple(
    val accountID: String?, val teamID: String?, val iosBuildID: String,
    val iosInstallationID: String, val macDeviceID: String?,
    val macInstanceTag: String?, val macBuildID: String?
) {
    fun wire() = JSONObject().put("accountID", accountID).put("teamID", teamID)
        .put("iosBuildID", iosBuildID).put("iosInstallationID", iosInstallationID)
        .put("macDeviceID", macDeviceID).put("macInstanceTag", macInstanceTag).put("macBuildID", macBuildID)

    // JSONEncoder.sortedKeys escapes slashes; Android/JVM JSONObject.quote differs.
    fun canonical(): ByteArray = IrohV2SigningCodec.encode(wire()).toString(Charsets.UTF_8)
        .replace("/", "\\/").toByteArray(Charsets.UTF_8)

    companion object {
        fun parse(value: JSONObject) = PhonePushTuple(
            field(value, "accountID", optional = true), field(value, "teamID", optional = true),
            checkNotNull(field(value, "iosBuildID")), checkNotNull(field(value, "iosInstallationID")),
            field(value, "macDeviceID", optional = true), field(value, "macInstanceTag", optional = true),
            field(value, "macBuildID", optional = true))

        internal fun field(value: JSONObject, key: String, optional: Boolean = false): String? {
            if (optional && (!value.has(key) || value.isNull(key))) return null
            val text = value.opt(key) as? String ?: error("Invalid push identity")
            require(text.isNotEmpty() && text.length <= 1024) { "Invalid push identity" }
            return text
        }
    }
}

internal data class PhonePushEnvelope(
    val installationID: String, val keyID: String, val senderKeyID: String,
    val encapsulatedKey: String, val ciphertext: String, val tuple: PhonePushTuple,
    val version: Int = 2
) {
    fun wire() = JSONObject().put("installationID", installationID).put("keyID", keyID)
        .put("senderKeyID", senderKeyID).put("encapsulatedKey", encapsulatedKey)
        .put("ciphertext", ciphertext).put("tuple", tuple.wire()).put("version", version)

    companion object {
        fun parse(value: JSONObject): PhonePushEnvelope {
            require(value.opt("version") == 2) { "Unsupported push envelope" }
            fun id(name: String) = checkNotNull(PhonePushTuple.field(value, name))
            fun bytes(name: String, limit: Int) = (value.opt(name) as? String)?.also {
                require(it.length <= limit) { "Push envelope exceeds limit" }
            } ?: error("Invalid push envelope")
            return PhonePushEnvelope(id("installationID"), id("keyID"), id("senderKeyID"),
                bytes("encapsulatedKey", 44), bytes("ciphertext", 21848),
                PhonePushTuple.parse(value.getJSONObject("tuple")))
        }
    }
}

/** cmux authenticated HPKE v2. Trust, freshness and replay admission belong to the caller;
 * an envelope must never be used as the source of its own expected identity or sender key. */
internal object PhonePushCrypto {
    const val ALGORITHM = "x25519-hpke-sha256-chacha20poly1305-v2"
    private const val MAX_CIPHERTEXT = 16 * 1024
    private fun suite() = HPKE(HPKE.mode_auth, HPKE.kem_X25519_SHA256,
        HPKE.kdf_HKDF_SHA256, HPKE.aead_CHACHA20_POLY1305)
    private fun privateKey(bytes: ByteArray): AsymmetricCipherKeyPair {
        require(bytes.size == 32) { "Invalid push key" }
        val key = X25519PrivateKeyParameters(bytes)
        return AsymmetricCipherKeyPair(key.generatePublicKey(), key)
    }
    private fun publicKey(bytes: ByteArray): X25519PublicKeyParameters {
        require(bytes.size == 32) { "Invalid push key" }
        return X25519PublicKeyParameters(bytes)
    }
    private fun binding(tuple: PhonePushTuple, keyID: String, senderKeyID: String) =
        "cmux-phone-push-v2|$keyID|$senderKeyID|".toByteArray(Charsets.UTF_8) + tuple.canonical()
    private fun decode(text: String, maximum: Int): ByteArray {
        require(text.length <= ((maximum + 2) / 3) * 4) { "Push envelope exceeds limit" }
        val bytes = Base64.getDecoder().decode(text)
        require(bytes.size <= maximum) { "Push envelope exceeds limit" }
        return bytes
    }

    fun decrypt(envelope: PhonePushEnvelope, expected: PhonePushTuple,
        installationID: String, keyID: String, senderKeyID: String,
        senderPublicKey: ByteArray, recipientPrivateKey: ByteArray): ByteArray {
        require(envelope.version == 2 && envelope.tuple == expected &&
            expected.iosInstallationID == installationID && envelope.installationID == installationID &&
            envelope.keyID == keyID && envelope.senderKeyID == senderKeyID) { "Push identity mismatch" }
        val encapsulated = decode(envelope.encapsulatedKey, 32)
        require(encapsulated.size == 32) { "Invalid push encapsulation" }
        val ciphertext = decode(envelope.ciphertext, MAX_CIPHERTEXT)
        require(ciphertext.size >= 16) { "Invalid push ciphertext" }
        val context = binding(expected, keyID, senderKeyID)
        return suite().setupAuthR(encapsulated, privateKey(recipientPrivateKey), context, publicKey(senderPublicKey))
            .open(context, ciphertext)
    }

    /** Generates a phone envelope with fresh HPKE encapsulation per message. */
    fun encrypt(plaintext: ByteArray, tuple: PhonePushTuple, keyID: String, senderKeyID: String,
        recipientPublicKey: ByteArray, senderPrivateKey: ByteArray): PhonePushEnvelope {
        require(plaintext.size <= MAX_CIPHERTEXT - 16) { "Push plaintext exceeds limit" }
        val context = binding(tuple, keyID, senderKeyID)
        val sender = suite().setupAuthS(publicKey(recipientPublicKey), context, privateKey(senderPrivateKey))
        return PhonePushEnvelope(tuple.iosInstallationID, keyID, senderKeyID,
            Base64.getEncoder().encodeToString(sender.encapsulation),
            Base64.getEncoder().encodeToString(sender.seal(context, plaintext)), tuple)
    }
}
