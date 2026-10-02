package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneFcmQueueTest {
    private val now = 1_800_000_000_000L
    private val team = NativeTeamScope("fixture-login", "fixture-user", "fixture-team", 1)
    private val phone = PhonePushIdentity("fixture-phone", "phone-key", ByteArray(32) { it.toByte() })
    private val remote = PhonePushIdentity("fixture-mac", "mac-key", ByteArray(32) { (it + 32).toByte() })
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
        IrohV2Computer("record", "a".repeat(64), "directory-mac", "stable", "Fixture Mac", emptyList()), team),
        "directory-mac", "Fixture Mac", "stable"), team)
    private val raw = JSONArray(javaClass.getResource("/push/apple-push-messages.json")!!.readText())
        .getJSONObject(0).getJSONObject("cmux").toString()
    private fun state() = JSONObject().put("task_session", team.login).put("refresh_token", "fixture-refresh")
        .put("pairings", JSONArray().put(NativePairingRecords.encode(mac))).also { state ->
            state.put(PhonePushKeyState.KEY, JSONObject().put("login", team.login).put("identity", phone.wire()))
            PhonePushKeyState(state).pin(team, mac.origin, PhonePushPeer(PhonePushTuple(team.userId, null,
                "android.fixture", phone.installationID, "physical-mac", "stable", "fixture.mac"), remote.descriptor()))
        }
    private fun membership() = NativeAccountTeamsState(userId = team.userId,
        teams = listOf(NativeTeam(team.teamId, "Fixture")), selectedTeamId = team.teamId, scope = team)

    @Test fun ciphertextSurvivesReconstructionWithoutRedeliveryExtendingItsLife() {
        val state = state(); val queue = PhoneFcmQueue(state)
        assertTrue(queue.enqueue(raw, now)); val first = queue.waiting(now).single()
        val restored = PhoneFcmQueue(JSONObject(state.toString()))
        assertEquals(first, restored.waiting(now).single())
        assertTrue(restored.enqueue(raw, now + 1000))
        assertEquals(first, restored.waiting(now + 1000).single())
        assertTrue(restored.waiting(first.expires).isEmpty())
        assertFalse(state.toString().contains("Choose the next step"))
    }
    @Test fun enqueueDecisionDistinguishesNewDuplicateAndRejectedWithoutChangingRetention() {
        val queue = PhoneFcmQueue(state())
        assertEquals(PhoneFcmQueue.EnqueueResult.NEW, queue.offer(raw, now))
        val original = queue.waiting(now).single()
        assertEquals(PhoneFcmQueue.EnqueueResult.DUPLICATE, queue.offer(raw, now + 1000))
        assertEquals(original, queue.waiting(now + 1000).single())
        assertEquals(PhoneFcmQueue.EnqueueResult.REJECTED, queue.offer("{}", now))
        assertEquals(PhoneFcmQueue.EnqueueResult.NEW, queue.offer(raw, original.expires))
        assertEquals(original.expires + PhoneFcmQueue.LIFETIME, queue.waiting(original.expires).single().expires)
    }
    @Test fun prioritySurvivesLaterNormalMessagesAndDuplicateUpgradeDoesNotRenewExpiry() {
        val state = state(); val queue = PhoneFcmQueue(state)
        assertEquals(PhoneFcmQueue.EnqueueResult.NEW, queue.offer(raw, now))
        val first = queue.waiting(now).single()
        assertEquals(PhoneFcmQueue.EnqueueResult.PRIORITY_UPGRADE, queue.offer(raw, now + 1000, true))
        assertEquals(first.copy(highPriority = true), queue.waiting(now + 1000).single())
        assertEquals(PhoneFcmQueue.EnqueueResult.DUPLICATE, queue.offer(raw, now + 2000, true))
        assertEquals(PhoneFcmQueue.EnqueueResult.DUPLICATE, queue.offer(raw, now + 2000, false))
        assertEquals(PhoneFcmQueue.EnqueueResult.NEW, queue.offer(raw + " ", now + 2000, false))
        val restored = PhoneFcmQueue(JSONObject(state.toString())).waiting(now + 2000)
        assertEquals(listOf(true, false), restored.map { it.highPriority })
        assertEquals(first.expires, restored.first().expires)
        assertFalse(PhoneFcmQueue(state).waiting(first.expires).any { it.highPriority })
        state.optJSONArray(PhoneFcmQueue.KEY)!!.getJSONObject(1).remove("high_priority")
        assertFalse(PhoneFcmQueue(state).waiting(first.expires).single().highPriority)
    }
    @Test fun loginReplacementAndSignOutDiscardQueuedCiphertext() {
        val state = state(); val queue = PhoneFcmQueue(state); queue.enqueue(raw, now)
        state.put("task_session", "replacement"); queue.prune(now)
        assertFalse(state.has(PhoneFcmQueue.KEY))
        assertTrue(queue.enqueue(raw, now)); state.remove("refresh_token"); queue.prune(now)
        assertFalse(queue.enqueue(raw, now)); assertFalse(state.has(PhoneFcmQueue.KEY))
    }
    @Test fun malformedPlaintextAndOversizedProviderDataAreRejected() {
        assertTrue(PhoneFcmQueue.valid(raw))
        for (bad in listOf("{}", "{\"title\":\"Untrusted\"}", raw + " trailing", " ".repeat(4097) + raw,
            JSONObject(raw).put("body", "plaintext").toString(), "{\"encryptedPayloads\":[]}")) {
            assertFalse(PhoneFcmQueue.valid(bad)); assertFalse(PhoneFcmQueue(state()).enqueue(bad, now))
        }
    }
    @Test fun capacityIsBoundedAndRemovingAnOldLoginItemCannotRemoveANewOne() {
        val state = state(); val queue = PhoneFcmQueue(state)
        repeat(PhoneFcmQueue.CAPACITY) { assertTrue(queue.enqueue(raw + " ".repeat(it), now)) }
        assertFalse(queue.enqueue(raw + " ".repeat(PhoneFcmQueue.CAPACITY), now))
        val old = queue.waiting(now).first(); state.put("task_session", "replacement")
        assertTrue(queue.enqueue(raw, now)); queue.remove(old, now)
        assertEquals("replacement", queue.waiting(now).single().login)
    }
    @Test fun pinnedAppleEnvelopeOpensOnlyForFreshAdmittedMembershipAndSavedMac() {
        val state = state(); val queue = PhoneFcmQueue(state); queue.enqueue(raw, now)
        val item = queue.waiting(now).single(); val membership = membership()
        val value = openQueuedPhonePush(item, state, membership, "android.fixture", now)!!
        assertEquals(mac.origin, value.origin); assertEquals("Choose the next step", value.notification!!.body)
        for (untrusted in listOf(membership.copy(scope = null), membership.copy(cached = true),
            membership.copy(error = "offline"), membership.copy(teams = emptyList()),
            membership.copy(scope = team.copy(login = "other"))))
            assertNull(openQueuedPhonePush(item, state, untrusted, "android.fixture", now))
        assertNull(openQueuedPhonePush(item, state, membership, "wrong.build", now))
        assertNull(openQueuedPhonePush(item, state, membership, "android.fixture", item.expires))
        state.put("pairings", JSONArray())
        assertNull(openQueuedPhonePush(item, state, membership, "android.fixture", now))
    }
    @Test fun selectedTeamDoesNotAuthorizeOrDenyAnotherTeamWithoutMembership() {
        val state = state(); val queue = PhoneFcmQueue(state); queue.enqueue(raw, now)
        val item = queue.waiting(now).single()
        val membership = membership().copy(scope = team.copy(teamId = "other-team"), selectedTeamId = "other-team")
        assertNotNull(openQueuedPhonePush(item, state, membership, "android.fixture", now))
        assertNull(openQueuedPhonePush(item, state, membership.copy(teams = listOf(NativeTeam("other-team", "Other"))), "android.fixture", now))
    }
}
