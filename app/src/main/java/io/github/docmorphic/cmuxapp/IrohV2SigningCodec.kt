package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Base64

/** Canonical proof bytes for cmux's V2 control service; not a credential or transport. */
internal object IrohV2SigningCodec {
    private val maxInteger = BigDecimal("9007199254740991")

    fun encode(value: Any?): ByteArray = canonical(value).toByteArray(Charsets.UTF_8)

    fun enrollment(device: JSONObject, challenge: JSONObject): ByteArray = encode(JSONObject()
        .put("purpose", "cmux-iroh-v2-enrollment")
        .put("device", device)
        .put("challengeId", challenge.getString("challengeId"))
        .put("nonce", challenge.getString("nonce")))

    fun request(device: JSONObject, requestId: String, issuedAt: Long, body: JSONObject, nonce: String): ByteArray = encode(JSONObject()
        .put("purpose", "cmux-iroh-v2-request")
        .put("identity", device.getJSONObject("identity"))
        .put("endpointId", device.getString("endpointId"))
        .put("identityGeneration", device.get("identityGeneration"))
        .put("requestId", requestId)
        .put("issuedAt", issuedAt)
        .put("nonce", nonce)
        .put("body", body))

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun canonical(value: Any?): String = when {
        value == null || value === JSONObject.NULL -> "null"
        value is String -> quote(value)
        value is Boolean -> value.toString()
        value is Number -> {
            val number = runCatching { BigDecimal(value.toString()) }.getOrElse {
                throw IllegalArgumentException("V2 signatures require finite integers")
            }
            require(number.stripTrailingZeros().scale() <= 0 && number.abs() <= maxInteger) {
                "V2 signatures require safe JSON integers"
            }
            number.toBigIntegerExact().toString()
        }
        value is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        value is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            quote(it) + ":" + canonical(value.get(it))
        }
        else -> throw IllegalArgumentException("Unsupported V2 JSON value")
    }

    // JSONObject.quote differs across Android/JVM (slashes and U+2028/U+2029).
    // The Worker requires unescaped Unicode/slashes and UTF-16 key ordering.
    private fun quote(value: String): String = buildString {
        append('"')
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> when {
                    char < ' ' -> append("\\u" + char.code.toString(16).padStart(4, '0'))
                    char.isHighSurrogate() -> {
                        require(index < value.length && value[index].isLowSurrogate()) { "Invalid Unicode in V2 proof" }
                        append(char); append(value[index++])
                    }
                    char.isLowSurrogate() -> throw IllegalArgumentException("Invalid Unicode in V2 proof")
                    else -> append(char)
                }
            }
        }
        append('"')
    }
}
