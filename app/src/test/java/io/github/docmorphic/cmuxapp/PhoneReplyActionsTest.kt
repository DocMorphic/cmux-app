package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

class PhoneReplyActionsTest {
    private val now = 1_800_000_000_000L
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
        IrohV2Computer("record", "a".repeat(64), "directory", "stable", "Mac", emptyList()), team), "directory", "Mac", "stable"), team)
    private val remote = PhonePushIdentity("mac-installation", "mac-key", ByteArray(32) { (it + 32).toByte() })
    private fun state(): JSONObject {
        val state = JSONObject().put("task_session", team.login).put("refresh_token", "fixture")
            .put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
        val keys = PhonePushKeyState(state); val local = keys.identity(team.login)
        keys.pin(team, mac.origin, PhonePushPeer(PhonePushTuple(team.userId, null, "android.fixture", local.installationID,
            "physical-mac", "stable", "fixture.mac"), remote.descriptor()))
        return state
    }
    private fun message(state: JSONObject, textShape: Boolean = true, target: String = "surface"): PhonePushMessage {
        val keys = PhonePushKeyState(state); val phone = keys.existingIdentity(team.login)!!; val peer = keys.peer(team, mac.origin)!!
        val value = JSONObject().put("kind", "notify").put("correlationId", UUID.randomUUID().toString())
            .put("expirationEpochSeconds", now / 1000 + 120).put("badgeCount", 1).put("hideContent", false)
            .put("title", "Fixture").put("subtitle", "").put("body", "Choose next step").put("workspaceId", "w".repeat(200))
            .put("surfaceId", target).put("notificationId", UUID.randomUUID().toString()).put("retargetsToLiveSurfaceOwner", false)
            .put("category", if (textShape) "cmux.terminal.reply" else "cmux.terminal").put("replyShape", if (textShape) "text" else "none")
        val envelope = PhonePushCrypto.encrypt(value.toString().toByteArray(), peer.tuple, phone.keyID, remote.keyID,
            Base64.getDecoder().decode(phone.descriptor().publicKey), remote.privateKey)
        return PhonePushMessage.open(JSONObject().put("encryptedPayloads", JSONArray().put(envelope.wire())).toString(),
            state, team, mac.origin, "android.fixture", now)
    }
    private fun route(message: PhonePushMessage) = message.notification!!.let { item ->
        NotificationDestination(UUID.randomUUID().toString(), mac.origin, item.id, item.workspaceId, item.surfaceId,
            item.retargetsToLiveSurfaceOwner, team.login)
    }

    @Test fun replyActionStartsItsWindowAtSubmissionAndOneTransactionConsumesIt() {
        val state = state(); val message = message(state, target = "s".repeat(200)); val route = route(message)
        val actions = PhoneReplyActions(state); val action = checkNotNull(actions.stage(message, route, now))
        val later = now + 3_600_000
        assertEquals(PhoneReplySubmission.QUEUED, actions.submit(route.routeId, action, " literal reply λ 中\n", later))
        val first = PhoneReplyOutbox(state).pending(later).single()
        assertEquals(later, first.createdAtMillis); assertEquals(action, first.replyID)
        assertNotNull(first.peerEpoch); assertEquals(first.peerEpoch, PreparedPhoneReply.restore(first.persisted()).peerEpoch)
        val restored = JSONObject(state.toString())
        assertEquals(PhoneReplySubmission.ALREADY_QUEUED, PhoneReplyActions(restored).submit(route.routeId, action, "changed text", later + 1))
        assertEquals(first.body, PhoneReplyOutbox(restored).pending(later + 1).single().body)
        PhoneReplyOutbox(restored).finish(first, PhoneReplyRelayResult.Accepted, later + 2)
        PhoneReplyOutbox(restored).prune(later + 1_000_000)
        assertEquals(PhoneReplySubmission.ALREADY_QUEUED, PhoneReplyActions(restored).submit(route.routeId, action, "old broadcast", later + 1_000_000))
        assertTrue(PhoneReplyOutbox(restored).pending(later + 1_000_000).isEmpty())
        assertFalse(restored.toString().contains("literal reply"))
    }

    @Test fun invalidInputAndFullQueueDoNotConsumeAnActionOrEvictEarlierReplies() {
        val state = state(); val message = message(state); val route = route(message)
        val actions = PhoneReplyActions(state); val action = actions.stage(message, route, now)!!
        for (text in listOf(" ", "x".repeat(8193), "\u0001".repeat(8192)))
            assertEquals(PhoneReplySubmission.INVALID_TEXT, actions.submit(route.routeId, action, text, now))
        val phone = PhonePushKeyState(state).existingIdentity(team.login)!!
        val outbox = PhoneReplyOutbox(state)
        repeat(PhoneReplyOutbox.CAPACITY) { outbox.enqueue(PreparedPhoneReply.prepare("earlier-$it", team, mac.origin,
            message.peer, phone, "workspace", "surface", false, "earlier", now), now) }
        assertEquals(PhoneReplySubmission.FULL, actions.submit(route.routeId, action, "new reply", now))
        assertEquals(PhoneReplyOutbox.CAPACITY, outbox.pending(now).size)
        outbox.finish(outbox.pending(now).first(), PhoneReplyRelayResult.Accepted, now)
        assertEquals(PhoneReplySubmission.QUEUED, actions.submit(route.routeId, action, "new reply", now))
    }

    @Test fun onlyAuthenticatedReplyCapabilityAndExactRouteCanCreateAnAction() {
        val state = state(); val plain = message(state, textShape = false)
        assertNull(PhoneReplyActions(state).stage(plain, route(plain), now))
        val reply = message(state); val route = route(reply)
        assertNull(PhoneReplyActions(state).stage(reply, route.copy(surfaceId = "other"), now))
        assertNull(PhoneReplyActions(state).stage(reply, route, now + 120_000))
        val action = PhoneReplyActions(state).stage(reply, route, now)!!
        assertEquals(PhoneReplySubmission.RETIRED, PhoneReplyActions(state).submit(UUID.randomUUID().toString(), action, "text", now))
        assertFalse(state.has(PhoneReplyOutbox.KEY))
    }

    @Test fun sameKeyRefreshPreservesActionsButForgetReaddAndRotationRetireThem() {
        val state = state(); val reply = message(state); val route = route(reply)
        val action = PhoneReplyActions(state).stage(reply, route, now)!!
        val keys = PhonePushKeyState(state); val before = keys.peerEpoch(team, mac.origin)
        keys.pin(team, mac.origin, reply.peer)
        assertEquals(before, keys.peerEpoch(team, mac.origin))
        assertTrue(reply.permits(state))
        state.put("pairings", JSONArray()); keys.prune(); PhoneReplyActions(state).prune()
        state.put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
        keys.pin(team, mac.origin, reply.peer)
        assertNotEquals(before, keys.peerEpoch(team, mac.origin)); assertFalse(reply.permits(state))
        assertEquals(PhoneReplySubmission.RETIRED, PhoneReplyActions(state).submit(route.routeId, action, "stale", now))
        val next = message(state); val destination = route(next); val nextAction = PhoneReplyActions(state).stage(next, destination, now)!!
        keys.pin(team, mac.origin, next.peer.copy(descriptor = PhonePushIdentity.generate().descriptor()))
        assertEquals(PhoneReplySubmission.RETIRED, PhoneReplyActions(state).submit(destination.routeId, nextAction, "stale", now))
    }

    @Test fun queuedPacketAlsoRetainsItsEnrollmentFenceAcrossRestoration() {
        val state = state(); val message = message(state); val route = route(message)
        val action = PhoneReplyActions(state).stage(message, route, now)!!
        PhoneReplyActions(state).submit(route.routeId, action, "queued", now)
        val old = PreparedPhoneReply.restore(PhoneReplyOutbox(state).pending(now).single().persisted())
        val keys = PhonePushKeyState(state)
        state.put("pairings", JSONArray()); keys.prune(); PhoneReplyOutbox(state).prune(now)
        state.put("pairings", JSONArray().put(NativePairingRecords.encode(mac))); keys.pin(team, mac.origin, message.peer)
        assertFalse(PhoneReplyOutbox(state).permits(old))
        assertEquals(ReplyEnqueueResult.RETIRED, PhoneReplyOutbox(state).enqueue(old, now))
        assertTrue(runCatching { PreparedPhoneReply.restore(old.persisted().put("peer_epoch", 123)) }.isFailure)
    }

    @Test fun loginReplacementAndCapacityEvictionDoNotReviveCachedActions() {
        val state = state(); val message = message(state); val destination = route(message)
        val first = PhoneReplyActions(state).stage(message, destination, now)!!
        val items = state.getJSONObject(PhoneReplyActions.KEY).getJSONArray("items")
        val row = items.getJSONObject(0)
        repeat(PhoneReplyActions.CAPACITY - 1) { items.put(JSONObject(row.toString()).put("action", UUID.randomUUID().toString()).put("route", UUID.randomUUID().toString())) }
        val fresh = message(state); PhoneReplyActions(state).stage(fresh, route(fresh), now)
        assertEquals(PhoneReplySubmission.RETIRED, PhoneReplyActions(state).submit(destination.routeId, first, "evicted", now))
        state.put("task_session", "replacement"); PhoneReplyActions(state).prune()
        assertFalse(state.has(PhoneReplyActions.KEY))
    }
}
