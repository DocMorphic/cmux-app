package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PhoneHelperMaintenanceTest {
    private class Fixture {
        val c = PhoneHelperEnrollmentTest.Context(JSONObject(javaClass.getResource("/push/helper-enrollment.json")!!.readText()))
        private val vector = JSONArray(javaClass.getResource("/push/apple-hpke-v2.json")!!.readText()).getJSONObject(0)
        val helperPrivate = Base64.getDecoder().decode(vector.getString("senderPrivateKeyBase64"))
        val receipt = c.session().use { it.finish(c.fixture.getJSONObject("response")); it.confirm(c.fixture.getJSONObject("ack"), c.state) }
        val requestID = "00000000-0000-4000-8000-000000000099"
        val challenge = ByteArray(32) { 42 }
        var allowed = true
        var now = c.now
        fun session(action: String = "renew", token: String? = "new-fixture-token") = PhoneHelperMaintenance(c.offer.getString("endpoint"),
            receipt.registrationID, receipt.generation, receipt.binding, c.phone, action, token, { allowed }, { now }, requestID)
        fun response(session: PhoneHelperMaintenance, change: (JSONObject) -> Unit = {}): JSONObject {
            val plaintext = JSONObject().put("version", 1).put("kind", "cmux-app.helper.registration").put("requestID", requestID)
                .put("requestDigest", session.requestDigest).put("challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(challenge))
                .put("expiresAt", c.now + 60_000).also(change)
            val encrypted = PhonePushCrypto.encrypt(plaintext.toString().toByteArray(), c.peer.tuple, c.phone.keyID,
                receipt.binding.peer.descriptor.keyID, Base64.getDecoder().decode(c.phone.descriptor().publicKey), helperPrivate)
            return JSONObject().put("requestID", requestID).put("envelope", encrypted.wire())
        }
        fun ack(session: PhoneHelperMaintenance, action: String = "renew"): JSONObject {
            val generation = "00000000-0000-4000-8000-000000000098"
            val id = receipt.registrationID.takeIf { action == "renew" }
            val next = generation.takeIf { action == "renew" }
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(challenge, "HmacSHA256")) }
            val proof = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(phoneEnrollmentFrame(
                "cmux-app.helper.registration.ack.v1", listOf(session.requestDigest, id, next))))
            return JSONObject().put("requestID", requestID).put("action", action).put("proof", proof)
                .put("registration", if (id == null) JSONObject.NULL else JSONObject().put("id", id).put("generation", next))
        }
    }
    @Test fun stableRequestAndVerifiedAckDoNotMutateStoredHelperOrNativePins() {
        val f = Fixture(); val before = f.c.state.toString()
        f.session().use { s ->
            s.begin().put("token", "modified")
            assertEquals("new-fixture-token", s.begin().getString("token"))
            assertTrue(runCatching { s.confirm(f.ack(s)) }.isFailure)
            s.finish(f.response(s)); val receipt = s.confirm(f.ack(s))
            assertEquals(f.receipt.registrationID, receipt.registrationID)
            assertNotEquals(f.receipt.generation, receipt.generation)
        }
        assertEquals(before, f.c.state.toString())
    }
    @Test fun savedChallengeCanRecoverLostAckAfterExpiryButCannotStartAnExpiredUncommittedProof() {
        val f = Fixture()
        val (saved, proof) = f.session().use { s ->
            val response = f.response(s); response to s.finish(response).getString("proof")
        }
        f.now += 61_000
        f.session().use { s ->
            assertTrue(runCatching { s.finish(saved) }.isFailure)
            assertEquals(proof, s.finish(saved, retry = true).getString("proof"))
            s.confirm(f.ack(s))
            f.now += 86_400_000
            assertTrue(runCatching { s.confirm(f.ack(s)) }.isFailure)
            assertTrue(runCatching { s.finish(saved, retry = true) }.isFailure)
        }
    }
    @Test fun alteredChallengeTranscriptOrTokenCannotAuthorizeMaintenance() {
        val f = Fixture()
        f.session().use { s ->
            for (mutate in listOf<(JSONObject) -> Unit>(
                { it.put("requestDigest", "wrong") }, { it.put("kind", "cmux-app.helper.enrollment") },
                { it.put("expiresAt", f.now + 120_001) }, { it.put("extra", true) }
            )) assertTrue(runCatching { s.finish(f.response(s, mutate)) }.isFailure)
            val response = f.response(s)
            f.session(token = "rotated-again").use { next -> assertTrue(runCatching { next.finish(response) }.isFailure) }
        }
    }
    @Test fun tamperedReceiptActionGenerationOrProofIsRejected() {
        val f = Fixture()
        f.session().use { s ->
            s.finish(f.response(s))
            for (mutate in listOf<(JSONObject) -> Unit>(
                { it.put("action", "revoke") }, { it.put("proof", "A".repeat(43)) },
                { it.getJSONObject("registration").put("generation", f.receipt.generation) },
                { it.getJSONObject("registration").put("id", "00000000-0000-4000-8000-000000000097") }
            )) assertTrue(runCatching { s.confirm(f.ack(s).also(mutate)) }.isFailure)
        }
    }
    @Test fun revokeHasNoTokenAndRequiresSignedNullRegistrationReceipt() {
        val f = Fixture()
        f.session("revoke", null).use { s ->
            assertTrue(s.begin().isNull("token")); s.finish(f.response(s))
            assertEquals(PhoneHelperMaintenanceReceipt(null, null), s.confirm(f.ack(s, "revoke")))
            assertTrue(runCatching { s.confirm(f.ack(s)) }.isFailure)
        }
        assertTrue(runCatching { f.session("revoke", "unexpected-token") }.isFailure)
    }
    @Test fun retirementAndClosePreventAnyFurtherProofOrReceiptAcceptance() {
        val f = Fixture(); val s = f.session(); val response = f.response(s)
        s.finish(response); f.allowed = false
        assertTrue(runCatching { s.begin() }.isFailure)
        assertTrue(runCatching { s.finish(response) }.isFailure)
        assertTrue(runCatching { s.confirm(f.ack(s)) }.isFailure)
        f.allowed = true; s.close()
        assertTrue(runCatching { s.begin() }.isFailure)
    }
}
