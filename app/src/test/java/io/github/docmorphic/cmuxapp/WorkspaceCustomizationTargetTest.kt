package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class WorkspaceCustomizationTargetTest {
    private val team = NativeTeamScope("login", "account", "team", 1)
    private val mac = NativeCredentialStore.PairedMac("route", "device", "Mac", "default", "account", "team")
    private val target = WorkspaceCustomizationTarget.capture("login", team, mac, "workspace")!!

    @Test fun savedOwnerRestoresAcrossRefreshAndCachedAccountDiscovery() {
        val restored = WorkspaceCustomizationTarget.restore(target.saved())!!
        assertEquals(target, restored)
        assertTrue(restored.matches("login", team.copy(generation = 9), mac.copy(name = "Renamed")))
        assertTrue(restored.matches("login", null, mac))
        val anonymous = mac.copy(accountUserId = null, accountTeamId = null)
        val local = WorkspaceCustomizationTarget.capture("login", null, anonymous, "workspace")!!
        assertEquals(local, WorkspaceCustomizationTarget.restore(local.saved()))
        assertTrue(local.matches("login", null, anonymous))
    }

    @Test fun replacementLoginTeamAccountMacAndBuildCannotReuseDraft() {
        assertFalse(target.matches(null, team, mac))
        assertFalse(target.matches("replacement", team, mac))
        assertFalse(target.matches("login", team.copy(userId = "other"), mac))
        assertFalse(target.matches("login", team.copy(teamId = "other"), mac))
        assertFalse(target.matches("login", team, mac.copy(deviceId = "other")))
        assertFalse(target.matches("login", team, mac.copy(instanceTag = "nightly")))
        assertFalse(target.matches("login", null, mac.copy(accountTeamId = "other")))
        assertNull(WorkspaceCustomizationTarget.capture(null, team, mac, "workspace"))
    }

    @Test fun LegacyAndMalformedSavedTargetsAreDiscarded() {
        assertNull(WorkspaceCustomizationTarget.restore(listOf(target.origin, target.workspaceId)))
        assertNull(WorkspaceCustomizationTarget.restore(emptyList()))
        assertNull(WorkspaceCustomizationTarget.restore(target.saved().toMutableList().apply { this[0] = "3" }))
        assertNull(WorkspaceCustomizationTarget.restore(target.saved().toMutableList().apply { this[1] = "" }))
        assertNull(WorkspaceCustomizationTarget.restore(target.saved().toMutableList().apply { this[6] = "x".repeat(16_385) }))
    }
}
