package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeWorkspaceFilterTest {
    private val device = "B1B8B422-E305-40D0-BE37-86F97D095C68"
    private val stable = workspaceMacFilterId(device, "default")!!
    private val nightly = workspaceMacFilterId(device, "nightly")!!
    private val legacy = workspaceMacFilterId(device, null)!!
    @Test fun combinesUnreadWithExactBuildIdentityAndDoesNotMatchUnknownOwners() {
        val filter = NativeWorkspaceFilter(true, setOf(stable, nightly))
        assertTrue(filter.matches(workspaceMacFilterId(device.lowercase(), "default"), true))
        assertTrue(filter.matches(nightly, true))
        assertFalse(filter.matches(stable, false))
        assertFalse(filter.matches(legacy, true))
        assertFalse(filter.matches(null, true))
        assertTrue(NativeWorkspaceFilter(true).matches(null, true))
        assertFalse(NativeWorkspaceFilter(true).matches(null, false))
    }
    @Test fun opaqueMacAndSshIdsCannotCollideAndUnknownDeviceHasNoFilterIdentity() {
        val ssh = UUID.randomUUID()
        assertNotEquals(workspaceSshFilterId(ssh), workspaceMacFilterId(ssh.toString(), null))
        assertNotEquals(workspaceMacFilterId("MAC", "nightly"), workspaceMacFilterId("mac", "nightly"))
        assertNotEquals(workspaceMacFilterId("a:b", "c"), workspaceMacFilterId("a", "b:c"))
        assertNull(workspaceMacFilterId("", "default"))
    }
    @Test fun hiddenOrUnavailableMachineSelectionClearsWithoutLosingUnread() {
        val filter = NativeWorkspaceFilter(true, setOf(stable, nightly))
        assertEquals(setOf(stable), filter.forMenu(setOf(stable, legacy), false).machines)
        assertEquals(NativeWorkspaceFilter(true), filter.forMenu(setOf(stable), false))
        assertEquals(NativeWorkspaceFilter(true), filter.forMenu(setOf(stable, nightly), true))
        assertEquals(NativeWorkspaceFilter(true), filter.forMenu(emptySet(), false))
    }
    @Test fun toggleAddsAndRemovesIndependentMachinesAndEmptyMeansAll() {
        val filter = NativeWorkspaceFilter().toggle(stable).toggle(nightly)
        assertEquals(setOf(stable, nightly), filter.machines)
        assertEquals(setOf(nightly), filter.toggle(stable).machines)
        assertFalse(filter.toggle(stable).toggle(nightly).active)
        assertTrue(filter.toggle(stable).toggle(nightly).matches(legacy, false))
    }
}
