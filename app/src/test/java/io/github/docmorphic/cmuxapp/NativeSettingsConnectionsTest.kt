package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeSettingsConnectionsTest {
    private val stable = NativeCredentialStore.PairedMac("stable", "mac", "Mac", "default")
    private val sibling = stable.copy(code = "nightly", instanceTag = "nightly")
    private fun connected(transport: MobileConnectionTransport? = null) =
        NativeComputerConnection(NativeFeedAvailability.CONNECTED, transport = transport)
    private fun identity(mac: NativeCredentialStore.PairedMac) = NativeMacIdentity(mac.deviceId, mac.instanceTag)

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
