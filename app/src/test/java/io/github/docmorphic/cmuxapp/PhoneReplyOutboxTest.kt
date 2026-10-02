package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneReplyOutboxTest {
    private val now = 1_800_000_000_000L
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
        IrohV2Computer("record", "a".repeat(64), "directory", "stable", "Mac", emptyList()), team), "directory", "Mac", "stable"), team)
    private val remote = PhonePushIdentity("mac-installation", "mac-key", ByteArray(32) { (it + 32).toByte() })
    private fun state(): JSONObject {
        val state = JSONObject().put("task_session", team.login).put("refresh_token", "fixture")
            .put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
        val keys = PhonePushKeyState(state); val identity = keys.identity(team.login)
        keys.pin(team, mac.origin, PhonePushPeer(PhonePushTuple(team.userId, null, "android.fixture", identity.installationID,
            "physical-mac", "stable", "fixture.mac"), remote.descriptor()))
        return state
    }
    private fun reply(state: JSONObject, id: String = "reply", text: String = "literal reply λ 中", at: Long = now): PreparedPhoneReply {
        val keys = PhonePushKeyState(state)
        return PreparedPhoneReply.prepare(id, team, mac.origin, keys.peer(team, mac.origin)!!, keys.existingIdentity(team.login)!!,
            "workspace", "surface", false, text, at)
    }

    @Test fun reconstructionPreservesExactBodyAndAcceptedReceiptStopsDuplicateIntent() {
        val state = state(); val prepared = reply(state); val queue = PhoneReplyOutbox(state)
        assertEquals(ReplyEnqueueResult.QUEUED, queue.enqueue(prepared, now))
        val restoredState = JSONObject(state.toString()); val restored = PhoneReplyOutbox(restoredState)
        val pending = restored.pending(now + 1).single()
        assertEquals(prepared.body, pending.body)
        assertEquals(ReplyEnqueueResult.DUPLICATE, restored.enqueue(prepared, now + 1))
        assertEquals(ReplyEnqueueResult.CONFLICT, restored.enqueue(reply(state, text = "different"), now + 1))
        restored.finish(pending, PhoneReplyRelayResult.Accepted, now + 2000)
        assertTrue(restored.pending(now + 2000).isEmpty())
        assertEquals("accepted", restored.receipts(now + 2000).single().status)
        assertEquals(ReplyEnqueueResult.DUPLICATE, restored.enqueue(prepared, now + 2000))
        assertFalse(restoredState.getJSONObject(PhoneReplyOutbox.KEY).toString().contains(prepared.body))
        assertFalse(restoredState.toString().contains("literal reply"))
    }

    @Test fun strictRestoreRejectsCrossWiredMetadataAndKeepsLargeReplyBytes() {
        val prepared = reply(state(), text = "中".repeat(8192))
        assertEquals(prepared.body, PreparedPhoneReply.restore(JSONObject(prepared.persisted().toString())).body)
        for ((key, value) in listOf("reply_id" to "other", "sender_key_id" to "other", "created_at" to "123", "version" to 2))
            assertTrue(runCatching { PreparedPhoneReply.restore(prepared.persisted().put(key, value)) }.isFailure)
        for ((key, value) in listOf("macDeviceId" to "other", "macInstanceTag" to "other", "text" to "injected")) {
            val body = JSONObject(prepared.body).put(key, value)
            assertTrue(runCatching { PreparedPhoneReply.restore(prepared.persisted().put("body", body.toString())) }.isFailure)
        }
        assertTrue(runCatching { PreparedPhoneReply.restore(prepared.persisted().put("body", prepared.body + " garbage")) }.isFailure)
    }

    @Test fun fullQueueNeverEvictsAnIntentAndExpiredUnknownOutcomeLeavesReceipt() {
        val state = state(); val queue = PhoneReplyOutbox(state)
        val first = reply(state, "0")
        assertEquals(ReplyEnqueueResult.QUEUED, queue.enqueue(first, now))
        repeat(19) { assertEquals(ReplyEnqueueResult.QUEUED, queue.enqueue(reply(state, (it + 1).toString()), now)) }
        assertEquals(ReplyEnqueueResult.FULL, queue.enqueue(reply(state, "overflow"), now))
        assertEquals("0", queue.pending(now).first().replyID)
        queue.finish(first, PhoneReplyRelayResult.Retry(now + 5000), now)
        val restored = PhoneReplyOutbox(JSONObject(state.toString()))
        assertTrue(restored.pending(now + 4999).isEmpty())
        assertEquals(20, restored.pending(now + 5000).size)
        assertTrue(restored.pending(now + 120_000).isEmpty())
        assertEquals(20, restored.receipts(now + 120_000).size)
        assertTrue(restored.receipts(now + 120_000).all { it.status == "unconfirmed" })
        assertEquals(ReplyEnqueueResult.DUPLICATE, restored.enqueue(first, now + 120_000))
    }

    @Test fun retryDeadlineSurvivesRecreationAndReceiptExpiryButNeverSurvivesLoginChange() {
        val state = state(); val queue = PhoneReplyOutbox(state); val prepared = reply(state)
        val later = now + 8 * 24 * 60 * 60 * 1000L
        queue.enqueue(prepared, now); queue.finish(prepared, PhoneReplyRelayResult.Retry(later + 3_600_000), now)
        val restoredState = JSONObject(state.toString()); val restored = PhoneReplyOutbox(restoredState)
        assertTrue(restored.pending(later).isEmpty())
        assertTrue(restored.receipts(later).isEmpty())
        assertTrue(restoredState.has(PhoneReplyOutbox.KEY))
        assertEquals(ReplyEnqueueResult.QUEUED, restored.enqueue(reply(restoredState, "new", at = later), later))
        assertTrue(restored.pending(later).isEmpty())
        restoredState.put("task_session", "new-login"); PhonePushKeyState(restoredState).prune(); restored.prune(later)
        assertFalse(restoredState.has(PhoneReplyOutbox.KEY))
    }

    @Test fun delayedFirstExecutionRetainsOnlyContentFreeFailureForSevenDays() {
        val state = state(); val queue = PhoneReplyOutbox(state); val prepared = reply(state)
        queue.enqueue(prepared, now)
        val hourLater = now + 3_600_000
        assertTrue(queue.waiting(hourLater).isEmpty())
        val restored = PhoneReplyOutbox(JSONObject(state.toString()))
        assertEquals("unconfirmed", restored.receipts(hourLater).single().status)
        assertFalse(state.toString().contains(prepared.body))
        restored.finish(prepared, PhoneReplyRelayResult.Accepted, hourLater)
        assertEquals("unconfirmed", restored.receipts(hourLater).single().status)
        assertEquals(1, restored.receipts(now + 7 * 24 * 60 * 60 * 1000L - 1).size)
        assertTrue(restored.receipts(now + 7 * 24 * 60 * 60 * 1000L).isEmpty())
    }

    @Test fun forgetReaddCannotReviveWorkAndKeyRotationNeverReencryptsPendingReply() {
        val state = state(); val queue = PhoneReplyOutbox(state); val prepared = reply(state)
        queue.enqueue(prepared, now)
        state.put("pairings", JSONArray()); PhonePushKeyState(state).prune(); queue.prune(now)
        state.put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
        assertTrue(queue.pending(now).isEmpty()); assertTrue(queue.receipts(now).isEmpty())
        val rotatedState = state(); val rotatedQueue = PhoneReplyOutbox(rotatedState); val old = reply(rotatedState)
        rotatedQueue.enqueue(old, now)
        val keys = PhonePushKeyState(rotatedState); val previous = keys.peer(team, mac.origin)!!
        keys.pin(team, mac.origin, previous.copy(descriptor = PhonePushIdentity.generate().descriptor()))
        rotatedQueue.prune(now)
        assertTrue(rotatedQueue.pending(now).isEmpty())
        assertEquals("unconfirmed", rotatedQueue.receipts(now).single().status)
        assertEquals(ReplyEnqueueResult.RETIRED, rotatedQueue.enqueue(old, now))
    }

    @Test fun repairedPairingKeepsCiphertextAndLateAcceptanceCannotConsumeReplacementWork() {
        val state = state(); val queue = PhoneReplyOutbox(state); val prepared = reply(state)
        queue.enqueue(prepared, now)
        val repaired = mac.copy(stableOrigin = "b".repeat(64), previousOrigins = setOf(mac.origin))
        state.put("pairings", JSONArray().put(NativePairingRecords.encode(repaired))); PhonePushKeyState(state).prune(); queue.prune(now)
        assertEquals(prepared.body, queue.pending(now).single().body)
        val altered = PreparedPhoneReply.restore(prepared.persisted().put("created_at", now + 1))
        queue.finish(altered, PhoneReplyRelayResult.Accepted, now + 2)
        assertEquals(1, queue.pending(now + 2).size)
        queue.prune(now + 120_000)
        assertEquals("unconfirmed", queue.receipts(now + 120_000).single().status)
        queue.finish(altered, PhoneReplyRelayResult.Accepted, now + 121_000)
        assertEquals("unconfirmed", queue.receipts(now + 121_000).single().status)
        queue.finish(prepared, PhoneReplyRelayResult.Accepted, now + 121_000)
        assertEquals("accepted", queue.receipts(now + 121_000).single().status)
    }

    @Test fun drainPersistsEachOutcomeAndSkipsUnavailableOwnersWithoutLosingOtherReplies() = runBlocking {
        val state = state(); val queue = PhoneReplyOutbox(state)
        queue.enqueue(reply(state, "unavailable"), now); queue.enqueue(reply(state, "ready"), now)
        val sent = mutableListOf<String>()
        drainPhoneReplies({ queue.pending(now) }, { it.replyID != "unavailable" }, {
            sent += it.replyID; PhoneReplyRelayResult.Accepted
        }, { item, result -> queue.finish(item, result, now) })
        assertEquals(listOf("ready"), sent)
        assertEquals(listOf("unavailable"), queue.pending(now).map { it.replyID })
        var permitted = true
        drainPhoneReplies({ queue.pending(now) }, { permitted }, { permitted = false; PhoneReplyRelayResult.Accepted },
            { _, _ -> error("late result must not acknowledge retired runtime") })
        assertEquals(1, queue.pending(now).size)
    }
}
