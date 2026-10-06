package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PhoneHelperEnrollmentTest {
    private fun fields(value: JSONObject): Map<String, Any> = value.keys().asSequence().associateWith { name ->
        value.get(name).let { if (it is JSONObject) fields(it) else it }
    }
    private fun fixture() = JSONObject(javaClass.getResource("/push/helper-enrollment.json")!!.readText())
    internal class Context(val fixture: JSONObject) {
        val offer = fixture.getJSONObject("offer")
        val macFields = offer.getJSONObject("mac")
        val team = NativeTeamScope("fixture-login", macFields.getString("accountID"), macFields.getString("teamID"), 1)
        val phoneDescriptor = PhonePushDescriptor.parse(fixture.getJSONObject("begin").getJSONObject("phone"))
        val phone = PhonePushIdentity(phoneDescriptor.installationID, phoneDescriptor.keyID, Base64.getDecoder().decode(fixture.getString("phonePrivateKey")))
        val peer = PhonePushPeer(PhonePushTuple(team.userId, team.teamId, offer.getString("phoneBuildID"), phone.installationID,
            macFields.getString("macDeviceID"), macFields.getString("macInstanceTag"), macFields.getString("macBuildID")), PhonePushDescriptor.parse(offer.getJSONObject("native")))
        val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
            IrohV2Computer("record", "a".repeat(64), "directory-mac", macFields.getString("macInstanceTag"), "Fixture Mac", emptyList()), team),
            "directory-mac", "Fixture Mac", macFields.getString("macInstanceTag")), team)
        val state = JSONObject().put("task_session", team.login).put("refresh_token", "fixture-refresh")
            .put("pairings", JSONArray().put(NativePairingRecords.encode(mac))).also {
                it.put(PhonePushKeyState.KEY, JSONObject().put("login", team.login).put("identity", phone.wire()))
                PhonePushKeyState(it).pin(team, mac.origin, peer)
            }
        val nativeEpoch = PhonePushKeyState(state).peerEpoch(team, mac.origin)!!
        val token = PhoneFcmTokenSnapshot(PhoneFcmTokenGrant(team.login, PhoneFcmProject.parse(offer.getJSONObject("project")), "fixture-consent"),
            fixture.getJSONObject("begin").getString("token"), "fixture-token-version")
        var current = true
        var now = fixture.getLong("now")
        fun session(offer: JSONObject = this.offer, token: PhoneFcmTokenSnapshot = this.token) = PhoneHelperEnrollment(offer.toString(), team,
            mac.origin, peer, nativeEpoch, phone, token, { current }, { now }, fixture.getJSONObject("begin").getString("requestID"))
    }
    @Test fun kotlinAndNodeAgreeOnProofsAndRealCryptoKitChallengeBeforeCommittingHelperTrust() {
        val c = Context(fixture())
        c.session().use { session ->
            assertEquals(fields(c.fixture.getJSONObject("begin")), fields(session.begin()))
            assertFalse(c.state.has(PhonePushHelperState.KEY))
            assertEquals(fields(c.fixture.getJSONObject("finish")), fields(session.finish(c.fixture.getJSONObject("response"))))
            assertFalse(c.state.has(PhonePushHelperState.KEY))
            val receipt = session.confirm(c.fixture.getJSONObject("ack"), c.state)
            assertEquals(c.fixture.getJSONObject("ack").getJSONObject("registration").getString("id"), receipt.registrationID)
            assertEquals(PhonePushDescriptor.parse(c.offer.getJSONObject("helper")), receipt.binding.peer.descriptor)
            assertEquals(c.peer, PhonePushKeyState(c.state).peer(c.team, c.mac.origin))
            assertEquals(receipt, session.confirm(c.fixture.getJSONObject("ack"), c.state))
        }
    }
    @Test fun wrongProjectMacOrUnsafeEndpointNeverStartsEnrollment() {
        val c = Context(fixture())
        assertTrue(runCatching { c.session(token = c.token.copy(grant = c.token.grant.copy(project = c.token.grant.project.copy(project = "other")))) }.isFailure)
        for (endpoint in listOf("http://helper.invalid/v1/push/enroll", "https://helper.invalid/v1/push/enroll?token=x", "https://user:pass@helper.invalid/v1/push/enroll"))
            assertTrue(runCatching { c.session(JSONObject(c.offer.toString()).put("endpoint", endpoint)) }.isFailure)
        val wrong = JSONObject(c.offer.toString()); wrong.getJSONObject("mac").put("macDeviceID", "other")
        assertTrue(runCatching { c.session(wrong) }.isFailure); assertFalse(c.state.has(PhonePushHelperState.KEY))
    }
    @Test fun changedTokenCannotReuseAnOldChallengeAndTamperedCiphertextFails() {
        val c = Context(fixture())
        c.session(token = c.token.copy(token = "replacement")).use { session ->
            assertTrue(runCatching { session.finish(c.fixture.getJSONObject("response")) }.isFailure)
        }
        val response = JSONObject(c.fixture.getJSONObject("response").toString())
        response.getJSONObject("envelope").put("ciphertext", Base64.getEncoder().encodeToString(ByteArray(64)))
        c.session().use { session -> assertTrue(runCatching { session.finish(response) }.isFailure) }
        assertFalse(c.state.has(PhonePushHelperState.KEY))
    }
    @Test fun unprovenOrTamperedAcknowledgmentCannotPinHelper() {
        val c = Context(fixture())
        c.session().use { session ->
            assertTrue(runCatching { session.confirm(c.fixture.getJSONObject("ack"), c.state) }.isFailure)
            session.finish(c.fixture.getJSONObject("response"))
            val changed = JSONObject(c.fixture.getJSONObject("ack").toString())
            changed.getJSONObject("registration").put("generation", "00000000-0000-4000-8000-000000000099")
            assertTrue(runCatching { session.confirm(changed, c.state) }.isFailure)
            assertFalse(c.state.has(PhonePushHelperState.KEY))
        }
    }
    @Test fun expiryOptOutOrNativeRotationAfterProofPreventLocalCommit() {
        val c = Context(fixture())
        c.session().use { session ->
            session.finish(c.fixture.getJSONObject("response")); c.current = false
            assertTrue(runCatching { session.confirm(c.fixture.getJSONObject("ack"), c.state) }.isFailure)
            c.current = true; c.now = c.offer.getLong("expiresAt")
            assertTrue(runCatching { session.begin() }.isFailure)
            c.now = c.fixture.getLong("now")
            PhonePushKeyState(c.state).pin(c.team, c.mac.origin, c.peer.copy(descriptor = PhonePushIdentity.generate().descriptor()))
            assertTrue(runCatching { session.confirm(c.fixture.getJSONObject("ack"), c.state) }.isFailure)
            assertFalse(c.state.has(PhonePushHelperState.KEY))
        }
    }
    @Test fun returnedRequestsCannotMutateSessionAndCloseRetiresProofs() {
        val c = Context(fixture()); val session = c.session()
        session.begin().put("token", "modified")
        assertEquals(c.token.token, session.begin().getString("token"))
        session.close(); assertTrue(runCatching { session.begin() }.isFailure)
        assertTrue(runCatching { session.finish(c.fixture.getJSONObject("response")) }.isFailure)
    }
    @Test fun realKotlinClientEnrollsThroughNodeTlsAndCryptoKitWithIdempotentFinish() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").orEmpty().contains("Mac"))
        val root = generateSequence(java.io.File(".").canonicalFile) { it.parentFile }
            .first { java.io.File(it, "scripts/push-enrollment-fixture-server.mjs").isFile }
        org.junit.Assume.assumeTrue(java.io.File(root, "build/push/cmux-push-seal").isFile)
        val directory = java.nio.file.Files.createTempDirectory("cmux-enrollment-tls").toFile()
        val certificate = okhttp3.tls.HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        java.io.File(directory, "cert.pem").writeText(certificate.certificatePem())
        java.io.File(directory, "key.pem").writeText(certificate.privateKeyPkcs8Pem())
        val errors = java.io.File(directory, "stderr.txt")
        val process = ProcessBuilder("node", java.io.File(root, "scripts/push-enrollment-fixture-server.mjs").path, directory.path)
            .redirectError(errors).apply { environment().keys.retainAll(setOf("PATH")) }.start()
        val trust = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val base = okhttp3.OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        try {
            val line = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }
                .get(15, java.util.concurrent.TimeUnit.SECONDS)
            checkNotNull(line) { "Fixture server failed: ${errors.readText().take(1000)}" }
            val offer = JSONObject(line); val c = Context(fixture())
            c.session(offer).use { session -> PhoneHelperHttp(session.endpoint, { c.current }, base, { c.now }).use { http ->
                val challenge = (http.send("begin", session.begin()) as PhoneHelperHttpResult.Success).body
                val proof = session.finish(challenge)
                val ack = (http.send("finish", proof) as PhoneHelperHttpResult.Success).body
                val receipt = session.confirm(ack, c.state)
                val repeated = (http.send("finish", proof) as PhoneHelperHttpResult.Success).body
                assertEquals(receipt, session.confirm(repeated, c.state))
                assertEquals(c.peer, receipt.binding.macPeer)
                assertEquals(PhonePushDescriptor.parse(offer.getJSONObject("helper")), receipt.binding.peer.descriptor)
                PhoneHelperMaintenance(session.endpoint, receipt.registrationID, receipt.generation, receipt.binding, c.phone,
                    "renew", "replacement-fixture-token", { c.current }, { c.now }).use { renewal ->
                    val renewalChallenge = (http.send("maintain.begin", renewal.begin()) as PhoneHelperHttpResult.Success).body
                    val renewalProof = renewal.finish(renewalChallenge)
                    val renewalAck = (http.send("maintain.finish", renewalProof) as PhoneHelperHttpResult.Success).body
                    val renewed = renewal.confirm(renewalAck)
                    val retryAck = (http.send("maintain.finish", renewalProof) as PhoneHelperHttpResult.Success).body
                    assertEquals(renewed, renewal.confirm(retryAck))
                    assertNotEquals(receipt.generation, renewed.generation)
                    PhoneHelperMaintenance(session.endpoint, checkNotNull(renewed.registrationID), checkNotNull(renewed.generation), receipt.binding,
                        c.phone, "revoke", null, { c.current }, { c.now }).use { removal ->
                        val removalChallenge = (http.send("maintain.begin", removal.begin()) as PhoneHelperHttpResult.Success).body
                        val removalProof = removal.finish(removalChallenge)
                        val removalAck = (http.send("maintain.finish", removalProof) as PhoneHelperHttpResult.Success).body
                        assertEquals(PhoneHelperMaintenanceReceipt(null, null), removal.confirm(removalAck))
                        assertEquals(removal.confirm(removalAck), removal.confirm((http.send("maintain.finish", removalProof) as PhoneHelperHttpResult.Success).body))
                    }
                }
            } }
        } finally {
            process.destroy()
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
            base.connectionPool.evictAll(); base.dispatcher.executorService.shutdown(); directory.deleteRecursively()
        }
    }

}
