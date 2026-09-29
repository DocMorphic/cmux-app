package io.github.docmorphic.cmuxapp

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TailscaleGrantPersistenceTest {
    @Test fun encryptedRoutesReloadNotifyOtherStoreAndPreserveNativePairingIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "tailscale_grant_fixture_" + UUID.randomUUID()
        val store = NativeCredentialStore(context, name)
        try {
            val team = NativeTeamScope("fixture-login", "user", "team", 1)
            store.update { it.put("task_session", team.login).put("refresh_token", "fixture-refresh") }
            val mac = IrohV2Computer("record", "ab".repeat(32), "mac", "default", "Fixture Mac", emptyList())
            val nativeCode = PairingCodeParser.computer(mac, team)
            store.rememberMac(nativeCode, mac.deviceId, mac.name, mac.buildTag)
            val other = NativeCredentialStore(context, name)
            val before = other.revisions.value
            val saved = TailscaleGrantStore(store::load, store::update)
            val grant = TailscaleSavedGrant(UUID.randomUUID().toString(), team.userId, team.teamId, "a".repeat(64),
                mac.deviceId, mac.buildTag, PairingCode.Route("100.99.1.2", 58465))
            saved.save(team, grant) { true }
            assertTrue(other.revisions.value > before)
            val restored = TailscaleGrantStore(other::load, other::update)
            assertEquals(listOf(grant), restored.computer(team, NativeComputerTarget.from(mac)))
            assertEquals(nativeCode, other.pairedMacs().single().code)
            assertEquals(nativeCode, other.load()!!.getString("pairing_code"))
            val encrypted = context.getSharedPreferences(name, 0).getString("state", "")!!
            assertFalse(encrypted.contains(grant.route.host)); assertFalse(encrypted.contains("fixture-refresh"))
            saved.removeRoute(team, NativeComputerTarget.from(mac), grant) { true }
            assertTrue(restored.computer(team, NativeComputerTarget.from(mac)).isEmpty())
            assertEquals(nativeCode, other.pairedMacs().single().code)
        } finally { store.clear() }
    }

    @Test fun changedLoginRejectsEncryptedCommitAndClearNotifiesReaders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "tailscale_grant_fixture_" + UUID.randomUUID()
        val store = NativeCredentialStore(context, name)
        try {
            val old = NativeTeamScope("old-login", "user", "team", 1)
            store.update { it.put("task_session", "new-login").put("refresh_token", "fixture") }
            val saved = TailscaleGrantStore(store::load, store::update)
            val grant = TailscaleSavedGrant(UUID.randomUUID().toString(), old.userId, old.teamId, "a".repeat(64),
                "mac", "default", PairingCode.Route("100.99.1.2", 58465))
            assertTrue(runCatching { saved.save(old, grant) { true } }.isFailure)
            assertNull(saved.find(old, grant.source))
            val before = store.revisions.value
            store.clear()
            assertTrue(store.revisions.value > before); assertNull(store.load())
        } finally { store.clear() }
    }
}
