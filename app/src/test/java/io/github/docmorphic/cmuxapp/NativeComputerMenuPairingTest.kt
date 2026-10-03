package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeComputerMenuPairingTest {
    private val mac = NativeCredentialStore.PairedMac("route-a", "device", "Mac", "nightly", "user", "team", "origin")

    @Test fun displayChangesKeepTheExactPairingSelectable() {
        assertTrue(NativeComputerMenuPairing.isCurrent(mac, listOf(mac.copy(name = "Renamed", previousOrigins = setOf("old")))))
    }
    @Test fun replacementCannotReuseTheVisibleRowsIdentity() {
        listOf(mac.copy(code = "route-b"), mac.copy(deviceId = "other"), mac.copy(instanceTag = "default"),
            mac.copy(accountUserId = "other"), mac.copy(accountTeamId = "other"), mac.copy(stableOrigin = "other"))
            .forEach { assertFalse(NativeComputerMenuPairing.isCurrent(mac, listOf(it))) }
    }
    @Test fun removedAndAmbiguousPairingsAreRejected() {
        assertFalse(NativeComputerMenuPairing.isCurrent(mac, emptyList()))
        assertFalse(NativeComputerMenuPairing.isCurrent(mac, listOf(mac, mac.copy(code = "route-b"))))
    }
    @Test fun siblingInstallationDoesNotReplaceTheSelectedInstance() {
        val sibling = mac.copy(instanceTag = "default", code = "stable-route", stableOrigin = "stable-origin")
        assertTrue(NativeComputerMenuPairing.isCurrent(mac, listOf(sibling, mac)))
        assertFalse(NativeComputerMenuPairing.isCurrent(mac, listOf(sibling)))
    }
}
