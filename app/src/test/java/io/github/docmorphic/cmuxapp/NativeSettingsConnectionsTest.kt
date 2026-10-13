package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeSettingsConnectionsTest {
    private val stable = NativeCredentialStore.PairedMac("stable", "mac", "Mac", "default")
    private val sibling = stable.copy(code = "nightly", instanceTag = "nightly")
    private fun connected(transport: MobileConnectionTransport? = null) =
        NativeComputerConnection(NativeFeedAvailability.CONNECTED, transport = transport)
    private fun identity(mac: NativeCredentialStore.PairedMac) = NativeMacIdentity(mac.deviceId, mac.instanceTag)

    @Test fun originalForegroundAndSelectedSameLocatorRowsKeepSeparateOwnersAndTransports() {
        val original = stable.copy(code = "shared", accountUserId = "user", accountTeamId = "original", stableOrigin = "original")
        val picker = original.copy(name = "Picker", accountTeamId = "picker", stableOrigin = "picker")
        val sources = mapOf(picker.origin to NativeFeedSource(picker, availability = NativeFeedAvailability.CONNECTED,
            hasWorkspaceSnapshot = true, connectionTransport = MobileConnectionTransport.TAILSCALE))
        val projection = nativeRecordComputerConnections(listOf(original, picker), sources, emptyList(),
            MobileConnectionTransport.IROH, isForeground = { it == original }, isPending = { false })
        val rows = nativeSettingsConnectionRows(listOf(original, picker), projection)
        assertEquals(listOf(original, picker), rows.map { it.mac })
        assertNotEquals(rows[0].owner, rows[1].owner)
        assertTrue(rows[0].connection.foreground); assertFalse(rows[1].connection.foreground)
        assertEquals(listOf(MobileConnectionTransport.IROH, MobileConnectionTransport.TAILSCALE), rows.map { it.connection.transport })
    }

    @Test fun missingOriginalWireNeverBorrowsTheSelectedTeamsSameLocatorRow() {
        val original = stable.copy(code = "shared", accountUserId = "user", accountTeamId = "original", stableOrigin = "original")
        val picker = original.copy(accountTeamId = "picker", stableOrigin = "picker")
        val projection = nativeRecordComputerConnections(listOf(original, picker), mapOf(picker.origin to
            NativeFeedSource(picker, availability = NativeFeedAvailability.CONNECTED)), emptyList(), null,
            isForeground = { false }, isPending = { false })
        assertEquals(listOf(picker), nativeSettingsConnectionRows(listOf(original, picker), projection).map { it.mac })
    }

    @Test fun everyConnectedAppInstanceKeepsItsOwnTransportAndSavedOrder() {
        val rows = nativeSettingsConnectionRows(listOf(sibling, stable), mapOf(
            identity(stable) to connected(MobileConnectionTransport.IROH),
            identity(sibling) to connected(MobileConnectionTransport.TAILSCALE)))
        assertEquals(listOf(sibling, stable), rows.map { it.mac })
        assertEquals(listOf(MobileConnectionTransport.TAILSCALE, MobileConnectionTransport.IROH),
            rows.map { it.connection.transport })
    }

    @Test fun discoveryMissingConnectionsAndOfflineSnapshotsCannotCreateLiveRows() {
        assertTrue(nativeSettingsConnectionRows(listOf(stable), emptyMap()).isEmpty())
        for (phase in listOf(NativeFeedAvailability.CONNECTING, NativeFeedAvailability.OFFLINE)) {
            assertTrue(nativeSettingsConnectionRows(listOf(stable), mapOf(identity(stable) to
                NativeComputerConnection(phase, transport = MobileConnectionTransport.IROH))).isEmpty())
        }
        assertTrue(nativeSettingsConnectionRows(emptyList(), mapOf(identity(stable) to connected())).isEmpty())
    }

    @Test fun ambiguousPairingsAndCaseVariantsCannotAliasAnotherLiveRow() {
        val uuid = stable.copy(deviceId = "12345678-1234-1234-1234-123456789abc")
        val duplicate = uuid.copy(code = "other", deviceId = uuid.deviceId.uppercase())
        assertTrue(nativeSettingsConnectionRows(listOf(uuid, duplicate), mapOf(identity(uuid) to connected())).isEmpty())
        assertTrue(nativeSettingsConnectionRows(listOf(stable, stable.copy(code = "other")),
            mapOf(identity(stable) to connected())).isEmpty())
    }

    @Test fun connectedUnknownTransportRemainsUnknownAndSiblingConnectionCannotSubstitute() {
        assertNull(nativeSettingsConnectionRows(listOf(stable), mapOf(identity(stable) to connected())).single().connection.transport)
        assertTrue(nativeSettingsConnectionRows(listOf(stable), mapOf(identity(sibling) to connected())).isEmpty())
    }
}
