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

    @Test fun scopedQrUpgradePersistsOriginAndKeepsExactConnectionPreference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = UUID.randomUUID().toString()
        val store = NativeCredentialStore(context, "pairing_upgrade_$fixture")
        val team = NativeTeamScope("fixture-login", "user", "team-$fixture", 1)
        val mac = IrohV2Computer("record", "ab".repeat(32), "mac", "default", "Fixture Mac", emptyList())
        val target = NativeComputerTarget.from(mac)
        val settings = NativeMacConnectionStore.create(context, team)
        try {
            store.update { it.put("task_session", team.login).put("refresh_token", "fixture-refresh") }
            val qr = "cmux-ios://attach?v=2&r=100.99.1.2:58465&ub=user"
            val pairing = PairingCodeParser.parse(qr).getOrThrow() as PairingCode.Tailscale
            val saved = TailscaleGrantStore(store::load, store::update)
            val grant = TailscaleSavedGrant(UUID.randomUUID().toString(), team.userId, team.teamId, TailscaleGrantStore.source(pairing),
                mac.deviceId, mac.buildTag, pairing.routes.single())
            saved.save(team, grant) { true }
            val initial = store.rememberAuthenticatedMac(NativeCredentialStore.PairedMac(qr, mac.deviceId, mac.name, mac.buildTag), team) { true }
            store.update { it.put("computer_selection", initial.origin) }
            settings.update(target, { true }) { it.copy(method = NativeMacConnectionMethod.TAILSCALE) }
            val before = settings.state.value
            val native = NativeCredentialStore.PairedMac(PairingCodeParser.computer(mac, team), mac.deviceId, mac.name, mac.buildTag)
            val upgraded = store.rememberAuthenticatedMac(native, team) { true }
            val reloaded = NativeCredentialStore(context, "pairing_upgrade_$fixture").pairedMacs().single()
            assertEquals(upgraded, reloaded); assertEquals(initial.origin, reloaded.origin)
            assertEquals(native.code, reloaded.code); assertEquals(team.userId, reloaded.accountUserId)
            assertEquals(team.teamId, reloaded.accountTeamId); assertEquals(before, settings.state.value)
            assertEquals(initial.origin, store.load()!!.getString("computer_selection"))
            assertEquals(listOf(grant), saved.computer(team, target))
            val encrypted = context.getSharedPreferences("pairing_upgrade_$fixture", 0).getString("state", "")!!
            assertFalse(encrypted.contains(initial.origin)); assertFalse(encrypted.contains(team.teamId))
        } finally { settings.removeComputer(target) { true }; store.clear() }
    }

    @Test fun scopedLocalRemovalPreservesAnotherTeamsIdenticalQrAndGrant() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = UUID.randomUUID().toString()
        val store = NativeCredentialStore(context, "pairing_owners_$fixture")
        try {
            val first = NativeTeamScope("fixture-login", "user", "team-a", 1)
            val second = first.copy(teamId = "team-b", generation = 2)
            store.update { it.put("task_session", first.login).put("refresh_token", "fixture-refresh") }
            val qr = "cmux-ios://attach?v=2&r=100.99.1.2:58465&ub=user"
            val pairing = PairingCodeParser.parse(qr).getOrThrow() as PairingCode.Tailscale
            val saved = TailscaleGrantStore(store::load, store::update)
            val rows = listOf(first, second).map { team ->
                saved.save(team, TailscaleSavedGrant(UUID.randomUUID().toString(), team.userId, team.teamId,
                    TailscaleGrantStore.source(pairing), "mac", "default", pairing.routes.single())) { true }
                store.rememberAuthenticatedMac(NativeCredentialStore.PairedMac(qr, "mac", "Mac", "default"), team) { true }
            }
            assertNotEquals(rows[0].origin, rows[1].origin)
            assertEquals(2, store.pairedMacs().size)
            store.forgetMac(qr, first) { true }
            assertEquals(listOf(rows[1]), NativeCredentialStore(context, "pairing_owners_$fixture").pairedMacs())
            assertNull(saved.find(first, TailscaleGrantStore.source(pairing)))
            assertNotNull(saved.find(second, TailscaleGrantStore.source(pairing)))
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
