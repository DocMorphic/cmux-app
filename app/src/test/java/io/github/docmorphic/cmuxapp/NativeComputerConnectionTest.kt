package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class NativeComputerConnectionTest {
    private val mac = NativeCredentialStore.PairedMac("code", "mac", "Mac", "default")
    private val identity = NativeMacIdentity("mac", "default")
    private fun workspace(id: String) = NativeWorkspace(id, id, emptyList(), null, false, null, null, false, emptyList(), null, null, null)
    private fun project(source: NativeFeedSource? = null, active: String? = null, pending: String? = null,
                        workspaces: List<NativeWorkspace> = emptyList()) = nativeComputerConnections(listOf(mac),
        source?.let { mapOf(it.mac.origin to it) }.orEmpty(), active, pending, workspaces).getValue(identity)

    @Test fun discoveredOrSelectedComputerDoesNotImplyConnectionOrZeroWorkspaces() {
        assertEquals(NativeComputerConnection(), project())
        assertEquals("Not connected", project().phrase)
        assertNull(project().workspaceCount)
    }

    @Test fun exactVerifiedForegroundOverridesFeedStatusAndCount() {
        val source = NativeFeedSource(mac, workspaces = listOf(workspace("stale")),
            availability = NativeFeedAvailability.OFFLINE, hasWorkspaceSnapshot = true)
        val result = project(source, active = mac.code, workspaces = listOf(workspace("1"), workspace("2")))
        assertEquals(NativeComputerConnection(NativeFeedAvailability.CONNECTED, true, 2), result)
        assertEquals("Connected", result.phrase)
    }

    @Test fun backgroundFeedConnectionHasNoForegroundRoleAndRetainsCountDuringReconnect() {
        val source = NativeFeedSource(mac, workspaces = listOf(workspace("1")),
            availability = NativeFeedAvailability.CONNECTED, hasWorkspaceSnapshot = true)
        assertEquals(NativeComputerConnection(NativeFeedAvailability.CONNECTED, false, 1), project(source))
        val reconnecting = project(source.copy(availability = NativeFeedAvailability.OFFLINE), pending = mac.code)
        assertEquals(NativeComputerConnection(NativeFeedAvailability.CONNECTING, false, 1), reconnecting)
        assertEquals("Reconnecting…", reconnecting.phrase)
        assertEquals(1, project(source.copy(availability = NativeFeedAvailability.OFFLINE)).workspaceCount)
    }

    @Test fun unknownAndConfirmedEmptySnapshotsDiffer() {
        assertNull(project(NativeFeedSource(mac)).workspaceCount)
        assertEquals(0, project(NativeFeedSource(mac, hasWorkspaceSnapshot = true)).workspaceCount)
        assertEquals(0, project(active = mac.code).workspaceCount)
    }

    @Test fun siblingBuildAndRetiredPairingCannotSupplyStatusOrCount() {
        val sibling = mac.copy(code = "debug-code", instanceTag = "debug")
        val source = NativeFeedSource(sibling, workspaces = listOf(workspace("1")),
            availability = NativeFeedAvailability.CONNECTED, hasWorkspaceSnapshot = true)
        val result = nativeComputerConnections(listOf(mac, sibling), mapOf(sibling.origin to source), sibling.code, null, emptyList())
        assertEquals(NativeComputerConnection(), result[identity])
        assertTrue(result.getValue(NativeMacIdentity("mac", "debug")).foreground)
        val retired = mac.copy(code = "retired")
        assertEquals(NativeComputerConnection(), nativeComputerConnections(listOf(mac),
            mapOf(mac.origin to source.copy(mac = retired)), retired.code, retired.code, listOf(workspace("1")))[identity])
        assertTrue(nativeComputerConnections(emptyList(), mapOf(mac.origin to source), mac.code, null, emptyList()).isEmpty())
    }

    @Test fun ambiguousIdentityDoesNotBorrowOneOfItsConnections() {
        val duplicate = mac.copy(code = "another-code")
        assertTrue(nativeComputerConnections(listOf(mac, duplicate), emptyMap(), mac.code, null, emptyList()).isEmpty())
    }
}
