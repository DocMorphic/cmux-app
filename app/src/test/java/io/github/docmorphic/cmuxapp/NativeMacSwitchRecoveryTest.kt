package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeMacSwitchRecoveryTest {
    private val owner = NativeMacSwitchRecovery.Owner("login", NativeTeamScope("login", "user", "team", 1))
    private val a = NativeCredentialStore.PairedMac("route-a", "mac", "Mac A", "default")
    private val b = NativeCredentialStore.PairedMac("route-b", "mac", "Mac Nightly", "nightly")
    private fun ready() = NativeMacSwitchRecovery().apply { connected(owner, a) }

    @Test fun cancellingToAllRestoresOriginalMacAndRejectsLateFailure() {
        val state = ready()
        state.begin(owner, b.code, a.code, a.origin)
        val old = state.entering(owner, b.code)
        assertEquals(NativeMacSwitchRecovery.Baseline(a, ""), state.cancelAndRestore(owner, b.code, "") { true })
        assertNull(state.failed(old, owner, b.code) { true })
        // A new selection during restoration retains the user's All Computers filter.
        state.begin(owner, "route-c", null, "")
        assertEquals(NativeMacSwitchRecovery.Baseline(a, ""), state.failed(state.entering(owner, "route-c"), owner, "route-c") { true })
    }
    @Test fun cancelledSwitchCannotRestoreWrongOwnerRouteOrRevokedBaseline() {
        val state = ready()
        state.begin(owner, b.code, a.code, a.origin)
        assertNull(state.cancelAndRestore(owner.copy(login = "new"), b.code, "") { true })
        assertEquals(a, state.failed(state.entering(owner, b.code), owner, b.code) { true }?.mac)
        state.begin(owner, b.code, a.code, a.origin)
        assertNull(state.cancelAndRestore(owner, "newer-route", "") { true })
        assertEquals(a, state.failed(state.entering(owner, b.code), owner, b.code) { true }?.mac)
        state.begin(owner, b.code, a.code, a.origin)
        assertNull(state.cancelAndRestore(owner, b.code, "") { false })
        state.clear(); state.begin(owner, b.code, null, "")
        assertNull(state.cancelAndRestore(owner, b.code, "") { true })
    }

    @Test fun confirmedLaunchAttachCanRestoreSavedMacBeforeAnyLiveConnection() {
        val state = NativeMacSwitchRecovery()
        state.begin(owner, b.code, null, "", savedFallback = a)
        assertEquals(a, state.failed(state.entering(owner, b.code), owner, b.code) { true }?.mac)
    }
    @Test fun liveBaselineWinsOverStoredFallbackAndRevocationStillPreventsRestore() {
        val state = ready()
        state.begin(owner, "route-c", a.code, a.origin, savedFallback = b)
        assertEquals(a, state.failed(state.entering(owner, "route-c"), owner, "route-c") { true }?.mac)
        state.clear()
        state.begin(owner, b.code, null, "", savedFallback = a)
        assertNull(state.failed(state.entering(owner, b.code), owner, b.code) { false })
        state.begin(owner, a.code, null, "", savedFallback = a)
        assertNull(state.failed(state.entering(owner, a.code), owner, a.code) { true })
    }

    @Test fun failedSwitchRestoresExactLiveRouteAndOriginalFilterOnlyOnce() {
        val state = ready()
        state.begin(owner, b.code, a.code, "all-computers")
        val ticket = state.entering(owner, b.code)
        assertEquals(NativeMacSwitchRecovery.Baseline(a, "all-computers"), state.failed(ticket, owner, b.code) { true })
        assertNull(state.failed(ticket, owner, b.code) { true })
        // The rollback itself follows ordinary connection recovery, never cycles back to B.
        assertNull(state.failed(state.entering(owner, a.code), owner, a.code) { true })
    }
    @Test fun rapidSelectionsCarryTheOriginalBaselineAndIgnoreEarlierFailures() {
        val state = ready()
        state.begin(owner, b.code, a.code, a.origin)
        val old = state.entering(owner, b.code)
        state.begin(owner, "route-c", null, b.origin)
        val current = state.entering(owner, "route-c")
        assertNull(state.failed(old, owner, b.code) { true })
        assertEquals(a, state.failed(current, owner, "route-c") { true }?.mac)
    }
    @Test fun tapsBeforeClientRetirementKeepTheOriginalFilter() {
        val state = ready()
        state.begin(owner, b.code, a.code, "")
        state.begin(owner, "route-c", a.code, b.origin)
        assertEquals(NativeMacSwitchRecovery.Baseline(a, ""), state.failed(state.entering(owner, "route-c"), owner, "route-c") { true })
    }
    @Test fun newerSelectionDuringRollbackKeepsBaselineAndSupersedesRestoration() {
        val state = ready()
        state.begin(owner, b.code, a.code, a.origin)
        state.failed(state.entering(owner, b.code), owner, b.code) { true }
        val restoration = state.entering(owner, a.code)
        state.begin(owner, "route-c", null, a.origin)
        assertNull(state.failed(restoration, owner, a.code) { true })
        assertEquals(a, state.failed(state.entering(owner, "route-c"), owner, "route-c") { true }?.mac)
    }
    @Test fun verifiedRouteNormalizationKeepsBaselineWithoutRevivingStaleAttempt() {
        val state = ready()
        state.begin(owner, b.code, a.code, a.origin)
        val original = state.entering(owner, b.code)
        state.retarget(original, owner, "normalized-b")
        assertNull(state.failed(original, owner, b.code) { true })
        assertEquals(a, state.failed(state.entering(owner, "normalized-b"), owner, "normalized-b") { true }?.mac)
        state.begin(owner, "route-c", null, "")
        state.retarget(original, owner, b.code)
        assertNotNull(state.entering(owner, "route-c"))
    }
    @Test fun successReplacesTheBaselineAndSameMacReconnectDoesNotRollBack() {
        val state = ready()
        state.begin(owner, b.code, a.code, a.origin)
        val ticket = state.entering(owner, b.code)
        state.connected(owner, b)
        assertNull(state.failed(ticket, owner, b.code) { true })
        state.begin(owner, "route-c", b.code, b.origin)
        assertEquals(b, state.failed(state.entering(owner, "route-c"), owner, "route-c") { true }?.mac)
        state.begin(owner, b.code, b.code, b.origin)
        assertNull(state.entering(owner, b.code))
    }
    @Test fun signOutTeamLoginAndAuthorityChangesPreventRollback() {
        for (changed in listOf(null, owner.copy(login = "new"), owner.copy(team = owner.team!!.copy(teamId = "other")))) {
            val state = ready(); state.begin(owner, b.code, a.code, a.origin)
            val ticket = state.entering(owner, b.code)
            state.reconcile(changed)
            assertNull(state.failed(ticket, changed, b.code) { true })
        }
        val state = ready(); state.begin(owner, b.code, a.code, a.origin)
        assertNull(state.failed(state.entering(owner, b.code), owner, b.code) { false })
    }
    @Test fun initialConnectionUnrelatedNavigationAndCancellationHaveNoRestore() {
        val state = NativeMacSwitchRecovery()
        state.begin(owner, b.code, null, "")
        assertNull(state.failed(state.entering(owner, b.code), owner, b.code) { true })
        state.connected(owner, a); state.begin(owner, b.code, a.code, a.origin)
        val ticket = state.entering(owner, b.code)
        state.entering(owner, "notification-route")
        assertNull(state.failed(ticket, owner, b.code) { true })
        state.begin(owner, b.code, a.code, a.origin)
        val cancelled = state.entering(owner, b.code); state.cancel()
        assertNull(state.failed(cancelled, owner, b.code) { true })
    }
}
