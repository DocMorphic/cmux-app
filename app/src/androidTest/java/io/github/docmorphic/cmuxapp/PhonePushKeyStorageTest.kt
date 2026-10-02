package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Base64

class PhonePushKeyStorageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun installationIdentityAndPeerSurviveEncryptedReconstructionButNotAccountReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeNotificationService.setEnabled(context, false)
        val store = NativeCredentialStore(context)
        store.clear()
        val team = NativeTeamScope("key-storage-login", "fixture-user", "fixture-team", 1)
        val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
            IrohV2Computer("record", "a".repeat(64), "directory-mac", "stable", "Fixture Mac", emptyList()), team),
            "directory-mac", "Fixture Mac", "stable"), team)
        try {
            store.update { it.put("task_session", team.login).put("refresh_token", "fixture-refresh")
                .put("pairings", JSONArray().put(NativePairingRecords.encode(mac))) }
            var identity: PhonePushIdentity? = null
            store.update { identity = PhonePushKeyState(it).identity(team.login) }
            val local = checkNotNull(identity)
            val remote = PhonePushIdentity.generate()
            val peer = PhonePushPeer(PhonePushTuple(team.userId, null, context.packageName, local.installationID,
                "physical-mac", "stable", "fixture.mac.build"), remote.descriptor())
            store.update { PhonePushKeyState(it).pin(team, mac.origin, peer) }
            val reconstructed = NativeCredentialStore(context)
            val state = checkNotNull(reconstructed.load())
            assertArrayEquals(local.privateKey, PhonePushKeyState(state).identity(team.login).privateKey)
            assertEquals(local.descriptor(), PhonePushKeyState(state).identity(team.login).descriptor())
            assertEquals(peer, PhonePushKeyState(state).peer(team, mac.origin))
            val encrypted = context.getSharedPreferences("native_cmux", Context.MODE_PRIVATE).getString("state", "")!!
            for (sensitive in listOf(local.keyID, local.installationID, Base64.getEncoder().encodeToString(local.privateKey), "physical-mac"))
                assertFalse(encrypted.contains(sensitive))
            store.forgetMac(mac.code, team) { true }
            assertNull(PhonePushKeyState(store.load()!!).peer(team, mac.origin))
            assertEquals(local.keyID, PhonePushKeyState(store.load()!!).identity(team.login).keyID)
            store.update { it.put("task_session", "replacement-login") }
            assertFalse(store.load()!!.has(PhonePushKeyState.KEY))
            store.update { identity = PhonePushKeyState(it).identity("replacement-login") }
            assertNotEquals(local.keyID, identity!!.keyID)
            store.clear()
            assertNull(store.load())
        } finally { store.clear() }
    }
    @Test fun replyOutboxRestoresExactCiphertextAndCredentialTransactionsRetireIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeNotificationService.setEnabled(context, false)
        val store = NativeCredentialStore(context); store.clear()
        val team = NativeTeamScope("reply-storage-login", "fixture-user", "fixture-team", 1)
        val mac = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(PairingCodeParser.computer(
            IrohV2Computer("record", "a".repeat(64), "directory-mac", "stable", "Fixture Mac", emptyList()), team),
            "directory-mac", "Fixture Mac", "stable"), team)
        try {
            store.update { state ->
                state.put("task_session", team.login).put("refresh_token", "fixture-refresh")
                    .put("pairings", JSONArray().put(NativePairingRecords.encode(mac)))
                val keys = PhonePushKeyState(state); val identity = keys.identity(team.login)
                keys.pin(team, mac.origin, PhonePushPeer(PhonePushTuple(team.userId, null, context.packageName, identity.installationID,
                    "physical-mac", "stable", "fixture.mac.build"), PhonePushIdentity.generate().descriptor()))
            }
            val state = store.load()!!; val keys = PhonePushKeyState(state)
            val now = System.currentTimeMillis()
            fun reply(id: String) = PreparedPhoneReply.prepare(id, team, mac.origin, keys.peer(team, mac.origin)!!,
                keys.existingIdentity(team.login)!!, "workspace", "surface", false, "Private fixture reply λ 中", now)
            val first = reply("first"); val second = reply("second")
            store.update {
                assertEquals(ReplyEnqueueResult.QUEUED, PhoneReplyOutbox(it).enqueue(first, now))
                assertEquals(ReplyEnqueueResult.QUEUED, PhoneReplyOutbox(it).enqueue(second, now))
            }
            val reconstructed = NativeCredentialStore(context)
            assertEquals(listOf(first.body, second.body), PhoneReplyOutbox(reconstructed.load()!!).pending(now).map { it.body })
            val encrypted = context.getSharedPreferences("native_cmux", Context.MODE_PRIVATE).getString("state", "")!!
            for (sensitive in listOf("Private fixture reply", first.body, "reply-storage-login", "physical-mac"))
                assertFalse(encrypted.contains(sensitive))
            store.update { PhoneReplyOutbox(it).finish(first, PhoneReplyRelayResult.Accepted, now + 1) }
            assertEquals(listOf("second"), PhoneReplyOutbox(reconstructed.load()!!).pending(now + 1).map { it.replyID })
            assertEquals("accepted", PhoneReplyOutbox(reconstructed.load()!!).receipts(now + 1).single().status)
            store.update { it.put("task_session", "replacement-login") }
            assertFalse(reconstructed.load()!!.has(PhoneReplyOutbox.KEY))
            assertFalse(reconstructed.load()!!.has(PhonePushKeyState.KEY))
            // Reusing the former login label cannot resurrect bytes erased by the transaction.
            store.update { it.put("task_session", team.login) }
            assertTrue(PhoneReplyOutbox(reconstructed.load()!!).pending(now + 2).isEmpty())
        } finally { store.clear() }
    }

}
