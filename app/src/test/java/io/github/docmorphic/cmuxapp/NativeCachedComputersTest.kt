package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeCachedComputersTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val profile = NativeAccountTeamsState(userId = "user", teams = listOf(NativeTeam("team", "My Team")), selectedTeamId = "team")
    private fun mac(name: String = "Studio", owner: NativeTeamScope = team, tag: String? = "default") =
        NativePairingRecords.scoped(NativeCredentialStore.PairedMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "mac", name, tag), owner)
    private fun state(vararg rows: NativeCredentialStore.PairedMac): JSONObject {
        val root = JSONObject().put("task_session", team.login).put("refresh_token", "fixture")
            .put("pairings", JSONArray(rows.map(NativePairingRecords::encode)))
        NativeAccountProfileCache({ root }, { it(root) }).save(team.login, NativeCachedComputers.ENVIRONMENT, profile)
        return root
    }
    private fun cached(root: JSONObject) = NativeAccountProfileCache({ root }, { error("No writes") })
        .read(team.login, NativeCachedComputers.ENVIRONMENT)!!

    @Test fun offlineRowsRemainAccountTeamAndBuildScopedWithoutManufacturingAuthority() {
        val stable = mac(); val nightly = mac("Nightly", tag = "nightly"); val legacy = mac("Legacy", tag = null)
        val root = state(stable, nightly, legacy, mac("Foreign", team.copy(userId = "other")),
            mac("Other team", team.copy(teamId = "other")), mac("Dev", tag = "dev"),
            NativeCredentialStore.PairedMac("cmux-ios://attach?v=2&r=100.64.0.2:58465", "unowned", "Unowned"))
        val profile = cached(root); val before = root.toString()
        val projection = NativeCachedComputers.project(root, team.login, profile)!!
        assertEquals(listOf(stable, nightly, legacy), projection.macs)
        assertNull(profile.scope); assertTrue(profile.cached); assertEquals(before, root.toString())
    }
    @Test fun retiredLoginWrongEnvironmentMissingCredentialsOrMismatchedProfileNeverProjectsRows() {
        val root = state(mac()); val account = cached(root)
        assertNull(NativeCachedComputers.project(root, "replacement", account))
        assertNull(NativeCachedComputers.project(root, team.login, account, "other-environment"))
        assertNull(NativeCachedComputers.project(root, team.login, account.copy(userId = "other")))
        assertNull(NativeCachedComputers.project(root, team.login, account.copy(selectedTeamId = "other")))
        assertNull(NativeCachedComputers.project(root, team.login, account.copy(teams = emptyList())))
        assertNull(NativeCachedComputers.project(root, team.login, account.copy(scope = team)))
        assertNull(NativeCachedComputers.project(root, team.login, account.copy(cached = false)))
        root.remove("refresh_token"); assertNull(NativeCachedComputers.project(root, team.login, account))
    }
    @Test fun hiddenRowsAndVersionWarningsRestoreButForgottenRowsCannotReappear() {
        val row = mac(); val root = state(row); val identity = NativeMacIdentity("mac", "default")
        NativeComputerVisibility.setVisible(root, team.login, row, false) { true }
        NativeMacVersionHistory.record(root, team, mapOf(identity to "0.64.24")) { true }
        val projection = NativeCachedComputers.project(root, team.login, cached(root))!!
        assertEquals(listOf(row), projection.macs); assertTrue(NativeComputerVisibility.isHidden(root, row))
        assertEquals(setOf(identity), projection.warnings(NativeMacCompatibilityPolicy.baked).keys)
        assertTrue(projection.warnings(NativeMacCompatibilityPolicy(emptyList())).isEmpty())
        NativePairingRecords.removeLocal(root, row.code, team)
        val forgotten = NativeCachedComputers.project(root, team.login, cached(root))!!
        assertTrue(forgotten.macs.isEmpty()); assertTrue(forgotten.versions.isEmpty())
    }
    @Test fun displayRoutesUseExactOwnerAndBuildAndNeverExposeGrantAuthority() {
        val row = mac(); val root = state(row)
        val grants = TailscaleGrantStore({ root }, { it(root) })
        grants.save(team, TailscaleSavedGrant("00000000-0000-0000-0000-000000000001", team.userId, team.teamId, "1".repeat(64), "mac", "default",
            PairingCode.Route("100.64.0.5", 58465))) { true }
        grants.save(team, TailscaleSavedGrant("00000000-0000-0000-0000-000000000002", team.userId, team.teamId, "2".repeat(64), "mac", "nightly",
            PairingCode.Route("100.64.0.6", 58465))) { true }
        val projection = NativeCachedComputers.project(root, team.login, cached(root))!!
        assertEquals(mapOf(NativeMacIdentity("mac", "default") to "100.64.0.5:58465"), projection.tailscaleRoutes)
    }
}
