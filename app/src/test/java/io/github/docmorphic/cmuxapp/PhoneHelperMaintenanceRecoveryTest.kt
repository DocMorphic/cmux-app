package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PhoneHelperMaintenanceRecoveryTest {
    private class Fixture {
        val c = PhoneHelperEnrollmentTest.Context(JSONObject(javaClass.getResource("/push/helper-enrollment.json")!!.readText()))
        var now = c.now
        var account = c.state.toString()
        var ledger = JSONObject().toString()
        var token: PhoneFcmTokenSnapshot? = c.token
        var grant: PhoneFcmTokenGrant? = c.token.grant
        var failLedger = false
        var failAccount = false
        val helperPrivate = Base64.getDecoder().decode(JSONArray(javaClass.getResource("/push/apple-hpke-v2.json")!!.readText())
            .getJSONObject(0).getString("senderPrivateKeyBase64"))
        var remoteToken: String? = c.token.token
        var generation = c.fixture.getJSONObject("ack").getJSONObject("registration").getString("generation")
        val registration = c.fixture.getJSONObject("ack").getJSONObject("registration").getString("id")
        var commits = 0
        val received = mutableListOf<Pair<String, String>>()
        val challenges = mutableMapOf<String, Pair<JSONObject, ByteArray>>()
        val acks = mutableMapOf<String, JSONObject>()
        val digests = mutableMapOf<String, String>()
        init {
            val state = JSONObject(account)
            val pending = PhoneHelperEnrollmentState(state, { now }).prepare(c.offer.toString(), c.team, c.mac.origin, c.token,
                c.fixture.getJSONObject("begin").getString("requestID"))
            c.session().use { s -> s.finish(c.fixture.getJSONObject("response")); PhoneHelperEnrollmentState(state, { now })
                .confirm(pending, c.token, s, c.fixture.getJSONObject("ack")) }
            account = state.toString()
            val storage = JSONObject(); PhoneHelperMaintenanceQueue(storage, { now }).preserve(state); ledger = storage.toString()
        }
        fun rotate(value: String) { token = c.token.copy(grant = c.token.grant.copy(epoch = "grant-$value"), token = value, revision = "revision-$value"); grant = token!!.grant }
        fun access(write: Boolean, action: (PhoneHelperMaintenanceQueue) -> Unit) {
            val local = JSONObject(account); val storage = JSONObject(ledger)
            val queue = PhoneHelperMaintenanceQueue(storage, { now })
            queue.reconcile(local, grant, token); action(queue); queue.reconcile(local, grant, token)
            if (write) {
                check(!failLedger) { "Synthetic ledger write failure" }; ledger = storage.toString()
                check(!failAccount) { "Synthetic account write failure" }; account = local.toString()
            }
        }
        fun runner(send: suspend (String, () -> Boolean, String, JSONObject) -> PhoneHelperHttpResult = { _, permits, step, body ->
            if (permits()) serve(step, body) else PhoneHelperHttpResult.Retired
        }) = PhoneHelperMaintenanceRecovery({ access(true, it) }, { access(false, it) }, send, { now })
        fun receipt() = JSONObject(account).getJSONArray(PhoneHelperEnrollmentState.RECEIPTS).getJSONObject(0)
        private fun proof(key: ByteArray, domain: String, fields: List<String?>): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(phoneEnrollmentFrame(domain, fields)))
        }
        fun serve(step: String, body: JSONObject): PhoneHelperHttpResult {
            val id = body.getString("requestID"); received += step to id
            if (step == "maintain.begin") {
                if (body.getJSONObject("registration").getString("generation") != generation || remoteToken == null) return PhoneHelperHttpResult.Rejected(409)
                val recipient = body.getJSONObject("recipient"); val tuple = recipient.getJSONObject("tuple")
                val fields = listOf(c.offer.getString("endpoint"), id, body.getString("action"), registration, generation,
                    recipient.getString("installationID"), recipient.getString("keyID"), recipient.getString("senderKeyID")) +
                    listOf("accountID", "teamID", "iosBuildID", "iosInstallationID", "macDeviceID", "macInstanceTag", "macBuildID")
                        .map { if (tuple.isNull(it)) null else tuple.getString(it) } + (body.opt("token") as? String)
                val digest = MessageDigest.getInstance("SHA-256").digest(phoneEnrollmentFrame("cmux-app.helper.registration.request.v1", fields))
                    .joinToString("") { "%02x".format(it) }
                digests[id] = digest
                val challenge = challenges.getOrPut(id) { JSONObject(body.toString()) to ByteArray(32).also(java.security.SecureRandom()::nextBytes) }.second
                val plain = JSONObject().put("version", 1).put("kind", "cmux-app.helper.registration").put("requestID", id)
                    .put("requestDigest", digest).put("challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(challenge)).put("expiresAt", now + 120_000)
                val encrypted = PhonePushCrypto.encrypt(plain.toString().toByteArray(), c.peer.tuple, c.phone.keyID,
                    c.offer.getJSONObject("helper").getString("keyID"), Base64.getDecoder().decode(c.phone.descriptor().publicKey), helperPrivate)
                return PhoneHelperHttpResult.Success(JSONObject().put("requestID", id).put("envelope", encrypted.wire()))
            }
            val ack = acks[id]
            if (ack != null) return PhoneHelperHttpResult.Success(if (step == "maintain.abort") JSONObject().put("requestID", id).put("ack", ack) else ack)
            val challenge = challenges[id]
            if (challenge != null) assertEquals(proof(challenge.second, "cmux-app.helper.registration.finish.v1", listOf(digests[id])), body.getString("proof"))
            if (step == "maintain.abort") {
                challenges.remove(id)
                return PhoneHelperHttpResult.Success(JSONObject().put("requestID", id).put("ack", JSONObject.NULL))
            }
            if (challenge == null) return PhoneHelperHttpResult.Rejected(428)
            val request = challenge.first
            if (request.getJSONObject("registration").getString("generation") != generation) return PhoneHelperHttpResult.Rejected(409)
            val action = request.getString("action"); commits++
            val next = if (action == "renew") JSONObject().put("id", registration).put("generation", UUID.randomUUID().toString()) else null
            if (next != null) { generation = next.getString("generation"); remoteToken = request.getString("token") } else remoteToken = null
            val result = JSONObject().put("requestID", id).put("action", action).put("registration", next ?: JSONObject.NULL)
                .put("proof", proof(challenge.second, "cmux-app.helper.registration.ack.v1", listOf(digests[id], next?.getString("id"), next?.getString("generation"))))
            acks[id] = result; challenges.remove(id)
            return PhoneHelperHttpResult.Success(result)
        }
    }
    @Test fun tokenRotationAutomaticallyRenewsAndProjectsVerifiedGeneration() = runBlocking {
        val f = Fixture(); f.rotate("new-token")
        assertFalse(f.runner { _, permits, step, body ->
            assertTrue(permits())
            val queue = PhoneHelperMaintenanceQueue(JSONObject(f.ledger), { f.now })
            assertEquals(body.getString("requestID"), queue.pending().single().requestID)
            if (step == "maintain.finish") assertNotNull(queue.pending().single().response)
            f.serve(step, body)
        }.runPass())
        assertEquals("new-token", f.remoteToken); assertEquals(f.generation, f.receipt().getString("generation"))
        assertEquals(f.token!!.revision, f.receipt().getString("token_revision")); assertEquals(1, f.commits)
    }
    @Test fun lostAckRetriesExactFinishAfterCoordinatorRecreation() = runBlocking {
        val f = Fixture(); f.rotate("new-token")
        assertTrue(f.runner { _, _, step, body ->
            val result = f.serve(step, body)
            if (step == "maintain.finish") PhoneHelperHttpResult.Retry(f.now + 1000) else result
        }.runPass())
        assertTrue(f.runner { _, _, _, _ -> error("Backoff ignored") }.runPass())
        f.now += 1000; assertFalse(f.runner().runPass())
        assertEquals(1, f.commits)
        assertEquals(f.received[1], f.received[2]); assertEquals(f.generation, f.receipt().getString("generation"))
    }
    @Test fun newerTokenSettlesUncertainEarlierCommitBeforeUpdatingAgain() = runBlocking {
        val f = Fixture(); f.rotate("first-token")
        assertTrue(f.runner { _, _, step, body ->
            val result = f.serve(step, body)
            if (step == "maintain.finish") { f.rotate("latest-token"); PhoneHelperHttpResult.Retry(f.now) } else result
        }.runPass())
        assertTrue(f.runner().runPass()) // Abort retrieves the already committed receipt.
        assertEquals("maintain.abort", f.received.last().first)
        assertFalse(f.runner().runPass())
        assertEquals("latest-token", f.remoteToken); assertEquals(2, f.commits)
        assertEquals(f.generation, f.receipt().getString("generation"))
    }
    @Test fun optOutBeforeFinishCancelsRenewalAndRemovesOldRegistration() = runBlocking {
        val f = Fixture(); f.rotate("must-not-register")
        assertTrue(f.runner { _, permits, step, body ->
            if (step == "maintain.finish") { f.grant = null; f.token = null; assertFalse(permits()); PhoneHelperHttpResult.Retired }
            else f.serve(step, body)
        }.runPass())
        f.account = JSONObject().toString() // Account keys can disappear; cleanup uses its encrypted retained key.
        assertTrue(f.runner().runPass())
        assertEquals("maintain.abort", f.received.last().first); assertEquals(f.c.token.token, f.remoteToken)
        assertFalse(f.runner().runPass()); assertNull(f.remoteToken); assertEquals(1, f.commits)
        assertFalse(JSONObject(f.ledger).has(PhoneHelperMaintenanceQueue.KEY))
        assertFalse(JSONObject(f.account).has(PhonePushHelperState.KEY))
    }
    @Test fun logoutAfterLostCommittedAckRemovesTheNewGenerationWithoutRestoringTrust() = runBlocking {
        val f = Fixture(); f.rotate("new-token")
        assertTrue(f.runner { _, _, step, body ->
            val result = f.serve(step, body)
            if (step == "maintain.finish") { f.account = "{}"; f.grant = null; f.token = null; PhoneHelperHttpResult.Retry(f.now) } else result
        }.runPass())
        assertTrue(f.runner().runPass()); assertFalse(f.runner().runPass())
        assertNull(f.remoteToken); assertEquals(2, f.commits)
        assertEquals("{}", f.account); assertFalse(JSONObject(f.ledger).has(PhoneHelperMaintenanceQueue.KEY))
    }
    @Test fun failedLedgerWritePreservesUncertainProofAndRetryDoesNotApplyTwice() = runBlocking {
        val f = Fixture(); f.rotate("new-token")
        assertTrue(runCatching { f.runner { _, _, step, body ->
            val result = f.serve(step, body); if (step == "maintain.finish") f.failLedger = true; result
        }.runPass() }.isFailure)
        f.failLedger = false
        assertFalse(f.runner().runPass()); assertEquals(1, f.commits)
        assertEquals(f.generation, f.receipt().getString("generation"))
    }
    @Test fun savedLedgerRepairsAccountProjectionAfterSecondStoreWriteFails() = runBlocking {
        val f = Fixture(); f.rotate("new-token")
        assertTrue(runCatching { f.runner { _, _, step, body ->
            val result = f.serve(step, body); if (step == "maintain.finish") f.failAccount = true; result
        }.runPass() }.isFailure)
        assertNotEquals(f.generation, f.receipt().getString("generation")); f.failAccount = false
        assertFalse(f.runner { _, _, _, _ -> error("Committed receipt should only need local repair") }.runPass())
        assertEquals(f.generation, f.receipt().getString("generation")); assertEquals(1, f.commits)
    }
    @Test fun hostRestartResealsSameRequestAfter428WithoutInventingAGeneration() = runBlocking {
        val f = Fixture(); f.rotate("new-token"); var request: String? = null
        assertTrue(f.runner { _, _, step, body ->
            if (step == "maintain.begin") { request = body.getString("requestID"); f.serve(step, body) }
            else { f.challenges.clear(); PhoneHelperHttpResult.Rejected(428) }
        }.runPass())
        assertFalse(f.runner().runPass()); assertEquals(request, f.received.last().second); assertEquals(1, f.commits)
    }
    @Test fun tokenFetchGapKeepsPairedTrustWithoutSendingOrRevoking() = runBlocking {
        val f = Fixture(); f.token = null; f.grant = f.c.token.grant.copy(epoch = "new-fetch")
        assertFalse(f.runner { _, _, _, _ -> error("Token fetch gap should wait") }.runPass())
        assertTrue(JSONObject(f.account).has(PhonePushHelperState.KEY)); assertNotNull(f.remoteToken)
    }
    @Test fun newPhysicalPairingCannotBeRemovedByOldCleanup() = runBlocking {
        val f = Fixture(); val account = JSONObject(f.account); val receipt = account.getJSONArray(PhoneHelperEnrollmentState.RECEIPTS).getJSONObject(0)
        val old = receipt.getString("id"); val replacement = UUID.randomUUID().toString()
        f.generation = UUID.randomUUID().toString(); receipt.put("id", replacement).put("generation", f.generation); f.account = account.toString()
        assertFalse(f.runner().runPass())
        assertNotNull(f.remoteToken); assertEquals(f.generation, f.receipt().getString("generation"))
        val rows = JSONObject(f.ledger).getJSONArray(PhoneHelperMaintenanceQueue.KEY)
        assertEquals(1, rows.length()); assertNotEquals(old, rows.getJSONObject(0).getJSONObject("receipt").getString("id"))
    }
    @Test fun cleanupDeadlineErasesRetainedPrivateKeysAndExpiredActiveWorkNeedsRepair() = runBlocking {
        val f = Fixture(); f.grant = null; f.token = null; f.account = "{}"
        assertTrue(f.runner { _, _, _, _ -> PhoneHelperHttpResult.Retry(f.now + 1000) }.runPass())
        f.now += 86_400_000
        assertFalse(f.runner { _, _, _, _ -> error("Expired cleanup sent traffic") }.runPass())
        assertFalse(JSONObject(f.ledger).has(PhoneHelperMaintenanceQueue.KEY))
        val active = Fixture(); active.rotate("new-token")
        assertTrue(active.runner { _, _, _, _ -> PhoneHelperHttpResult.Retry(active.now + 1000) }.runPass())
        active.now += 86_400_000; assertFalse(active.runner().runPass())
        assertTrue(active.receipt().getBoolean("maintenance_error"))
    }
    @Test fun removalTimestampSurvivesDelayedRestartButFailedAccountSaveDoesNotRevoke() = runBlocking {
        val f = Fixture()
        fun stage() {
            val storage = JSONObject(f.ledger)
            PhoneHelperMaintenanceQueue(storage, { f.now }).stageRetirement(JSONObject())
            f.ledger = storage.toString()
        }
        stage() // Proposed account clear failed; the authoritative account still owns this helper.
        assertFalse(f.runner { _, _, _, _ -> error("Uncommitted removal must not revoke") }.runPass())
        assertTrue(JSONObject(f.account).has(PhonePushHelperState.KEY))
        stage(); f.account = "{}"; f.grant = null; f.token = null
        f.now += 86_400_001 // Restart is delayed beyond the original removal deadline.
        assertFalse(f.runner { _, _, _, _ -> error("Cleanup deadline was incorrectly renewed") }.runPass())
        assertFalse(JSONObject(f.ledger).has(PhoneHelperMaintenanceQueue.KEY))
    }
    @Test fun candidateReceiptWaitsForPendingAccountCommitThenCleansUpIfOfferExpires() = runBlocking {
        val f = Fixture(); val knownId = f.receipt().getString("id")
        val pendingAccount = JSONObject(f.c.state.toString())
        PhoneHelperEnrollmentState(pendingAccount, { f.now }).prepare(f.c.offer.toString(), f.c.team, f.c.mac.origin, f.c.token,
            f.c.fixture.getJSONObject("begin").getString("requestID"))
        pendingAccount.getJSONArray(PhoneHelperEnrollmentState.KEY).getJSONObject(0).put("id", knownId)
        f.account = pendingAccount.toString()
        assertFalse(f.runner { _, _, _, _ -> error("Initial account receipt is still recoverable") }.runPass())
        assertNotNull(f.remoteToken)
        f.now = f.c.offer.getLong("expiresAt")
        assertFalse(f.runner().runPass()); assertNull(f.remoteToken)
        assertFalse(JSONObject(f.account).has(PhonePushHelperState.KEY))
    }

}
