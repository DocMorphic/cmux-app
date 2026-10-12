package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeComputerConnectionProjectionTest {
    private val original = NativeCredentialStore.PairedMac("shared-locator", "device", "Original", "default", "user", "original", "origin-a")
    private val selected = original.copy(name = "Picker", accountTeamId = "picker", stableOrigin = "origin-b")
    private fun workspace(id: String) = NativeWorkspace(id, id, emptyList(), null, false, null, null, false, emptyList(), null, null, null)
    private fun source(mac: NativeCredentialStore.PairedMac) = NativeFeedSource(mac, workspaces = listOf(workspace("picker-workspace")),
        availability = NativeFeedAvailability.CONNECTED, hasWorkspaceSnapshot = true, connectionTransport = MobileConnectionTransport.IROH,
        capabilities = setOf("caffeine.control.v1"), keepAwake = true)

    @Test fun sameDeviceAndLocatorKeepSeparateOwnerTransportCountAndAwakeState() {
        val projection = nativeRecordComputerConnections(listOf(original, selected), mapOf(selected.origin to source(selected)),
            listOf(workspace("one"), workspace("two")), MobileConnectionTransport.TAILSCALE, { it == original }, { false })
        assertEquals(NativeComputerConnection(NativeFeedAvailability.CONNECTED, true, 2, null, MobileConnectionTransport.TAILSCALE), projection[original])
        assertEquals(NativeComputerConnection(NativeFeedAvailability.CONNECTED, false, 1, true, MobileConnectionTransport.IROH), projection[selected])
        assertNull(projection[selected.copy(accountUserId = "other")]); assertNull(projection[selected.copy(instanceTag = "nightly")])
        val selectedMap = projection.selectedValues(listOf(selected))
        assertEquals(projection[selected], selectedMap[NativeMacIdentity(selected.deviceId, selected.instanceTag)])
        assertEquals(1, selectedMap.size); assertEquals(1, selectedMap.values.single().workspaceCount)
        assertFalse(selectedMap.values.single().foreground)
        val rows = nativeSettingsConnectionRows(listOf(original, selected), projection)
        assertEquals(listOf(original, selected), rows.map { it.mac }); assertEquals(2, rows.map { it.owner }.distinct().size)
    }

    @Test fun retiredForegroundAndExactPendingRecordDoNotRelabelSameLocatorPicker() {
        val projection = nativeRecordComputerConnections(listOf(selected), emptyMap(), emptyList(), null, { false },
            { NativeComputerMenuPairing.isCurrent(it, listOf(original)) })
        assertNull(projection[original]); assertEquals(NativeComputerConnection(), projection[selected])
        assertEquals(NativeComputerConnection(), projection.selectedValues(listOf(selected)).values.single())
        assertTrue(nativeSettingsConnectionRows(listOf(original, selected), projection).isEmpty())
    }

    @Test fun harmlessNameUpdatesKeepVerifiedSourceButRecordRevisionChangesRetireIt() {
        val renamed = selected.copy(name = "Renamed")
        fun project(record: NativeCredentialStore.PairedMac) = nativeRecordComputerConnections(listOf(record), mapOf(selected.origin to source(selected)),
            emptyList(), null, { false }, { false })
        assertEquals(MobileConnectionTransport.IROH, project(renamed)[renamed]?.transport)
        assertEquals(true, project(renamed)[selected]?.keepAwake)
        for (changed in listOf(renamed.copy(ticketRevision = "new"), renamed.copy(nativeRouteCode = "new"), renamed.copy(code = "new"))) {
            assertNull(project(changed)[selected]); assertEquals(NativeComputerConnection(), project(changed)[changed])
        }
    }

    @Test fun ambiguousOwnedInstancesAndCaseVariantsCannotChooseAnArbitrarySource() {
        val first = selected.copy(deviceId = "12345678-1234-1234-1234-123456789abc")
        val duplicate = first.copy(deviceId = first.deviceId.uppercase(), code = "other", stableOrigin = "other-origin")
        val projection = nativeRecordComputerConnections(listOf(first, duplicate), mapOf(first.origin to source(first)), emptyList(), null, { true }, { false })
        assertNull(projection[first]); assertNull(projection[duplicate])
        assertTrue(nativeSettingsConnectionRows(listOf(first, duplicate), projection).isEmpty())
    }
}
