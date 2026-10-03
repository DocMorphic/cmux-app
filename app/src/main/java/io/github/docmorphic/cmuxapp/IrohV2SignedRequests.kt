package io.github.docmorphic.cmuxapp

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** The deployed server's mobile discriminator is ios; this is an explicit wire profile, not an app identity. */
internal enum class IrohMobileWireProfile { IOS_COMPATIBILITY }

internal object IrohV2AndroidDevice {
    fun descriptor(identity: JSONObject, endpointId: String, appVersion: String, displayName: String,
                   profile: IrohMobileWireProfile, generation: Long = 1): JSONObject {
        require(identity.getString("appNamespace") in setOf("io.github.docmorphic.cmuxapp", "io.github.docmorphic.cmuxapp.debug"))
        require(endpointId.matches(Regex("[0-9a-f]{64}")))
        require(appVersion.length in 1..64 && displayName.length in 1..118)
        require(generation in 1..9007199254740991L)
        val platform = when (profile) { IrohMobileWireProfile.IOS_COMPATIBILITY -> "ios" }
        return JSONObject().put("identity", JSONObject(identity.toString())).put("endpointId", endpointId)
            .put("identityGeneration", generation).put("metadata", JSONObject()
                .put("platform", platform).put("appVersion", appVersion)
                .put("displayName", "$displayName (Android)").put("pairingEnabled", true)
                .put("capabilities", JSONArray().put("irx-v2")).put("relayURLs", JSONArray()))
    }
}

/** Pure signed-envelope builder. The owner supplies its protected native key and current account token. */
internal class IrohV2SignedRequests(
    device: JSONObject,
    private val sign: (ByteArray) -> ByteArray,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nonce: () -> String = { IrohV2SigningCodec.base64Url(ByteArray(16).also { SecureRandom().nextBytes(it) }) }
) {
    private val descriptor = JSONObject(IrohV2SigningCodec.encode(device).toString(Charsets.UTF_8))
    val teamId: String = descriptor.getJSONObject("identity").getString("teamId")
    val endpointId: String = descriptor.getString("endpointId")

    fun operation(schema: String): JSONObject {
        require(schema in IrohV2Wire.requestResponses)
        return JSONObject().put("schemaId", schema).put("requestId", newId())
    }

    fun setup(haveRevision: Long? = null): JSONObject {
        val unsigned = unsignedSetup(newId(), haveRevision)
        val issuedAt = nowSeconds()
        val proofNonce = nonce()
        return unsigned.put("proof", proof(unsigned.getString("requestId"), issuedAt, proofNonce,
            IrohV2SigningCodec.request(descriptor, unsigned.getString("requestId"), issuedAt, unsigned, proofNonce)))
    }

    fun registration(challenge: JSONObject): JSONObject {
        if (IrohV2Wire.integer(challenge, "expiresAt") <= nowSeconds()) throw IOException("Iroh enrollment challenge expired")
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(IrohV2SigningCodec.encode(descriptor))
            .joinToString("") { "%02x".format(it) }
        if (challenge.getString("payloadHash") != expectedHash) throw IOException("Iroh challenge belongs to another device descriptor")
        if (!challenge.getString("nonce").matches(Regex("[A-Za-z0-9_-]{43}"))) throw IOException("Invalid Iroh challenge nonce")
        val id = challenge.getString("challengeId")
        require(id.length in 1..128)
        return operation("device.register.v1").put("device", device()).put("challengeId", id)
            .put("nonce", challenge.getString("nonce"))
            .put("signature", signature(IrohV2SigningCodec.enrollment(descriptor, challenge)))
    }

    fun socketRequest(origin: HttpUrl, authorization: String, setup: JSONObject): Request {
        validateOrigin(origin); validateAuthorization(authorization)
        val encoded = bounded(setup)
        return Request.Builder().url(origin.newBuilder().addPathSegments("v2/control/socket").build())
            .header("Authorization", authorization).header("x-cmux-v2-setup", IrohV2SigningCodec.base64Url(encoded)).build()
    }

    fun httpSessionRequest(origin: HttpUrl, authorization: String, setup: JSONObject): Request {
        validateOrigin(origin); validateAuthorization(authorization)
        return Request.Builder().url(origin.newBuilder().addPathSegments("v2/control/session").build())
            .header("Authorization", authorization).post(bounded(setup).toRequestBody(JSON)).build()
    }

    fun httpOperationRequest(origin: HttpUrl, authorization: String, operation: JSONObject,
                             haveRevision: Long? = null): Request {
        validateOrigin(origin); validateAuthorization(authorization)
        val snapshot = JSONObject(IrohV2SigningCodec.encode(operation).toString(Charsets.UTF_8))
        val id = snapshot.getString("requestId")
        val unsigned = unsignedSetup(id, haveRevision)
        val issuedAt = nowSeconds()
        val proofNonce = nonce()
        val body = JSONObject().put("setup", unsigned).put("request", snapshot)
        val bytes = IrohV2SigningCodec.request(descriptor, id, issuedAt, body, proofNonce)
        val signedSetup = unsigned.put("proof", proof(id, issuedAt, proofNonce, bytes))
        return Request.Builder().url(origin.newBuilder().addPathSegments("v2/requests").build())
            .header("Authorization", authorization)
            .header("x-cmux-v2-setup", IrohV2SigningCodec.base64Url(bounded(signedSetup)))
            .post(bounded(snapshot).toRequestBody(JSON)).build()
    }

    fun acceptDevice(record: JSONObject): JSONObject {
        if (record.getString("deviceRecordId").length !in 1..128) throw IOException("Invalid Iroh device record ID")
        IrohV2Wire.integer(record, "revision")
        val received = record.getJSONObject("descriptor")
        val identityMatches = IrohV2SigningCodec.encode(received.getJSONObject("identity"))
            .contentEquals(IrohV2SigningCodec.encode(descriptor.getJSONObject("identity")))
        if (!identityMatches || received.getString("endpointId") != endpointId ||
            IrohV2Wire.integer(received, "identityGeneration") != IrohV2Wire.integer(descriptor, "identityGeneration") ||
            record.get("revoked") != false) throw IOException("Iroh enrolled device scope mismatch")
        return JSONObject(record.toString())
    }

    fun device(): JSONObject = JSONObject(descriptor.toString())

    private fun unsignedSetup(id: String, haveRevision: Long?) = JSONObject().put("schemaId", "session.open.v1")
        .put("requestId", id).put("device", device()).apply {
            require(id.length in 1..128)
            haveRevision?.let { require(it in 0..9007199254740991L); put("haveRevision", it) }
        }

    private fun proof(id: String, issuedAt: Long, nonce: String, bytes: ByteArray): JSONObject {
        require(nonce.matches(Regex("[A-Za-z0-9_-]{22}")))
        require(issuedAt in 0..9007199254740991L)
        return JSONObject().put("requestId", id).put("issuedAt", issuedAt).put("nonce", nonce).put("signature", signature(bytes))
    }
    private fun signature(bytes: ByteArray): String = sign(bytes).also { require(it.size == 64) }.let(IrohV2SigningCodec::base64Url)
    private fun bounded(value: JSONObject) = IrohV2SigningCodec.encode(value).also {
        require(it.size <= IrohV2Wire.MAX_REQUEST) { "Iroh request too large" }
    }
    private fun validateAuthorization(value: String) {
        require(value.startsWith("Bearer ") || value.startsWith("IrohTicket "))
        require(value.length <= 8203 && value.none { it == '\r' || it == '\n' })
        require(value.substringAfter(' ').isNotBlank())
    }
    private fun validateOrigin(value: HttpUrl) {
        require(value.username.isEmpty() && value.password.isEmpty() && value.query == null && value.fragment == null && value.encodedPath == "/")
        require(value.isHttps || (value.scheme == "http" && value.host in setOf("127.0.0.1", "localhost", "::1"))) {
            "Iroh account traffic requires HTTPS"
        }
    }
    companion object { private val JSON = "application/json; charset=utf-8".toMediaType() }
}
