package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSavedIdentityRefreshTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val code = "cmux-ios://attach?v=2&r=100.64.0.7:58465&ub=user"
    private val old = NativePairingRecords.scoped(NativeCredentialStore.PairedMac(code, "mac", "Mac"), team)
    private fun host(build: Any = "default", device: String = "mac") = JSONObject()
        .put("mac_device_id", device).put("mac_instance_tag", build).put("mac_display_name", "Updated Mac")

    @Test fun authenticatesBeforePersistingBuildAndRetiresExactlyOnce() = runBlocking {
        val events = mutableListOf<String>()
        var result = old
        assertTrue(refreshNativeSavedIdentity(old, code, host(), team, { true },
            { events += "account" }, { events += "persist"; result = it }))
        assertEquals(listOf("account", "persist"), events)
        assertEquals("default", result.instanceTag); assertEquals("Updated Mac", result.name)
        assertEquals(old.origin, result.origin); assertNull(result.ticketRevision)
        events.clear()
        assertFalse(refreshNativeSavedIdentity(result, code, host(), team, { true },
            { events += "account" }, { events += "persist" }))
        assertTrue(events.isEmpty())
    }

    @Test fun rejectsWrongHostMalformedTagAndPinnedSiblingBeforeAuthentication() = runBlocking {
        val pinned = old.copy(nativeRouteCode = PairingCodeParser.computer(
            IrohV2Computer("r", "a".repeat(64), "mac", "nightly", "Mac", emptyList()), team))
        for ((row, status) in listOf(old to host(device = "other"), old to host(4), old to host(" default"),
            old to host("\n"), old to host("x".repeat(65)), pinned to host())) {
            var called = false
            assertTrue(runCatching { refreshNativeSavedIdentity(row, code, status, team, { true },
                { called = true }, { called = true }) }.isFailure)
            assertFalse(called)
        }
    }

    @Test fun denialCancellationAndConcurrentRemovalNeverPersist() = runBlocking {
        for (mode in listOf("denied", "cancelled", "removed")) {
            var allowed = true
            var persisted = false
            assertTrue(runCatching { refreshNativeSavedIdentity(old, code, host(), team, { allowed }, {
                when (mode) {
                    "denied" -> error("Account denied")
                    "cancelled" -> throw CancellationException("Cancelled")
                    else -> allowed = false
                }
            }, { persisted = true }) }.isFailure)
            assertFalse(persisted)
        }
    }

    @Test fun authenticatedNativeLocatorRetiresEvenWithSameBuild() = runBlocking {
        val tagged = old.copy(instanceTag = "default")
        val selected = PairingCodeParser.computer(IrohV2Computer("r", "a".repeat(64), "mac", "default", "Mac", emptyList()), team)
        var result: NativeCredentialStore.PairedMac? = null
        assertTrue(refreshNativeSavedIdentity(tagged, selected, host(), team, { true }, {}, { result = it }))
        assertEquals(selected, result!!.code); assertEquals(tagged.origin, result!!.origin)
    }

    @Test fun selectionFollowsOwnedHistoryButNeverAnotherAccountSiblingOrAmbiguousRow() {
        val learned = old.copy(code = "new-code", instanceTag = "default")
        assertEquals(learned, refreshedNativeSavedSelection(old, listOf(learned), team))
        for (rows in listOf(emptyList(), listOf(learned.copy(deviceId = "other")),
            listOf(learned.copy(accountTeamId = "other")), listOf(learned.copy(stableOrigin = "unrelated")),
            listOf(learned, learned.copy(code = "another")))) {
            assertEquals(old, refreshedNativeSavedSelection(old, rows, team))
        }
        assertEquals(learned, refreshedNativeSavedSelection(learned, listOf(learned.copy(instanceTag = "nightly")), team))
    }
}
