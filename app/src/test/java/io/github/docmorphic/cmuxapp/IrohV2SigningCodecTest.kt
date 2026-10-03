package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class IrohV2SigningCodecTest {
    private val fixture by lazy {
        JSONObject(checkNotNull(javaClass.getResourceAsStream("/iroh-v2/signing.json")).bufferedReader().use { it.readText() })
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun verify(bytes: ByteArray, canonical: String, signature: String) {
        assertEquals(fixture.getString(canonical), bytes.toString(Charsets.UTF_8))
        // Official Worker vectors; public fixture keys only, never an installed device identity.
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(
            hex("302a300506032b6570032100" + fixture.getJSONObject("device").getString("endpointId"))))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(key); verifier.update(bytes)
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(fixture.getString(signature))))
    }

    @Test fun officialWorkerEnrollmentProofAndSignatureMatch() {
        verify(IrohV2SigningCodec.enrollment(fixture.getJSONObject("device"), fixture.getJSONObject("challenge")),
            "enrollmentCanonical", "enrollmentSignature")
    }

    @Test fun officialWorkerSocketAndHttpProofSignaturesMatch() {
        val device = fixture.getJSONObject("device")
        val setup = fixture.getJSONObject("setup")
        val http = JSONObject().put("setup", setup).put("request", fixture.getJSONObject("httpOperation"))
        for ((body, prefix) in listOf(setup to "request", http to "http")) {
            verify(IrohV2SigningCodec.request(device, setup.getString("requestId"), fixture.getLong("issuedAt"),
                body, fixture.getString("proofNonce")), prefix + "Canonical", prefix + "Signature")
        }
    }

    @Test fun canonicalEncodingRetainsUnicodeSortsUtf16AndNormalizesIntegers() {
        val objectValue = JSONObject().put("\uE000", "/\u2028\u2029")
            .put("🙂", JSONArray().put(JSONObject.NULL).put(true).put(false).put(BigDecimal("-0.0")))
        assertEquals("{\"🙂\":[null,true,false,0],\"\uE000\":\"/\u2028\u2029\"}",
            IrohV2SigningCodec.encode(objectValue).toString(Charsets.UTF_8))
        assertEquals("\"\\b\\f\\n\\r\\t\\u0000\\\"\\\\\"", IrohV2SigningCodec.encode("\b\u000C\n\r\t\u0000\"\\").toString(Charsets.UTF_8))
        assertEquals("[9007199254740991,-9007199254740991,1000]", IrohV2SigningCodec.encode(
            JSONArray().put(9007199254740991L).put(-9007199254740991L).put(BigDecimal("1e3"))).toString(Charsets.UTF_8))
        assertEquals("_-8", IrohV2SigningCodec.base64Url(byteArrayOf(-1, -17)))
    }

    @Test fun rejectsUnsafeNumbersAndInvalidUnicodeBeforeSigning() {
        for (value in listOf(9007199254740992L, -9007199254740992L, 0.5, Double.NaN,
            Double.POSITIVE_INFINITY, "\uD800", "\uDC00", JSONObject().put("\uD800", 1), Any())) {
            assertThrows(IllegalArgumentException::class.java) { IrohV2SigningCodec.encode(value) }
        }
    }
}
