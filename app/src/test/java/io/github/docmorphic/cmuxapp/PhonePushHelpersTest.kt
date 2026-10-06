package io.github.docmorphic.cmuxapp

import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

class PhonePushHelpersTest {
    private val now = 1_800_000_000_000L
    private val team = NativeTeamScope("fixture-login", "fixture-user", "fixture-team", 1)
    private val project = PhoneFcmProject("fixture-project", "fixture-application", "123456")
    private val phone = PhonePushIdentity("fixture-phone", "phone-key", ByteArray(32) { it.toByte() })
    private val remote = PhonePushIdentity("fixture-mac", "mac-key", ByteArray(32) { (it + 32).toByte() })
    private val helper = PhonePushIdentity("fixture-helper", "helper-key", ByteArray(32) { (it + 64).toByte() })
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
        IrohV2Computer("record", "a".repeat(64), "directory-mac", "stable", "Fixture Mac", emptyList()), team),
        "directory-mac", "Fixture Mac", "stable"), team)
    private val peer = PhonePushPeer(PhonePushTuple(team.userId, null, "android.fixture", phone.installationID,
        "physical-mac", "stable", "fixture.mac"), remote.descriptor())
    private val vectors = JSONArray(javaClass.getResource("/push/apple-push-messages.json")!!.readText())
    private fun state() = JSONObject().put("task_session", team.login).put("refresh_token", "fixture-refresh")
        .put("pairings", JSONArray().put(NativePairingRecords.encode(mac))).also { state ->
            state.put(PhonePushKeyState.KEY, JSONObject().put("login", team.login).put("identity", phone.wire()))
            PhonePushKeyState(state).pin(team, mac.origin, peer)
        }
    private fun pin(state: JSONObject, sender: PhonePushIdentity = helper) = PhonePushHelperState(state).pin(team,
        mac.origin, peer, PhonePushKeyState(state).peerEpoch(team, mac.origin)!!, sender.descriptor(), project)
    private fun raw(sender: PhonePushIdentity = helper, kind: Int = 0, correlation: String? = null): String {
        val payload = JSONObject(vectors.getJSONObject(kind).getJSONObject("plaintext").toString())
            .put("macInstallationID", sender.installationID).put("macPushPublicKey", sender.descriptor().publicKey)
        if (correlation != null) payload.put("correlationId", correlation)
        val encrypted = PhonePushCrypto.encrypt(payload.toString().toByteArray(), peer.tuple, phone.keyID, sender.keyID,
            Base64.getDecoder().decode(phone.descriptor().publicKey), sender.privateKey)
        return JSONObject().put("encryptedPayloads", JSONArray().put(encrypted.wire())).toString()
    }
    private fun open(state: JSONObject, raw: String = raw()) = PhonePushMessage.open(raw, state, team, mac.origin, "android.fixture", now)
    private fun route(message: PhonePushMessage) = message.notification!!.let {
        NotificationDestination(UUID.randomUUID().toString(), mac.origin, it.id, it.workspaceId, it.surfaceId,
            it.retargetsToLiveSurfaceOwner, team.login)
    }

    @Test fun helperEnrollmentKeepsNativePinAndIsIdempotentAcrossRestore() {
        val state = state(); val keys = PhonePushKeyState(state); val epoch = keys.peerEpoch(team, mac.origin)
        val enrolled = pin(state)
        assertEquals(peer, keys.peer(team, mac.origin)); assertEquals(epoch, keys.peerEpoch(team, mac.origin))
        assertEquals(enrolled, pin(state))
        assertEquals(enrolled, PhonePushHelperState(JSONObject(state.toString())).binding(team, mac.origin))
        assertEquals(peer, enrolled.macPeer); assertEquals(peer.tuple, enrolled.peer.tuple)
    }
    @Test fun unpinnedOrUnknownSendersCannotUseNativeMacIdentity() {
        val state = state(); assertTrue(runCatching { open(state) }.isFailure)
        pin(state)
        val unknown = PhonePushIdentity.generate()
        assertTrue(runCatching { open(state, raw(unknown)) }.isFailure)
        val spoofed = JSONObject(raw()).also { it.getJSONArray("encryptedPayloads").getJSONObject(0).put("senderKeyID", remote.keyID) }
        assertTrue(runCatching { open(state, spoofed.toString()) }.isFailure)
        assertThrows(IllegalArgumentException::class.java) { pin(state, remote) }
    }
    @Test fun helperNotifyAndDismissUseExistingAdmissionAndOfficialEventsStillOpen() {
        val state = state(); val binding = pin(state); val message = open(state)
        assertEquals(binding.peer, message.peer); assertEquals(peer, message.replyPeer); assertEquals(binding.epoch, message.helperEpoch)
        assertTrue(message.canReply); assertEquals("Choose the next step", message.notification!!.body)
        val official = open(state, raw(remote)); assertEquals(peer, official.peer); assertNull(official.helperEpoch)
        assertEquals(PhonePushAdmission.ACCEPTED, PhonePushInbox(state).admit(message, now))
        assertEquals(PhonePushAdmission.DUPLICATE, PhonePushInbox(state).admit(official, now))
        val dismiss = open(state, raw(kind = 1)); assertEquals(listOf("notice"), dismiss.dismissedIDs)
        assertEquals(PhonePushAdmission.ACCEPTED, PhonePushInbox(state).admit(dismiss, now))
    }
    @Test fun ambiguousRecipientAndContradictoryHelperMetadataAreRejected() {
        val state = state(); pin(state)
        val both = JSONObject(raw()); both.getJSONArray("encryptedPayloads").put(JSONObject(raw(remote)).getJSONArray("encryptedPayloads").getJSONObject(0))
        assertTrue(runCatching { open(state, both.toString()) }.isFailure)
        // A known helper key cannot claim the official installation/public key in its authenticated payload.
        val value = JSONObject(vectors.getJSONObject(0).getJSONObject("plaintext").toString())
        val encrypted = PhonePushCrypto.encrypt(value.toString().toByteArray(), peer.tuple, phone.keyID, helper.keyID,
            Base64.getDecoder().decode(phone.descriptor().publicKey), helper.privateKey)
        assertTrue(runCatching { open(state, JSONObject().put("encryptedPayloads", JSONArray().put(encrypted.wire())).toString()) }.isFailure)
    }
    @Test fun nativeKeyRotationOrForgetAndReaddRetiresHelperBinding() {
        val state = state(); pin(state); val message = open(state); val keys = PhonePushKeyState(state)
        keys.pin(team, mac.origin, peer.copy(descriptor = PhonePushIdentity.generate().descriptor()))
        assertFalse(message.permits(state)); assertNull(PhonePushHelperState(state).binding(team, mac.origin))
        keys.pin(team, mac.origin, peer); pin(state)
        state.put("pairings", JSONArray()); keys.prune(); PhonePushHelperState(state).prune()
        state.put("pairings", JSONArray().put(NativePairingRecords.encode(mac))); keys.pin(team, mac.origin, peer)
        assertNull(PhonePushHelperState(state).binding(team, mac.origin))
        assertTrue(runCatching { open(state) }.isFailure)
    }
    @Test fun staleNativeSnapshotOrDifferentScopeCannotEnrollHelper() {
        val state = state(); val keys = PhonePushKeyState(state); val epoch = keys.peerEpoch(team, mac.origin)!!
        keys.pin(team, mac.origin, peer.copy(descriptor = PhonePushIdentity.generate().descriptor()))
        assertThrows(IllegalStateException::class.java) { PhonePushHelperState(state).pin(team, mac.origin, peer, epoch, helper.descriptor(), project) }
        assertThrows(IllegalStateException::class.java) { PhonePushHelperState(state).pin(team.copy(teamId = "other"), mac.origin, peer, epoch, helper.descriptor(), project) }
        assertFalse(state.has(PhonePushHelperState.KEY))
    }
    @Test fun helperReplyIsEncryptedForOfficialMacAndRetainsRevocationAfterRestore() {
        val state = state(); val binding = pin(state); val message = open(state); val route = route(message)
        val actions = PhoneReplyActions(state); val id = actions.stage(message, route, now)!!
        assertEquals(peer, actions.directTarget(route.routeId, id)!!.peer)
        // Legacy action readers see an unrecognized helper peer and reject it.
        val row = state.getJSONObject(PhoneReplyActions.KEY).getJSONArray("items").getJSONObject(0)
        assertNotEquals(peer, PhonePushPeer.parse(row.getJSONObject("peer")))
        assertEquals(PhoneReplySubmission.QUEUED, actions.submit(route.routeId, id, "continue", now))
        val reply = PhoneReplyOutbox(state).pending(now).single()
        assertEquals(peer, reply.peer); assertEquals(binding.epoch, reply.helperEpoch); assertEquals(3, reply.persisted().getInt("version"))
        val envelope = JSONObject(reply.body).getJSONObject("encryptedPayload")
        assertEquals(remote.keyID, envelope.getString("keyID")); assertEquals(remote.installationID, envelope.getString("installationID"))
        fun decrypt(recipient: PhonePushIdentity): String {
            val suite = HPKE(HPKE.mode_auth, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_CHACHA20_POLY1305)
            val privateKey = X25519PrivateKeyParameters(recipient.privateKey)
            val context = "cmux-phone-push-v2|${remote.keyID}|${phone.keyID}|".toByteArray() + peer.tuple.canonical()
            return String(suite.setupAuthR(Base64.getDecoder().decode(envelope.getString("encapsulatedKey")),
                AsymmetricCipherKeyPair(privateKey.generatePublicKey(), privateKey), context,
                X25519PublicKeyParameters(Base64.getDecoder().decode(phone.descriptor().publicKey)))
                .open(context, Base64.getDecoder().decode(envelope.getString("ciphertext"))))
        }
        assertEquals("continue", JSONObject(decrypt(remote)).getString("text"))
        assertTrue(runCatching { decrypt(helper) }.isFailure)
        val restored = PreparedPhoneReply.restore(reply.persisted()); assertEquals(binding.epoch, restored.helperEpoch)
        assertEquals(binding.epoch, restored.withDirectFence(true).withDirectFence(false).helperEpoch)
        PhonePushHelperState(state).forget(team, mac.origin)
        assertFalse(PhoneReplyOutbox(state).permits(restored)); assertTrue(PhoneReplyOutbox(state).pending(now).isEmpty())
        assertFalse(message.permits(state)); assertEquals(peer, PhonePushKeyState(state).peer(team, mac.origin))
    }
    @Test fun reenrollmentCannotReviveOldActionsAndVersionDowngradeDropsSavedPackets() {
        val state = state(); val previous = pin(state); val message = open(state); val route = route(message)
        val action = PhoneReplyActions(state).stage(message, route, now)!!
        PhoneReplyActions(state).submit(route.routeId, action, "pending", now)
        val packet = PhoneReplyOutbox(state).pending(now).single()
        for (version in listOf(1, 2)) assertTrue(runCatching { PreparedPhoneReply.restore(packet.persisted().put("version", version)) }.isFailure)
        assertTrue(runCatching { PreparedPhoneReply.restore(packet.persisted().apply { remove("helper_epoch") }) }.isFailure)
        PhonePushHelperState(state).forget(team, mac.origin); assertNotEquals(previous.epoch, pin(state).epoch)
        assertFalse(PhoneReplyOutbox(state).permits(packet))
        assertEquals(PhoneReplySubmission.RETIRED, PhoneReplyActions(state).submit(route.routeId, action, "late", now))
    }
    @Test fun tokenConsentProjectAndLoginRetirementRemoveOnlyHelpers() {
        val state = state(); val binding = pin(state)
        val token = PhoneFcmTokenGrant(team.login, project, "token-epoch")
        PhonePushHelperState(state).retainForToken(token)
        assertEquals(binding, PhonePushHelperState(state).binding(team, mac.origin))
        PhonePushHelperState(state).retainForToken(token.copy(epoch = "rotated"))
        assertEquals(binding, PhonePushHelperState(state).binding(team, mac.origin))
        PhonePushHelperState(state).retainForToken(token.copy(login = "other-login"))
        assertNull(PhonePushHelperState(state).binding(team, mac.origin)); assertEquals(peer, PhonePushKeyState(state).peer(team, mac.origin))
        pin(state); PhonePushHelperState(state).retainForToken(token.copy(project = project.copy(project = "other-project")))
        assertNull(PhonePushHelperState(state).binding(team, mac.origin))
        pin(state); PhonePushHelperState(state).retainForToken(null); assertFalse(state.has(PhonePushHelperState.KEY))
    }
}
