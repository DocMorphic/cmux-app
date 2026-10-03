package io.github.docmorphic.cmuxapp

import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class IrohV2SignedRequestsTest {
    private val fixture = JSONObject(checkNotNull(javaClass.getResourceAsStream("/iroh-v2/signing.json"))
        .bufferedReader().use { it.readText() })
    private val device get() = fixture.getJSONObject("device")
    private val origin = "https://cmux-iroh-v2.debussy.workers.dev/".toHttpUrl()
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun signer(bytes: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(
            hex("302e020100300506032b657004220420" + fixture.getString("secretSeedHex"))))
        return Signature.getInstance("Ed25519").apply { initSign(key); update(bytes) }.sign()
    }
    private fun requests() = IrohV2SignedRequests(device, ::signer, { fixture.getLong("issuedAt") },
        { "request-one" }, { fixture.getString("proofNonce") })

    @Test fun setupAndHttpProofsMatchOfficialWorkerSignatures() {
        val requests = requests()
        val setup = requests.setup(9)
        assertEquals(fixture.getString("requestSignature"), setup.getJSONObject("proof").getString("signature"))
        val socket = requests.socketRequest(origin, "Bearer test-token", setup)
        assertEquals("/v2/control/socket", socket.url.encodedPath)
        val decoded = JSONObject(String(Base64.getUrlDecoder().decode(socket.header("x-cmux-v2-setup"))))
        assertEquals("request-one", decoded.getString("requestId"))
        val http = requests.httpOperationRequest(origin, "IrohTicket test-ticket", fixture.getJSONObject("httpOperation"), 9)
        val header = JSONObject(String(Base64.getUrlDecoder().decode(http.header("x-cmux-v2-setup"))))
        assertEquals(fixture.getString("httpSignature"), header.getJSONObject("proof").getString("signature"))
        assertEquals("/v2/requests", http.url.encodedPath)
        val body = Buffer().also { http.body!!.writeTo(it) }.readUtf8()
        assertEquals("relay.request.v1", JSONObject(body).getString("schemaId"))
        assertFalse(JSONObject(body).has("proof"))
        val session = requests.httpSessionRequest(origin, "Bearer test-token", setup)
        assertEquals("/v2/control/session", session.url.encodedPath)
        assertEquals("POST", session.method)
    }

    @Test fun enrollmentBindsDescriptorAndRejectsExpiredOrDifferentChallenge() {
        val challenge = JSONObject(fixture.getJSONObject("challenge").toString()).put("payloadHash",
            MessageDigest.getInstance("SHA-256").digest(IrohV2SigningCodec.encode(device))
                .joinToString("") { "%02x".format(it) })
        val request = requests().registration(challenge)
        assertEquals(fixture.getString("enrollmentSignature"), request.getString("signature"))
        assertEquals("device.register.v1", request.getString("schemaId"))
        assertThrows(IOException::class.java) { requests().registration(JSONObject(challenge.toString()).put("payloadHash", "0".repeat(64))) }
        assertThrows(IOException::class.java) { requests().registration(JSONObject(challenge.toString()).put("expiresAt", fixture.getLong("issuedAt"))) }
    }

    @Test fun acceptedRecordMustMatchEveryIdentityFieldKeyGenerationAndRevocationState() {
        val record = JSONObject().put("deviceRecordId", "record").put("revision", 3).put("revoked", false).put("descriptor", device)
        assertEquals("record", requests().acceptDevice(record).getString("deviceRecordId"))
        for (field in device.getJSONObject("identity").keys()) {
            val changed = JSONObject(record.toString())
            changed.getJSONObject("descriptor").getJSONObject("identity").put(field, "different")
            assertThrows(IOException::class.java) { requests().acceptDevice(changed) }
        }
        for (field in listOf("endpointId", "identityGeneration")) {
            val changed = JSONObject(record.toString())
            changed.getJSONObject("descriptor").put(field, if (field == "endpointId") "0".repeat(64) else 2)
            assertThrows(IOException::class.java) { requests().acceptDevice(changed) }
        }
        assertThrows(IOException::class.java) { requests().acceptDevice(JSONObject(record.toString()).put("revoked", true)) }
    }

    @Test fun androidProfileKeepsIndependentIdentityAndCredentialOriginsAreRestricted() {
        val identity = JSONObject(device.getJSONObject("identity").toString()).put("appNamespace", "io.github.docmorphic.cmuxapp.debug")
        val descriptor = IrohV2AndroidDevice.descriptor(identity, device.getString("endpointId"), "0.2.0", "Pixel 6a", IrohMobileWireProfile.IOS_COMPATIBILITY)
        assertEquals("io.github.docmorphic.cmuxapp.debug", descriptor.getJSONObject("identity").getString("appNamespace"))
        assertEquals("Pixel 6a (Android)", descriptor.getJSONObject("metadata").getString("displayName"))
        assertEquals("ios", descriptor.getJSONObject("metadata").getString("platform"))
        assertEquals(1, descriptor.getInt("identityGeneration"))
        identity.put("appNamespace", "dev.cmux.ios")
        assertThrows(IllegalArgumentException::class.java) { IrohV2AndroidDevice.descriptor(identity, device.getString("endpointId"), "1", "Pixel", IrohMobileWireProfile.IOS_COMPATIBILITY) }
        for (url in listOf("http://example.com/", "https://user:pass@example.com/", "https://example.com/path", "https://example.com/?query=1"))
            assertThrows(IllegalArgumentException::class.java) { requests().socketRequest(url.toHttpUrl(), "Bearer token", requests().setup()) }
        assertThrows(IllegalArgumentException::class.java) { requests().socketRequest(origin, "Bearer token\r\nInjected: yes", requests().setup()) }
    }
}
