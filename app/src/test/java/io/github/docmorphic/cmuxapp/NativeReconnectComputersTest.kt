package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeReconnectComputersTest {
    private val team = NativeTeamScope("login", "user", "team", 1)
    private val device = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private fun directory(tag: String = "default", endpoint: String = "peer", id: String = device) =
        IrohV2Computer("record-$id-$tag", endpoint, id, tag, "Discovered name", emptyList())
    private fun saved(tag: String? = "default") = NativeComputerListRow(
        NativeCredentialStore.PairedMac("saved-route", device, "Custom name", tag), "Custom name",
        NativeComputerPresence(false, 1234), NativeComputerRouteLabel(NativeMacConnectionMethod.DIRECT, "192.168.1.2:42"))

    @Test fun offlineSavedRecordsSurviveEmptyDiscoveryWithoutRewritingRoutesOrNames() {
        val row = saved()
        val result = NativeReconnectComputers.merge(listOf(row), emptyList())
        assertFalse(result.isEmpty)
        assertSame(row, result.saved.single())
        assertEquals("saved-route", result.saved.single().mac.code)
        assertTrue(result.discovered.isEmpty())
        assertTrue(NativeReconnectComputers.merge(emptyList(), emptyList()).isEmpty)
    }
    @Test fun matchingExactDiscoveryDoesNotDuplicateSavedRowsButOtherBuildsAndLegacyAmbiguityRemain() {
        val exact = directory(id = device.uppercase())
        val nightly = directory("nightly")
        val result = NativeReconnectComputers.merge(listOf(saved()), listOf(exact, nightly, nightly))
        assertEquals(listOf(nightly), result.discovered)
        assertEquals(1, result.saved.size)
        assertEquals(listOf(exact), NativeReconnectComputers.merge(listOf(saved(null)), listOf(exact)).discovered)
    }
    @Test fun ambiguousNewDiscoveryIsNotSelectableAndOldAccountGenerationCannotBeUsed() {
        val first = directory(); val second = directory(endpoint = "replaced")
        assertTrue(NativeReconnectComputers.merge(emptyList(), listOf(first, second)).isEmpty)
        val state = NativeComputersState(team, ready = true, computers = listOf(first))
        assertTrue(NativeReconnectComputers.currentDiscovery(first, state, team))
        assertFalse(NativeReconnectComputers.currentDiscovery(first, state.copy(ready = false), team))
        assertFalse(NativeReconnectComputers.currentDiscovery(first, state, team.copy(generation = 2)))
        assertFalse(NativeReconnectComputers.currentDiscovery(first, state, null))
        assertFalse(NativeReconnectComputers.currentDiscovery(first, state.copy(computers = listOf(second)), team))
        assertFalse(NativeReconnectComputers.currentDiscovery(first, state.copy(computers = listOf(first, second)), team))
        assertTrue(NativeReconnectComputers.currentDiscovery(first, state.copy(computers = listOf(first, first)), team))
    }
}
