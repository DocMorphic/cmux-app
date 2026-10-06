package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneHelperEnrollmentRecoveryTest {
    private class Harness {
        val fixture = JSONObject(javaClass.getResource("/push/helper-enrollment.json")!!.readText())
        val c = PhoneHelperEnrollmentTest.Context(fixture)
        var durable = c.state.toString()
        var token: PhoneFcmTokenSnapshot? = c.token
        var failSave = false
        fun transaction(action: (JSONObject, PhoneFcmTokenSnapshot?) -> Unit) {
            val detached = JSONObject(durable)
            action(detached, token)
            check(!failSave) { "Synthetic disk failure" }
            durable = detached.toString()
        }
        fun prepare(): PendingPhoneHelperEnrollment {
            var pending: PendingPhoneHelperEnrollment? = null
            transaction { state, _ -> pending = PhoneHelperEnrollmentState(state, { c.now }).prepare(c.offer.toString(), c.team,
                c.mac.origin, c.token, fixture.getJSONObject("begin").getString("requestID")) }
            return pending!!
        }
        fun state() = JSONObject(durable)
        fun records() = PhoneHelperEnrollmentState(state(), { c.now })
        fun runner(send: suspend (String, () -> Boolean, String, JSONObject) -> PhoneHelperHttpResult) = PhoneHelperEnrollmentRecovery(
            transaction = ::transaction, inspect = { action -> action(state(), token) }, send = send, now = { c.now })
        fun reply(step: String) = PhoneHelperHttpResult.Success(fixture.getJSONObject(if (step == "begin") "response" else "ack"))
        fun fields(value: JSONObject): Map<String, Any> = value.keys().asSequence().associateWith { key ->
            value.get(key).let { if (it is JSONObject) fields(it) else it }
        }
    }
    @Test fun lostBeginResponseReplaysSameRequestAfterRecreationAndCommitsReceiptWithPin() = runBlocking {
        val h = Harness(); h.prepare(); var sent: JSONObject? = null
        assertTrue(h.runner { _, permits, step, payload ->
            assertTrue(permits()); assertEquals("begin", step); sent = payload
            PhoneHelperHttpResult.Retry(h.c.now + 5000)
        }.runPass())
        assertFalse(h.state().has(PhonePushHelperState.KEY))
        // A completely new coordinator observes the persisted backoff without sending.
        assertTrue(h.runner { _, _, _, _ -> error("Backoff ignored") }.runPass())
        h.c.now += 5000
        assertFalse(h.runner { _, permits, step, payload ->
            assertTrue(permits())
            if (step == "begin") assertEquals(h.fields(sent!!), h.fields(payload))
            h.reply(step)
        }.runPass())
        val state = h.state()
        assertFalse(state.has(PhoneHelperEnrollmentState.KEY))
        assertNotNull(PhonePushHelperState(state).binding(h.c.team, h.c.mac.origin))
        val receipt = state.getJSONArray(PhoneHelperEnrollmentState.RECEIPTS).getJSONObject(0)
        assertEquals(h.fixture.getJSONObject("ack").getJSONObject("registration").getString("generation"), receipt.getString("generation"))
        assertFalse(receipt.has("offer")); assertFalse(receipt.has("response")); assertFalse(receipt.has("token"))
    }
    @Test fun lostFinishAckRestoresCiphertextAndReplaysIdenticalProofWithoutBeginningAgain() = runBlocking {
        val h = Harness(); h.prepare(); var finish: JSONObject? = null
        assertTrue(h.runner { _, _, step, payload ->
            if (step == "begin") h.reply(step) else {
                finish = payload
                assertNotNull(h.records().pending().single().response) // Committed before the write.
                PhoneHelperHttpResult.Retry(h.c.now + 1000)
            }
        }.runPass())
        h.c.now += 1000
        assertFalse(h.runner { _, permits, step, payload ->
            assertTrue(permits()); assertEquals("finish", step)
            assertEquals(h.fields(finish!!), h.fields(payload)); h.reply(step)
        }.runPass())
    }
    @Test fun replacementDuringFinishCannotInstallStaleReceiptOrRemoveNewAttempt() = runBlocking {
        val h = Harness(); val original = h.prepare(); var replacement: PendingPhoneHelperEnrollment? = null
        assertTrue(h.runner { _, permits, step, _ ->
            if (step == "finish") { replacement = h.prepare(); assertFalse(permits()) }
            h.reply(step)
        }.runPass())
        val newest = checkNotNull(replacement)
        assertNotEquals(original.id, newest.id)
        assertEquals(newest.id, h.records().pending().single().id)
        assertFalse(h.state().has(PhonePushHelperState.KEY))
    }
    @Test fun tokenRotationOptOutExpiryAndNativeRotationRetirePendingBeforeAnyTraffic() = runBlocking {
        for (change in 0..4) {
            val h = Harness(); h.prepare()
            when (change) {
                0 -> h.token = h.c.token.copy(revision = "new-token")
                1 -> h.token = null
                2 -> h.c.now = h.c.offer.getLong("expiresAt")
                3 -> h.transaction { state, _ -> PhonePushKeyState(state).pin(h.c.team, h.c.mac.origin,
                    h.c.peer.copy(descriptor = PhonePushIdentity.generate().descriptor())) }
                4 -> h.transaction { state, _ -> state.put("task_session", "other-login") }
            }
            assertFalse(h.runner { _, _, _, _ -> error("Retired enrollment sent traffic") }.runPass())
            assertFalse(h.state().has(PhoneHelperEnrollmentState.KEY))
        }
    }
    @Test fun tokenChangesDuringNetworkResponsePreventCheckpointAndFinish() = runBlocking {
        val h = Harness(); h.prepare(); var sends = 0
        assertFalse(h.runner { _, permits, step, _ ->
            sends++; h.token = h.c.token.copy(revision = "rotated")
            assertFalse(permits()); h.reply(step)
        }.runPass())
        assertEquals(1, sends); assertFalse(h.state().has(PhonePushHelperState.KEY))
    }
    @Test fun failedCheckpointNeverSendsFinishAndKeepsOriginalRequestForRetry() = runBlocking {
        val h = Harness(); val item = h.prepare(); var sends = 0
        val outcome = runCatching { h.runner { _, _, step, _ ->
            sends++; h.failSave = true; h.reply(step)
        }.runPass() }
        assertTrue(outcome.isFailure); assertEquals(1, sends)
        assertEquals(item.id, h.records().pending().single().id)
        assertNull(h.records().pending().single().response)
        assertFalse(h.state().has(PhonePushHelperState.KEY))
    }
    @Test fun failedReceiptCommitKeepsChallengeAndRetriesAckWithoutPartialTrust() = runBlocking {
        val h = Harness(); h.prepare()
        val outcome = runCatching { h.runner { _, _, step, _ ->
            if (step == "finish") h.failSave = true
            h.reply(step)
        }.runPass() }
        assertTrue(outcome.isFailure)
        assertNotNull(h.records().pending().single().response)
        assertFalse(h.state().has(PhonePushHelperState.KEY))
        assertFalse(h.state().has(PhoneHelperEnrollmentState.RECEIPTS))
        h.failSave = false
        assertFalse(h.runner { _, _, step, _ -> assertEquals("finish", step); h.reply(step) }.runPass())
        assertTrue(h.state().has(PhonePushHelperState.KEY))
        assertTrue(h.state().has(PhoneHelperEnrollmentState.RECEIPTS))
    }
    @Test fun tamperedAckLeavesNeitherPinNorReceiptAndRetiresOnlyItsAttempt() = runBlocking {
        val h = Harness(); h.prepare()
        assertFalse(h.runner { _, _, step, _ ->
            if (step == "begin") h.reply(step) else PhoneHelperHttpResult.Success(
                JSONObject(h.fixture.getJSONObject("ack").toString()).put("proof", "A".repeat(43)))
        }.runPass())
        assertFalse(h.state().has(PhonePushHelperState.KEY)); assertFalse(h.state().has(PhoneHelperEnrollmentState.RECEIPTS))
    }
    @Test fun completedReceiptIsPrunedWhenHelperIsForgottenAndFailedReplacementPreservesExistingPin() = runBlocking {
        val h = Harness(); h.prepare()
        assertFalse(h.runner { _, _, step, _ -> h.reply(step) }.runPass())
        val previous = PhonePushHelperState(h.state()).binding(h.c.team, h.c.mac.origin)
        h.prepare()
        assertFalse(h.runner { _, _, _, _ -> PhoneHelperHttpResult.Rejected(409) }.runPass())
        assertEquals(previous, PhonePushHelperState(h.state()).binding(h.c.team, h.c.mac.origin))
        assertTrue(h.state().has(PhoneHelperEnrollmentState.RECEIPTS))
        h.transaction { state, _ ->
            PhonePushHelperState(state).forget(h.c.team, h.c.mac.origin)
            PhoneHelperEnrollmentState(state, { h.c.now }).prune()
        }
        assertFalse(h.state().has(PhoneHelperEnrollmentState.RECEIPTS))
    }
}
