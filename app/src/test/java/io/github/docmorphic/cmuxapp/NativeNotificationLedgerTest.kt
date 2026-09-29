package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeNotificationLedgerTest {
    private fun item(id: String, read: Boolean = false) =
        NativeNotification(id, "workspace", "surface", "Ready", "Done", read)

    @Test fun eachMacHasQuietBaselineAndIndependentUnreadHistory() {
        val state = JSONObject()
        val ledger = NativeNotificationLedger(state)
        assertTrue(ledger.baseline("a", listOf(item("old"))))
        assertTrue(ledger.baseline("b", emptyList()))
        assertFalse(ledger.baseline("a", listOf(item("new"))))
        assertEquals(listOf("new"), ledger.unseen("a", listOf(item("old"), item("new"), item("read", true))).map { it.id })
        ledger.acknowledge("a", listOf("new"))
        val restarted = NativeNotificationLedger(JSONObject(state.toString()))
        assertTrue(restarted.unseen("a", listOf(item("new"))).isEmpty())
        assertEquals(listOf("new"), restarted.unseen("b", listOf(item("new"))).map { it.id })
    }

    @Test fun failedPostCanRetrySameDurableRouteWithoutLosingUnread() {
        val state = JSONObject()
        val ledger = NativeNotificationLedger(state)
        ledger.baseline("a", emptyList())
        val route = ledger.stage("a", item("n"))
        val restarted = NativeNotificationLedger(JSONObject(state.toString()))
        assertEquals(route, restarted.destination(route.routeId))
        assertEquals(listOf(item("n")), restarted.unseen("a", listOf(item("n"))))
        assertEquals(route.routeId, restarted.stage("a", item("n")).routeId)
        restarted.acknowledge("a", listOf("n"))
        assertTrue(restarted.unseen("a", listOf(item("n"))).isEmpty())
    }

    @Test fun collidingHashesAndIdenticalIdsAcrossMacsHaveDistinctRoutes() {
        assertEquals("FB".hashCode(), "Ea".hashCode())
        val ledger = NativeNotificationLedger(JSONObject())
        val routes = listOf(ledger.stage("a", item("FB")), ledger.stage("a", item("Ea")), ledger.stage("b", item("FB")))
        assertEquals(3, routes.map { it.routeId }.toSet().size)
        routes.forEach { assertEquals(it, ledger.destination(it.routeId)) }
        assertNotEquals(pairingOrigin("a"), pairingOrigin("b"))
        assertEquals(pairingOrigin("a"), pairingOrigin("a"))
    }

    @Test fun sameRouteCannotReuseAnotherDeviceOrInstallationIdentity() {
        val a = NativeCredentialStore.PairedMac("same-code", "a", "Mac", "stable")
        assertEquals(a.origin, a.copy(name = "Renamed Mac").origin)
        assertNotEquals(a.origin, a.copy(deviceId = "b").origin)
        assertNotEquals(a.origin, a.copy(instanceTag = "dev").origin)
        assertNotEquals(a.origin, a.copy(instanceTag = null).origin)
    }

    @Test fun forgottenMacDropsOnlyItsRoutesAndBaseline() {
        val ledger = NativeNotificationLedger(JSONObject())
        ledger.baseline("a", listOf(item("old"))); ledger.baseline("b", emptyList())
        val a = ledger.stage("a", item("n")); val b = ledger.stage("b", item("n"))
        assertEquals(listOf(a.routeId), ledger.prune(setOf("b")))
        assertNull(ledger.destination(a.routeId)); assertEquals(b, ledger.destination(b.routeId))
        assertTrue(ledger.baseline("a", emptyList()))
        assertFalse(ledger.baseline("b", emptyList()))
    }

    @Test fun boundedRoutesReturnEveryEvictedAlertForCancellation() {
        val ledger = NativeNotificationLedger(JSONObject())
        repeat(NativeNotificationLedger.ROUTE_LIMIT + 5) { ledger.stage("a", item(it.toString())) }
        val removed = ledger.prune(setOf("a"))
        assertEquals(5, removed.size)
        assertEquals(NativeNotificationLedger.ROUTE_LIMIT, ledger.destinations().size)
        removed.forEach { assertNull(ledger.destination(it)) }
        ledger.acknowledge("a", (0..NativeNotificationLedger.SEEN_LIMIT).map { it.toString() })
        assertTrue(ledger.unseen("a", listOf(item("0"))).isEmpty())
        assertEquals(1, ledger.unseen("a", listOf(item(NativeNotificationLedger.SEEN_LIMIT.toString()))).size)
    }

    @Test fun movedSurfaceProvenanceSurvivesPersistenceAndRestaging() {
        val state = JSONObject()
        val ledger = NativeNotificationLedger(state)
        val initial = ledger.stage("a", item("n"))
        val updated = ledger.stage("a", item("n").copy(workspaceId = "moved", retargetsToLiveSurfaceOwner = true))
        assertEquals(initial.routeId, updated.routeId)
        val result = NativeNotificationLedger(JSONObject(state.toString())).destination(updated.routeId)!!.notification()
        assertEquals("moved", result.workspaceId)
        assertEquals("surface", result.surfaceId)
        assertTrue(result.retargetsToLiveSurfaceOwner)
    }
    @Test fun mergedPairingsKeepBothPendingIntentsAndUnionAcknowledgementsAcrossRestart() {
        val state = JSONObject(); val ledger = NativeNotificationLedger(state)
        ledger.baseline("first", listOf(item("old-a")))
        ledger.baseline("second", listOf(item("old-b")))
        val a = ledger.stage("first", item("shared"))
        val b = ledger.stage("second", item("shared"))
        val foreign = ledger.stage("other-team", item("shared"))
        ledger.acknowledge("second", listOf("shared"))
        ledger.coalesce("first", setOf("second"))
        val restarted = NativeNotificationLedger(JSONObject(state.toString()))
        restarted.coalesce("first", setOf("second"))
        assertTrue(restarted.prune(setOf("first", "other-team")).isEmpty())
        assertEquals(a, restarted.destination(a.routeId))
        assertEquals(b.copy(origin = "first"), restarted.destination(b.routeId))
        assertEquals(foreign, restarted.destination(foreign.routeId))
        assertEquals(listOf("new"), restarted.unseen("first", listOf("old-a", "old-b", "shared", "new").map { item(it) }).map { it.id })
        assertFalse(restarted.baseline("first", emptyList()))
        assertEquals(setOf(a.routeId, b.routeId), restarted.prune(setOf("other-team")).toSet())
    }

    @Test fun absentPrimaryInheritsOnlyExistingAliasBaseline() {
        val ledger = NativeNotificationLedger(JSONObject())
        ledger.coalesce("primary", setOf("missing"))
        assertTrue(ledger.baseline("primary", emptyList()))
        ledger.baseline("old", listOf(item("seen")))
        ledger.coalesce("new", setOf("old"))
        assertFalse(ledger.baseline("new", emptyList()))
        assertTrue(ledger.unseen("new", listOf(item("seen"))).isEmpty())
    }

}
