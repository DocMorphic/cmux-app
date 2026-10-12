package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeForegroundMacSelectionTest {
    private val original = NativeCredentialStore.PairedMac("same-route", "device", "Original", "default", "user", "a", "origin-a", ticketRevision = "ticket-a")
    private fun selected(requested: NativeCredentialStore.PairedMac = original,
        admitted: NativeCredentialStore.PairedMac? = original, ready: Boolean = true,
        code: String = original.code, connected: String? = original.code, permitted: Boolean = true) =
        nativeForegroundMacSelected(requested, admitted, ready, code, connected, permitted)

    @Test fun renamedAdmittedRecordKeepsItsWarmConnectionButSameCodeSiblingsCannot() {
        assertTrue(selected(original.copy(name = "Renamed")))
        for (other in listOf(original.copy(accountTeamId = "b", stableOrigin = "origin-b"),
            original.copy(accountUserId = "other"), original.copy(deviceId = "other"), original.copy(instanceTag = "nightly"),
            original.copy(ticketRevision = "new-ticket"), original.copy(nativeRouteCode = "new-native-route")))
            assertFalse(selected(other))
    }
    @Test fun readinessPermissionAndBothLocatorBindingsAreRequired() {
        for (result in listOf(selected(admitted = null), selected(ready = false), selected(permitted = false),
            selected(code = "new-route"), selected(connected = null), selected(connected = "other"))) assertFalse(result)
    }
    @Test fun pickerTeamRowSharingTheLocatorDoesNotInheritForegroundStatusOrWorkspaceCount() {
        val sibling = original.copy(accountTeamId = "b", stableOrigin = "origin-b")
        val status = nativeComputerConnections(listOf(sibling), emptyMap(), original.code, null, emptyList(),
            isForeground = { selected(it) }).values.single()
        assertFalse(status.foreground); assertEquals(NativeFeedAvailability.OFFLINE, status.availability)
        assertNull(status.workspaceCount)
    }
}
