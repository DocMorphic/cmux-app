package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

class PhonePushMessageTest {
    private val now = 1_800_000_000_000L
    private val team = NativeTeamScope("fixture-login", "fixture-user", "fixture-team", 1)
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
        IrohV2Computer("record", "a".repeat(64), "directory-mac", "stable", "Fixture Mac", emptyList()), team),
        "directory-mac", "Fixture Mac", "stable"), team)
    private val phone = PhonePushIdentity("fixture-phone", "phone-key", ByteArray(32) { it.toByte() })
    private val remote = PhonePushIdentity("fixture-mac", "mac-key", ByteArray(32) { (it + 32).toByte() })
    private val peer = PhonePushPeer(PhonePushTuple(team.userId, null, "android.fixture", phone.installationID,
        "physical-mac", "stable", "fixture.mac"), remote.descriptor())
    private val vectors = JSONArray(javaClass.getResource("/push/apple-push-messages.json")!!.readText())
    private fun state(): JSONObject {
        val state = JSONObject().put("task_session", team.login).put("refresh_token", "fixture-refresh")
            .put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
        state.put(PhonePushKeyState.KEY, JSONObject().put("login", team.login).put("identity", phone.wire()))
        PhonePushKeyState(state).pin(team, mac.origin, peer)
        return state
    }
    private fun payload(index: Int = 0) = JSONObject(vectors.getJSONObject(index).getJSONObject("plaintext").toString())
    private fun raw(payload: JSONObject): String {
        val encrypted = PhonePushCrypto.encrypt(payload.toString().toByteArray(), peer.tuple, phone.keyID, remote.keyID,
            Base64.getDecoder().decode(phone.descriptor().publicKey), remote.privateKey)
        return JSONObject().put("encryptedPayloads", JSONArray().put(encrypted.wire())).toString()
    }
    private fun open(raw: String, state: JSONObject = state(), at: Long = now) =
        PhonePushMessage.open(raw, state, team, mac.origin, "android.fixture", at)
    private fun message(state: JSONObject, value: JSONObject = payload()) = open(raw(value), state)

    @Test fun unchangedMacEncoderAndAppleCryptoKitProduceAcceptedNotifyAndDismiss() {
        val notification = open(vectors.getJSONObject(0).getJSONObject("cmux").toString())
        assertEquals("Ready λ 中", notification.notification!!.title)
        assertEquals("Choose the next step", notification.notification.body)
        assertEquals("workspace", notification.notification.workspaceId)
        assertEquals("surface", notification.notification.surfaceId)
        assertTrue(notification.canReply); assertTrue(notification.hasNotificationID)
        assertEquals(1, notification.badgeCount)
        val dismiss = open(vectors.getJSONObject(1).getJSONObject("cmux").toString())
        assertNull(dismiss.notification); assertFalse(dismiss.canReply)
        assertEquals(listOf("notice"), dismiss.dismissedIDs)
    }

    @Test fun outerMetadataCannotChooseIdentityContentExpiryOrReplyCapability() {
        val value = JSONObject(raw(payload().put("replyShape", "none").put("category", "cmux.terminal")))
            .put("macDeviceId", "wrong").put("macPushPublicKey", "attacker").put("body", "injected")
            .put("expirationEpochSeconds", 0).put("replyShape", "text").put("category", "cmux.terminal.reply")
        val decoded = open(value.toString())
        assertEquals("Choose the next step", decoded.notification!!.body)
        assertFalse(decoded.canReply)
        for (field in listOf("macDeviceId", "macInstanceTag", "macBuildID", "macInstallationID", "macPushPublicKey"))
            assertTrue(field, runCatching { open(raw(payload().put(field, "wrong"))) }.isFailure)
        assertTrue(runCatching { open(payload().toString()) }.isFailure) // No plaintext downgrade.
    }

    @Test fun expiredMalformedAmbiguousOrUnpinnedMessagesNeverOpen() {
        val base = vectors.getJSONObject(0).getJSONObject("cmux").toString()
        assertTrue(runCatching { open(base, at = now + 120_000) }.isFailure)
        assertTrue(runCatching { open(base + " trailing") }.isFailure)
        val duplicate = JSONObject(base); duplicate.getJSONArray("encryptedPayloads").put(duplicate.getJSONArray("encryptedPayloads").get(0))
        assertTrue(runCatching { open(duplicate.toString()) }.isFailure)
        for ((field, value) in listOf("expirationEpochSeconds" to "1800000120", "expirationEpochSeconds" to -1,
            "badgeCount" to -1, "hideContent" to "false", "title" to "x".repeat(121), "body" to "x".repeat(501),
            "surfaceId" to "x".repeat(201), "retargetsToLiveSurfaceOwner" to 1, "correlationId" to "1-1-1-1-1", "kind" to "other"))
            assertTrue(field, runCatching { open(raw(payload().put(field, value))) }.isFailure)
        val absent = state().apply { remove(PhonePushKeyState.KEY) }
        assertTrue(runCatching { open(base, absent) }.isFailure)
        val changed = state().put("task_session", "replacement")
        assertTrue(runCatching { open(base, changed) }.isFailure)
        assertTrue(runCatching { PhonePushMessage.open(base, state(), team, mac.origin, "wrong.build", now) }.isFailure)
    }

    @Test fun redactionReplyConfinementAndLegacyMissingNotificationIdAreExplicit() {
        val hidden = open(raw(payload().put("hideContent", true)))
        assertEquals("cmux", hidden.notification!!.title); assertEquals("", hidden.notification.subtitle)
        assertEquals("New terminal activity", hidden.notification.body)
        val incomplete = payload().apply { remove("workspaceId") }
        assertFalse(open(raw(incomplete)).canReply)
        incomplete.put("retargetsToLiveSurfaceOwner", true)
        assertTrue(open(raw(incomplete)).canReply)
        incomplete.remove("surfaceId")
        assertFalse(open(raw(incomplete)).canReply)
        val legacy = open(raw(payload().apply { remove("notificationId") }))
        assertFalse(legacy.hasNotificationID)
        assertEquals("push.${legacy.correlationID}", legacy.notification!!.id)
    }

    @Test fun replayAndDismissBeforeNotifySurviveReconstructionWithoutContent() {
        val state = state(); val inbox = PhonePushInbox(state)
        val dismiss = message(state, payload(1))
        assertEquals(PhonePushAdmission.ACCEPTED, inbox.admit(dismiss, now))
        val restored = PhonePushInbox(JSONObject(state.toString()))
        assertEquals(PhonePushAdmission.DUPLICATE, restored.admit(dismiss, now))
        assertEquals(PhonePushAdmission.DISMISSED, restored.admit(message(state), now))
        assertFalse(state.getJSONObject(PhonePushInbox.KEY).toString().contains("Choose the next step"))
        assertEquals(PhonePushAdmission.EXPIRED, restored.admit(message(state), now + 120_000))
    }

    @Test fun fullReplayAndDismissCachesDoNotEvictUnexpiredProtection() {
        val state = state(); val inbox = PhonePushInbox(state)
        val original = message(state); assertEquals(PhonePushAdmission.ACCEPTED, inbox.admit(original, now))
        val events = state.getJSONObject(PhonePushInbox.KEY).getJSONArray("events")
        val row = events.getJSONObject(0)
        repeat(PhonePushInbox.CAPACITY - 1) { events.put(JSONObject(row.toString()).put("id", UUID.randomUUID().toString())) }
        assertEquals(PhonePushAdmission.FULL, inbox.admit(message(state, payload().put("correlationId", UUID.randomUUID().toString())), now))
        assertEquals(PhonePushAdmission.DUPLICATE, inbox.admit(original, now))
        val other = state(); val full = PhonePushInbox(other)
        // Populate durable records directly; this tests cache saturation, not a giant push packet.
        val dismissed = JSONArray((0 until PhonePushInbox.CAPACITY).map { index ->
            JSONObject(row.toString()).put("id", "notice-$index")
        })
        other.put(PhonePushInbox.KEY, JSONObject().put("login", team.login).put("events", JSONArray()).put("dismissed", dismissed))
        assertEquals(PhonePushAdmission.FULL, full.admit(message(other, payload(1)), now))
        assertEquals(PhonePushInbox.CAPACITY, other.getJSONObject(PhonePushInbox.KEY).getJSONArray("dismissed").length())
    }

    @Test fun rotationForgetAndLoginChangesCannotRebindOpenedMessagesOrOldReplayState() {
        val state = state(); val old = message(state); val inbox = PhonePushInbox(state)
        inbox.admit(old, now)
        PhonePushKeyState(state).pin(team, mac.origin, peer.copy(descriptor = PhonePushIdentity.generate().descriptor()))
        assertEquals(PhonePushAdmission.RETIRED, inbox.admit(old, now))
        state.put("pairings", JSONArray()); inbox.prune(now)
        assertFalse(state.has(PhonePushInbox.KEY))
        val replacement = state(); val opened = message(replacement); PhonePushInbox(replacement).admit(opened, now)
        replacement.put("task_session", "replacement"); PhonePushInbox(replacement).prune(now)
        assertFalse(replacement.has(PhonePushInbox.KEY)); assertFalse(opened.permits(replacement))
    }
}
