package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeNotificationMonitorStateTest {
    private val mac = NativeCredentialStore.PairedMac("saved", "device", "Mac")
    private val other = NativeCredentialStore.PairedMac("other", "other-device", "Other Mac")
    private val item = NativeNotification("notification", "workspace", "surface", "Ready", "Done", false)

    @Test fun coldMembershipRestorationKeepsDurableTapUntilConnectionAdmissionReturns() {
        val state = JSONObject()
        val route = NativeNotificationLedger(state).stage(mac.origin, item, "login")
        val restoring = NativeNotificationMonitorState(true, listOf(mac)) { false }
        assertTrue(restoring.workers.isEmpty())
        val restarted = NativeNotificationLedger(JSONObject(state.toString()))
        assertTrue(restarted.prune(restoring.retainedOrigins).isEmpty())
        assertEquals(route, restarted.destination(route.routeId))
        val ready = NativeNotificationMonitorState(true, listOf(mac)) { true }
        assertEquals(setOf(mac), ready.workers)
        assertTrue(restarted.prune(ready.retainedOrigins).isEmpty())
        assertEquals(route, restarted.destination(route.routeId))
    }

    @Test fun temporaryAdmissionLossDoesNotEraseOtherSavedComputerRoutes() {
        val ledger = NativeNotificationLedger(JSONObject())
        val route = ledger.stage(other.origin, item, "login")
        val plan = NativeNotificationMonitorState(true, listOf(mac, other)) { it == mac }
        assertEquals(setOf(mac), plan.workers)
        assertTrue(ledger.prune(plan.retainedOrigins).isEmpty())
        assertEquals(route, ledger.destination(route.routeId))
    }

    @Test fun hiddenOrForgottenComputerStillRetiresOnlyItsRoutes() {
        val ledger = NativeNotificationLedger(JSONObject())
        val removed = ledger.stage(mac.origin, item, "login")
        val retained = ledger.stage(other.origin, item, "login")
        val plan = NativeNotificationMonitorState(true, listOf(other)) { false }
        assertEquals(listOf(removed.routeId), ledger.prune(plan.retainedOrigins))
        assertNull(ledger.destination(removed.routeId))
        assertEquals(retained, ledger.destination(retained.routeId))
    }

    @Test fun signedOutAccountRetiresRoutesAndNeverStartsFeedWorkers() {
        val ledger = NativeNotificationLedger(JSONObject())
        val route = ledger.stage(mac.origin, item, "old-login")
        val plan = NativeNotificationMonitorState(false, listOf(mac)) { error("Must not admit a signed-out feed") }
        assertTrue(plan.workers.isEmpty())
        assertEquals(listOf(route.routeId), ledger.prune(plan.retainedOrigins))
        assertNull(ledger.destination(route.routeId))
    }
}
